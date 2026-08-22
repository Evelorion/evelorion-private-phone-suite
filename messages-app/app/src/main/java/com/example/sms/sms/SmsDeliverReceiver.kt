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
 * 收到后由我们负责：写系统收件箱 + 写本地 Room + 按隐私设置清理系统副本 + 弹通知。
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        handleIncomingSms(context, intent, writeToSystemInbox = true)
    }
}

/** 彩信通知（WAP Push）；这里只登记一条占位消息并提示，完整 MMS 下载超出短信范畴 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!SmsRoleCheck.isDefaultSmsApp(context)) return
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
            val systemUri = if (writeToSystemInbox) {
                repo.systemSms.insertInbox(address, body, time, read = false)
            } else null
            // 先写本地私密库；只有这一步正常返回后，才允许删除系统层副本。
            val threadId = repo.onIncoming(address, body, time)
            repo.deleteSystemCopyIfEnabled(systemUri)
            if (threadId == null) return@launch // 已保存到本地拦截日志，不显示通知
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
