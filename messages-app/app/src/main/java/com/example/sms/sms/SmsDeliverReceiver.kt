package com.example.sms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.sms.Graph
import com.example.sms.util.SmsRoleCheck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SMS_DELIVER —— 只有「默认短信应用」才会收到。
 * 收到后由我们负责：写系统收件箱 + 写本地 Room + 弹通知。
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        handleIncomingSms(context, intent, writeToSystemInbox = true)
    }
}

/**
 * SMS_RECEIVED —— 非默认短信应用时的兜底。
 * 如果本应用已是默认短信应用，这里直接忽略，避免和 SMS_DELIVER 重复入库。
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (SmsRoleCheck.isDefaultSmsApp(context)) return
        handleIncomingSms(context, intent, writeToSystemInbox = false)
    }
}

/** 彩信通知（WAP Push）；这里只登记一条占位消息并提示，完整 MMS 下载超出短信范畴 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        val repo = Graph.repository
        val pending = goAsync()
        Graph.applicationScope.launch {
            try {
                val from = intent.getStringExtra("address") ?: "彩信"
                repo.onIncoming(from, "[彩信] 收到一条多媒体消息", System.currentTimeMillis())
            } finally {
                pending.finish()
            }
        }
    }
}

/** 两个接收器共用的入库逻辑：合并长短信分段、入库、回写系统库、发通知 */
private fun BroadcastReceiver.handleIncomingSms(
    context: Context,
    intent: Intent,
    writeToSystemInbox: Boolean,
) {
    val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
        .getOrNull() ?: return
    if (messages.isEmpty()) return

    // 长短信会被拆成多个 PDU，按发件人合并成一条
    val address = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: return
    val body = messages.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
    val time = messages[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
    if (body.isBlank()) return

    Graph.init(context)
    val repo = Graph.repository
    val settings = Graph.settings
    val notifier = NotificationHelper(context)
    val pending = goAsync()

    Graph.applicationScope.launch {
        try {
            if (writeToSystemInbox) {
                repo.systemSms.insertInbox(address, body, time, read = false)
            }
            val threadId = repo.onIncoming(address, body, time) ?: return@launch // 被拦截
            val conv = repo.getConversation(threadId) ?: return@launch
            val prefs = settings.settings.first()
            if (prefs.notificationsEnabled && !conv.muted) {
                notifier.showNewMessage(threadId, conv.displayName, body, conv.address)
            }
        } finally {
            pending.finish()
        }
    }
}
