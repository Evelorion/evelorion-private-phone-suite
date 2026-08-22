package com.example.sms.data.repo

import android.content.Context
import android.net.Uri
import com.example.sms.data.db.AppDatabase
import com.example.sms.data.db.BlockedLogEntity
import com.example.sms.data.db.ConversationEntity
import com.example.sms.data.db.MessageEntity
import com.example.sms.data.db.MsgStatus
import com.example.sms.data.db.MsgType
import com.example.sms.data.prefs.SettingsStore
import com.example.sms.data.system.ContactsRepository
import com.example.sms.data.system.SystemSmsStore
import com.example.sms.util.Classifier
import com.example.sms.util.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MessageRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
    val contacts: ContactsRepository = ContactsRepository(context),
    val systemSms: SystemSmsStore = SystemSmsStore(context),
    private val settings: SettingsStore = SettingsStore(context),
) {
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val blockedDao = db.blockedLogDao()
    private val threadLock = Mutex()

    /* ------------------------- 查询 ------------------------- */

    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun observeConversation(threadId: Long): Flow<ConversationEntity?> =
        conversationDao.observeById(threadId)

    fun observeThread(threadId: Long): Flow<List<MessageEntity>> =
        messageDao.observeThread(threadId)

    fun observeBlockedCountThisMonth(): Flow<Int> =
        blockedDao.observeCountSince(startOfMonth())

    suspend fun getConversation(threadId: Long): ConversationEntity? = conversationDao.getById(threadId)

    suspend fun searchMessages(q: String): List<MessageEntity> =
        if (q.isBlank()) emptyList() else messageDao.search(q)

    suspend fun recentIncoming(days: Int = 30): List<MessageEntity> =
        messageDao.recentIncoming(System.currentTimeMillis() - days * 86_400_000L)

    /* ------------------------- 写入 ------------------------- */

    /** 找到或创建一条会话；address 可以是 "138...;139..." 这样的多收件人 */
    suspend fun ensureThread(addressRaw: String, nameHint: String? = null): Long = threadLock.withLock {
        val address = PhoneUtils.joinAddresses(PhoneUtils.splitAddresses(addressRaw).ifEmpty { listOf(addressRaw) })
        conversationDao.getByAddress(address)?.let { return@withLock it.threadId }
        // 号码尾号匹配一次，避免 +86 前缀造成重复会话
        conversationDao.observeAll().first().firstOrNull { existing ->
            val a = PhoneUtils.splitAddresses(existing.address)
            val b = PhoneUtils.splitAddresses(address)
            a.size == b.size && a.zip(b).all { (x, y) -> PhoneUtils.sameNumber(x, y) }
        }?.let { return@withLock it.threadId }

        val id = conversationDao.nextThreadId()
        val display = nameHint
            ?: PhoneUtils.splitAddresses(address).let { list ->
                if (list.size == 1) contacts.lookupName(list[0]) ?: PhoneUtils.format(list[0])
                else "群发（" + list.size + "）"
            }
        conversationDao.upsert(
            ConversationEntity(
                threadId = id,
                address = address,
                displayName = display,
                lastTime = System.currentTimeMillis(),
            )
        )
        id
    }

    /** 收到一条短信 */
    suspend fun onIncoming(address: String, body: String, time: Long): Long? {
        val blockSpam = settings.settings.first().blockSpam
        if (blockSpam && Classifier.isSpam(address, body)) {
            blockedDao.insert(BlockedLogEntity(address = address, body = body, time = time))
            return null
        }
        val threadId = ensureThread(address)
        val existing = conversationDao.getById(threadId)
        if (existing?.blocked == true) {
            blockedDao.insert(BlockedLogEntity(address = address, body = body, time = time))
            return null
        }
        messageDao.insert(
            MessageEntity(
                threadId = threadId,
                address = PhoneUtils.normalize(address),
                body = body,
                time = time,
                outgoing = false,
                read = false,
                status = MsgStatus.RECEIVED,
            )
        )
        conversationDao.refreshSummary(threadId, body, false, time)
        retitleIfNeeded(threadId, address)
        recategorize(threadId, address, body)
        return threadId
    }

    /** 本地登记一条待发送消息，返回消息 id */
    suspend fun createOutgoing(
        threadId: Long,
        address: String,
        body: String,
        type: MsgType = MsgType.TEXT,
        attachmentUri: String? = null,
        durationMs: Long = 0L,
    ): Long {
        val now = System.currentTimeMillis()
        val id = messageDao.insert(
            MessageEntity(
                threadId = threadId,
                address = PhoneUtils.normalize(address),
                body = body,
                time = now,
                outgoing = true,
                read = true,
                status = MsgStatus.PENDING,
                type = type,
                attachmentUri = attachmentUri,
                durationMs = durationMs,
            )
        )
        val snippet = when (type) {
            MsgType.IMAGE -> body.ifBlank { "图片" }
            MsgType.VOICE -> "语音"
            MsgType.TEXT -> body
        }
        conversationDao.refreshSummary(threadId, snippet, type == MsgType.IMAGE, now)
        return id
    }

    suspend fun setStatus(messageId: Long, status: MsgStatus, error: String? = null) =
        messageDao.setStatus(messageId, status, error)

    suspend fun prepareMultipart(messageId: Long, partCount: Int) =
        messageDao.prepareMultipart(messageId, partCount.coerceAtLeast(1))

    /** @return 这次失败是否真的把仍在发送中的消息改成了失败。 */
    suspend fun recordSendFailure(messageId: Long, error: String): Boolean =
        messageDao.markFailedIfPending(messageId, error) > 0

    /** @return 所有分段是否都已发送成功。 */
    suspend fun recordSentPart(messageId: Long): Boolean {
        messageDao.incrementSentParts(messageId)
        messageDao.markSentWhenComplete(messageId)
        // 某些基带会先送达 delivered 广播；发送完成后再补一次状态合并。
        messageDao.markDeliveredWhenComplete(messageId)
        return messageDao.getById(messageId)?.status in setOf(MsgStatus.SENT, MsgStatus.DELIVERED)
    }

    /** @return 所有分段是否都已取得送达回执。 */
    suspend fun recordDeliveredPart(messageId: Long): Boolean {
        messageDao.incrementDeliveredParts(messageId)
        messageDao.markDeliveredWhenComplete(messageId)
        return messageDao.getById(messageId)?.status == MsgStatus.DELIVERED
    }

    suspend fun setReaction(messageId: Long, reaction: String?) =
        messageDao.setReaction(messageId, reaction)

    suspend fun markRead(threadId: Long) {
        messageDao.markThreadRead(threadId)
        conversationDao.clearUnread(threadId)
        conversationDao.getById(threadId)?.let { conv ->
            PhoneUtils.splitAddresses(conv.address).forEach { systemSms.markSystemThreadRead(it) }
        }
    }

    /** 一键已读：把所有会话的未读清零，并撤掉全部通知 */
    suspend fun markAllRead() {
        messageDao.markAllRead()
        conversationDao.clearAllUnread()
        conversationDao.observeAll().first()
            .flatMap { PhoneUtils.splitAddresses(it.address) }
            .forEach { systemSms.markSystemThreadRead(it) }
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
        }
    }

    suspend fun saveDraft(threadId: Long, draft: String) = conversationDao.setDraft(threadId, draft)
    suspend fun setPinned(threadId: Long, v: Boolean) = conversationDao.setPinned(threadId, v)
    suspend fun setMuted(threadId: Long, v: Boolean) = conversationDao.setMuted(threadId, v)
    suspend fun setBlocked(threadId: Long, v: Boolean) = conversationDao.setBlocked(threadId, v)

    /** 本地保存已经完成后，再按隐私设置删除对应的系统短信副本。 */
    suspend fun deleteSystemCopyIfEnabled(uri: Uri?) {
        if (uri == null) return
        if (settings.settings.first().deleteSystemSmsAfterImport) {
            systemSms.deleteByUri(uri)
        }
    }

    suspend fun deleteMessage(messageId: Long) {
        val m = messageDao.getById(messageId) ?: return
        messageDao.delete(messageId)
        val last = messageDao.observeThread(m.threadId).first().lastOrNull()
        conversationDao.refreshSummary(
            m.threadId, last?.body.orEmpty(), last?.type == MsgType.IMAGE, last?.time ?: 0L,
        )
    }

    suspend fun deleteConversation(threadId: Long) {
        messageDao.deleteThread(threadId)
        conversationDao.delete(threadId)
    }

    /* ------------------------- 系统短信导入 ------------------------- */

    /** 把系统短信库里已有的短信导入本地库；已导入过的用 systemId 去重 */
    suspend fun importSystemSms(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val appSettings = settings.settings.first()
        val already = appSettings.importedSystemSms
        if (already && !force) return@withContext 0

        val raw = systemSms.readAll()
        if (raw.isEmpty()) {
            settings.setImported(true)
            return@withContext 0
        }
        // 按系统 threadId 分组建会话
        val grouped = raw.groupBy { it.threadId }
        var inserted = 0
        for ((_, list) in grouped) {
            val address = list.firstOrNull { it.address.isNotBlank() }?.address ?: continue
            val threadId = ensureThread(address)
            val entities = list.map { r ->
                MessageEntity(
                    threadId = threadId,
                    address = PhoneUtils.normalize(r.address),
                    body = r.body,
                    time = r.time,
                    outgoing = r.outgoing,
                    read = r.read || r.outgoing,
                    status = if (r.outgoing) MsgStatus.SENT else MsgStatus.RECEIVED,
                    systemId = r.systemId,
                )
            }
            messageDao.insertAll(entities)
            if (appSettings.deleteSystemSmsAfterImport) {
                // insertAll 成功返回才清理；即使是去重命中的旧消息，本地也已有副本。
                systemSms.deleteByIds(entities.mapNotNull { it.systemId })
            }
            inserted += entities.size
            val newest = list.maxByOrNull { it.time } ?: continue
            conversationDao.refreshSummary(threadId, newest.body, false, newest.time)
            retitleIfNeeded(threadId, address)
            recategorize(threadId, address, newest.body)
        }
        settings.setImported(true)
        inserted
    }

    /* ------------------------- 内部 ------------------------- */

    private suspend fun retitleIfNeeded(threadId: Long, address: String) {
        val conv = conversationDao.getById(threadId) ?: return
        val looksLikeNumber = conv.displayName.isBlank() ||
            conv.displayName.filter { it.isDigit() }.length >= 5
        if (!looksLikeNumber) return
        val name = contacts.lookupName(address) ?: return
        conversationDao.update(conv.copy(displayName = name))
    }

    private suspend fun recategorize(threadId: Long, address: String, body: String) {
        val conv = conversationDao.getById(threadId) ?: return
        val cat = Classifier.classify(address, body)
        if (conv.category != cat) conversationDao.update(conv.copy(category = cat))
    }

    private fun startOfMonth(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
