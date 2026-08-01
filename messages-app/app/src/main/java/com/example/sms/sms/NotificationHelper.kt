package com.example.sms.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.sms.MainActivity
import com.example.sms.R
import com.example.sms.util.CodeExtractor

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val KEY_REPLY = "key_reply"
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_messages_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun showNewMessage(threadId: Long, title: String, body: String, address: String) {
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val notifyId = threadId.toInt().let { if (it == 0) 1 else it }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
        }
        val contentPi = PendingIntent.getActivity(
            context, notifyId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .addAction(replyAction(notifyId, threadId, address))
            .addAction(markReadAction(notifyId, threadId))

        // 短信里带验证码/取件码时，通知上直接给一个「复制」按钮
        CodeExtractor.extract(body)?.let { extracted ->
            builder.addAction(copyCodeAction(notifyId, extracted.code, extracted.label))
        }

        runCatching { manager.notify(notifyId, builder.build()) }
    }

    fun cancel(threadId: Long) = manager.cancel(threadId.toInt())

    private fun replyAction(
        notifyId: Int,
        threadId: Long,
        address: String,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("回复").build()
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            putExtra(NotificationActionReceiver.EXTRA_ADDRESS, address)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFY_ID, notifyId)
        }
        val pi = PendingIntent.getBroadcast(
            context, notifyId * 10 + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "回复", pi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun markReadAction(notifyId: Int, threadId: Long): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFY_ID, notifyId)
        }
        val pi = PendingIntent.getBroadcast(
            context, notifyId * 10 + 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "标记已读", pi).build()
    }

    private fun copyCodeAction(notifyId: Int, code: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_CODE
            putExtra(NotificationActionReceiver.EXTRA_CODE, code)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFY_ID, notifyId)
        }
        val pi = PendingIntent.getBroadcast(
            context, notifyId * 10 + 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_message, "复制" + label + " " + code, pi,
        ).build()
    }
}
