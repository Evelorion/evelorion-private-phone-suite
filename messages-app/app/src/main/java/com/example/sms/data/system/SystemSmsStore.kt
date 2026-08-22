package com.example.sms.data.system

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.sms.data.db.MsgStatus
import com.example.sms.util.SmsRoleCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 与系统短信数据库（Telephony.Sms）打交道：
 *  - 首次启动时把已有短信导入本地 Room
 *  - 作为默认短信应用时，收/发的短信先回写系统库
 *  - 用户开启私密清理后，仅在本地 Room 保存成功后删除系统库副本
 */
class SystemSmsStore(private val context: Context) {

    sealed interface ClearResult {
        data class Success(val deleted: Int) : ClearResult
        data object NotDefaultSmsApp : ClearResult
        data object MissingReadPermission : ClearResult
        data class Failed(val reason: String) : ClearResult
    }

    data class RawSms(
        val systemId: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val time: Long,
        val outgoing: Boolean,
        val read: Boolean,
    )

    private fun canRead(): Boolean = isDefaultSmsApp() && ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    fun isDefaultSmsApp(): Boolean =
        SmsRoleCheck.isDefaultSmsApp(context)

    /**
     * 首次只读取最近的短信（收件箱 + 已发送）。
     *
     * 旧版默认一次扫描 5000 条，首次启动时还要为每个会话匹配联系人，
     * 在短信较多的设备上会长时间停在加载状态。启动导入只负责让首页尽快
     * 可用，因此限制为最近 100 条；后续收到的新短信会实时写入本地库。
     */
    suspend fun readAll(limit: Int = INITIAL_IMPORT_LIMIT): List<RawSms> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val out = mutableListOf<RawSms>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                Telephony.Sms.DATE + " DESC LIMIT " + limit,
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getInt(5)
                    val outgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT ||
                        type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                        type == Telephony.Sms.MESSAGE_TYPE_QUEUED
                    out += RawSms(
                        systemId = c.getLong(0),
                        threadId = c.getLong(1),
                        address = c.getString(2) ?: "",
                        body = c.getString(3) ?: "",
                        time = c.getLong(4),
                        outgoing = outgoing,
                        read = c.getInt(6) == 1,
                    )
                }
            }
        }
        out
    }

    /** 把收到的短信写进系统收件箱（仅默认短信应用需要且被允许） */
    suspend fun insertInbox(address: String, body: String, time: Long, read: Boolean): Uri? =
        withContext(Dispatchers.IO) {
            if (!isDefaultSmsApp()) return@withContext null
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, time)
                put(Telephony.Sms.DATE_SENT, time)
                put(Telephony.Sms.READ, if (read) 1 else 0)
                put(Telephony.Sms.SEEN, if (read) 1 else 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            runCatching {
                context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            }.getOrNull()
        }

    /** 把发出的短信写进系统已发送 */
    suspend fun insertSent(address: String, body: String, time: Long): Uri? =
        withContext(Dispatchers.IO) {
            if (!isDefaultSmsApp()) return@withContext null
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, time)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            runCatching {
                context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }.getOrNull()
        }

    suspend fun updateStatus(uriString: String?, status: MsgStatus) = withContext(Dispatchers.IO) {
        if (uriString == null || !isDefaultSmsApp()) return@withContext
        val type = when (status) {
            MsgStatus.FAILED -> Telephony.Sms.MESSAGE_TYPE_FAILED
            MsgStatus.PENDING -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
            else -> Telephony.Sms.MESSAGE_TYPE_SENT
        }
        runCatching {
            context.contentResolver.update(
                Uri.parse(uriString), ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null,
            )
        }
        Unit
    }

    suspend fun markSystemThreadRead(address: String) = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext
        runCatching {
            context.contentResolver.update(
                Telephony.Sms.Inbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                },
                Telephony.Sms.ADDRESS + " = ? AND " + Telephony.Sms.READ + " = 0",
                arrayOf(address),
            )
        }
        Unit
    }

    /** 删除刚写入系统库的单条副本；只使用 _id，避免误删相同号码的其它短信。 */
    suspend fun deleteByUri(uri: Uri?): Int = withContext(Dispatchers.IO) {
        if (uri == null || !isDefaultSmsApp()) return@withContext 0
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return@withContext 0
        deleteByIdsInternal(listOf(id))
    }

    /** 删除一批已经确认导入本地私密库的系统短信。 */
    suspend fun deleteByIds(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext 0
        ids.filter { it >= 0L }.distinct().chunked(DELETE_BATCH_SIZE).sumOf { batch ->
            deleteByIdsInternal(batch)
        }
    }

    /**
     * 一键清空 Android 系统短信库。这里不会触碰本应用 Room 数据库。
     * 只有当前默认短信应用才允许写系统 Provider。
     */
    suspend fun clearAll(): ClearResult = withContext(Dispatchers.IO) {
        if (!isDefaultSmsApp()) return@withContext ClearResult.NotDefaultSmsApp
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ClearResult.MissingReadPermission
        }
        runCatching {
            context.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null)
        }.fold(
            onSuccess = { ClearResult.Success(it) },
            onFailure = { ClearResult.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    private fun deleteByIdsInternal(ids: Collection<Long>): Int {
        val selection = SystemSmsDeleteSelection.byIds(ids) ?: return 0
        return runCatching {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                selection.where,
                selection.args,
            )
        }.getOrDefault(0)
    }

    private companion object {
        const val INITIAL_IMPORT_LIMIT = 100
        const val DELETE_BATCH_SIZE = 200
    }
}
