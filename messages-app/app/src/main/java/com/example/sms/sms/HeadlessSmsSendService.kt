package com.example.sms.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import com.example.sms.Graph
import kotlinx.coroutines.launch

/**
 * 默认短信应用必备组件：来电界面「快捷回复短信」会走这里。
 * 收到 RESPOND_VIA_MESSAGE 后直接发送，不显示界面。
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.extras?.getCharSequence(Intent.EXTRA_TEXT)?.toString()
        val recipients = intent.data?.schemeSpecificPart
            ?.split(",", ";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (text.isNullOrBlank() || recipients.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Graph.init(applicationContext)
        Graph.applicationScope.launch {
            try {
                for (r in recipients) {
                    val threadId = Graph.repository.ensureThread(r)
                    Graph.sender.send(threadId, r, text)
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
