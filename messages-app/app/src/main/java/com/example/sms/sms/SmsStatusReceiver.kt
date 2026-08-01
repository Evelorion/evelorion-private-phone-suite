package com.example.sms.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.example.sms.Graph
import com.example.sms.data.db.MsgStatus
import kotlinx.coroutines.launch

/** 接收 SmsManager 的发送结果与送达回执 */
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SENT = "com.example.sms.SMS_SENT"
        const val ACTION_DELIVERED = "com.example.sms.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
        const val EXTRA_SYSTEM_URI = "system_uri"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val systemUri = intent.getStringExtra(EXTRA_SYSTEM_URI)
        Graph.init(context)
        val repo = Graph.repository
        val pending = goAsync()

        Graph.applicationScope.launch {
            try {
                when (intent.action) {
                    ACTION_SENT -> {
                        if (resultCode == Activity.RESULT_OK) {
                            if (repo.recordSentPart(messageId)) {
                                repo.systemSms.updateStatus(systemUri, MsgStatus.SENT)
                            }
                        } else {
                            repo.setStatus(messageId, MsgStatus.FAILED, errorText(resultCode))
                            repo.systemSms.updateStatus(systemUri, MsgStatus.FAILED)
                        }
                    }
                    ACTION_DELIVERED -> {
                        if (resultCode == Activity.RESULT_OK) {
                            repo.recordDeliveredPart(messageId)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "发送失败：一般错误"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "发送失败：无服务"
        SmsManager.RESULT_ERROR_NULL_PDU -> "发送失败：PDU 为空"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "发送失败：射频已关闭"
        else -> "发送失败（代码 " + code + "）"
    }
}
