package com.evelorion.phone.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.telecom.Call
import android.util.Log
import com.evelorion.phone.R

/**
 * 通话存在多久，通知就存在多久。
 *
 * 这条通知不依赖 [CallActivity] 的生命周期：用户按返回、回桌面或切到游戏后，
 * 仍然可以从通知栏回到通话或直接挂断。来电时通知与全屏来电页同时创建，
 * 而不是等 Activity 退出之后才补出来。
 */
internal class CallNotificationController(
    private val service: PhoneInCallService,
) {
    private val notificationManager =
        service.getSystemService(NotificationManager::class.java)
    private var foregroundStarted = false

    init {
        createChannel()
    }

    fun update(call: Call?) {
        if (call == null || call.state == Call.STATE_DISCONNECTED) {
            stop()
            return
        }

        val notification = buildNotification(call)
        if (!foregroundStarted) {
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    service.startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                    )
                } else {
                    service.startForeground(NOTIFICATION_ID, notification)
                }
            }.onFailure {
                // 极少数定制系统会错误拦截 InCallService 升前台；至少保留普通
                // CallStyle 通知，让用户仍有挂断入口。
                Log.e(TAG, "无法启动电话前台通知", it)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }.isSuccess
            foregroundStarted = started
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun stop() {
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
        }
        foregroundStarted = false
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(call: Call): Notification {
        val displayName = CallManager.callerName.ifBlank {
            CallManager.number.ifBlank { service.getString(R.string.call_notification_unknown) }
        }
        val title = when (call.state) {
            Call.STATE_RINGING -> R.string.call_notification_incoming
            Call.STATE_DIALING -> R.string.call_notification_dialing
            Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT ->
                R.string.call_notification_connecting
            Call.STATE_ACTIVE -> R.string.call_notification_active
            Call.STATE_HOLDING -> R.string.call_notification_holding
            else -> R.string.call_notification_connecting
        }

        val contentIntent = PendingIntent.getActivity(
            service,
            REQUEST_OPEN,
            Intent(service, CallActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUpIntent = actionIntent(ACTION_HANG_UP, REQUEST_HANG_UP)
        val answerIntent = actionIntent(ACTION_ANSWER, REQUEST_ANSWER)
        val declineIntent = actionIntent(ACTION_DECLINE, REQUEST_DECLINE)

        val builder = Notification.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(service.getString(title))
            .setContentText(displayName)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 通话时长已在通话页显示。通知栏秒表会让 SystemUI 即使在游戏前台
            // 也每秒刷新；保留静态通知和挂断入口即可。
            .setShowWhen(false)
            .setUsesChronometer(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder()
                .setName(displayName)
                .setImportant(true)
                .build()
            builder.setStyle(
                if (call.state == Call.STATE_RINGING) {
                    Notification.CallStyle.forIncomingCall(person, declineIntent, answerIntent)
                } else {
                    Notification.CallStyle.forOngoingCall(person, hangUpIntent)
                },
            )
        } else if (call.state == Call.STATE_RINGING) {
            builder
                .addAction(
                    Notification.Action.Builder(
                        R.drawable.ic_stat_call,
                        service.getString(R.string.call_notification_decline),
                        declineIntent,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        R.drawable.ic_stat_call,
                        service.getString(R.string.call_notification_answer),
                        answerIntent,
                    ).build(),
                )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_call,
                    service.getString(R.string.call_notification_hang_up),
                    hangUpIntent,
                ).build(),
            )
        }

        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            service,
            requestCode,
            Intent(service, CallNotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.call_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = service.getString(R.string.call_notification_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CallNotification"
        private const val CHANNEL_ID = "phone_calls_v1"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_ANSWER = "com.evelorion.phone.action.ANSWER"
        private const val ACTION_DECLINE = "com.evelorion.phone.action.DECLINE"
        private const val ACTION_HANG_UP = "com.evelorion.phone.action.HANG_UP"
        private const val REQUEST_OPEN = 4101
        private const val REQUEST_ANSWER = 4102
        private const val REQUEST_DECLINE = 4103
        private const val REQUEST_HANG_UP = 4104

        internal fun isAnswer(action: String?) = action == ACTION_ANSWER
        internal fun isDecline(action: String?) = action == ACTION_DECLINE
        internal fun isHangUp(action: String?) = action == ACTION_HANG_UP
    }
}

/** 通知按钮的进程内桥接；exported=false，外部 App 无法直接调用。 */
class CallNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            CallNotificationController.isAnswer(intent.action) -> CallManager.answer()
            CallNotificationController.isDecline(intent.action) -> CallManager.reject()
            CallNotificationController.isHangUp(intent.action) -> CallManager.hangUp()
        }
    }
}
