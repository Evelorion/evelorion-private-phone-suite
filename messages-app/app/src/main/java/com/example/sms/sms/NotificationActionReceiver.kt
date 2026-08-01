package com.example.sms.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.sms.Graph
import kotlinx.coroutines.launch

/** 通知上的「回复 / 标记已读 / 复制验证码」 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.example.sms.ACTION_REPLY"
        const val ACTION_MARK_READ = "com.example.sms.ACTION_MARK_READ"
        const val ACTION_COPY_CODE = "com.example.sms.ACTION_COPY_CODE"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NOTIFY_ID = "notify_id"
        const val EXTRA_CODE = "code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, -1)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)

        when (intent.action) {
            ACTION_COPY_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(context, "已复制 " + code, Toast.LENGTH_SHORT).show()
                if (notifyId >= 0) NotificationManagerCompat.from(context).cancel(notifyId)
            }

            ACTION_MARK_READ -> {
                if (threadId < 0) return
                val pending = goAsync()
                Graph.applicationScope.launch {
                    try {
                        Graph.repository.markRead(threadId)
                        if (notifyId >= 0) NotificationManagerCompat.from(context).cancel(notifyId)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(NotificationHelper.KEY_REPLY)?.toString()
                val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
                if (text.isNullOrBlank() || threadId < 0 || address.isBlank()) return
                val pending = goAsync()
                Graph.applicationScope.launch {
                    try {
                        Graph.sender.send(threadId, address, text)
                        Graph.repository.markRead(threadId)
                        if (notifyId >= 0) NotificationManagerCompat.from(context).cancel(notifyId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
