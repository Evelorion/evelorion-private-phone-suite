package com.example.sms.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import com.example.sms.data.db.MsgStatus
import com.example.sms.data.db.MsgType
import com.example.sms.data.repo.MessageRepository
import com.example.sms.util.PhoneUtils
import com.example.sms.util.SimUtils
import com.example.sms.util.SmsRoleCheck

/**
 * 真实发送：SmsManager.sendMultipartTextMessage
 * 发送结果 / 送达回执通过 PendingIntent 回到 SmsStatusReceiver，再写回 Room。
 */
class SmsSender(
    private val context: Context,
    private val repository: MessageRepository,
) {

    /**
     * 取 SmsManager。subId >= 0 时绑定到指定 SIM 卡，否则用系统默认卡。
     */
    @Suppress("DEPRECATION")
    private fun smsManager(subId: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        if (subId < 0) return base
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.createForSubscriptionId(subId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        }.getOrDefault(base)
    }

    /**
     * 发送一条短信。会先在本地库登记 PENDING，再交给系统发送。
     * @return 本地消息 id 列表（多收件人时每人一条）
     */
    suspend fun send(
        threadId: Long,
        addressJoined: String,
        body: String,
        subId: Int = SimUtils.SUB_DEFAULT,
    ): List<Long> {
        // Google Play 只允许当前默认短信应用使用 SEND_SMS。
        if (!SmsRoleCheck.isDefaultSmsApp(context)) return emptyList()
        val recipients = PhoneUtils.splitAddresses(addressJoined)
        if (recipients.isEmpty() || body.isBlank()) return emptyList()

        val ids = mutableListOf<Long>()
        for (address in recipients) {
            val messageId = repository.createOutgoing(threadId, address, body, MsgType.TEXT)
            ids += messageId
            dispatch(messageId, address, body, subId)
        }
        return ids
    }

    /** 重发一条失败的消息 */
    suspend fun retry(
        messageId: Long,
        address: String,
        body: String,
        subId: Int = SimUtils.SUB_DEFAULT,
    ) {
        if (!SmsRoleCheck.isDefaultSmsApp(context)) {
            repository.setStatus(messageId, MsgStatus.FAILED, "请先把本应用设为默认短信应用")
            return
        }
        repository.setStatus(messageId, MsgStatus.PENDING, null)
        dispatch(messageId, address, body, subId)
    }

    private suspend fun dispatch(messageId: Long, address: String, body: String, subId: Int) {
        val manager = runCatching { smsManager(subId) }.getOrNull()
        if (manager == null) {
            repository.setStatus(messageId, MsgStatus.FAILED, "设备不支持短信")
            return
        }
        // 写系统已发送库（默认短信应用的义务）
        val sentUri = repository.systemSms.insertSent(address, body, System.currentTimeMillis())

        val parts = manager.divideMessage(body)
        repository.prepareMultipart(messageId, parts.size)
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveredIntents = ArrayList<PendingIntent>(parts.size)

        for (i in parts.indices) {
            sentIntents += statusIntent(
                SmsStatusReceiver.ACTION_SENT, messageId, i, parts.size, sentUri?.toString(),
            )
            deliveredIntents += statusIntent(
                SmsStatusReceiver.ACTION_DELIVERED, messageId, i, parts.size, sentUri?.toString(),
            )
        }

        val result = runCatching {
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
        }
        if (result.isFailure) {
            repository.setStatus(
                messageId, MsgStatus.FAILED, result.exceptionOrNull()?.message ?: "发送失败",
            )
        }
    }

    private fun statusIntent(
        action: String,
        messageId: Long,
        partIndex: Int,
        partCount: Int,
        systemUri: String?,
    ): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            this.action = action
            putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(SmsStatusReceiver.EXTRA_PART_INDEX, partIndex)
            putExtra(SmsStatusReceiver.EXTRA_PART_COUNT, partCount)
            putExtra(SmsStatusReceiver.EXTRA_SYSTEM_URI, systemUri)
        }
        val requestCode = (messageId * 100 + partIndex).toInt() +
            if (action == SmsStatusReceiver.ACTION_DELIVERED) 1 else 0
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
