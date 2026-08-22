package com.evelorion.phone.telecom

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.RequiresApi
import com.evelorion.phone.MainActivity

/**
 * 系统把通话交给我们的入口。
 *
 * 只有本 App 被选为**默认电话应用**之后，系统才会绑定这个 Service。
 * 在那之前它一次都不会被调用 —— 所以「来电界面不出来」的第一排查点
 * 永远是「是不是还没设成默认电话应用」，而不是代码。
 *
 * 职责刻意压到最小：把 Telecom 的回调翻译成 [CallManager] 里的状态，
 * 再把界面拉起来。任何业务逻辑（来电显示查号、写通话记录）都不放在这里，
 * 因为这个 Service 的生命周期由系统掌握，随时可能被销毁重建。
 */
class PhoneInCallService : InCallService() {

    private lateinit var callNotification: CallNotificationController
    private var callerLookupNumber = ""
    private val notificationListener: (Call?) -> Unit = { currentCall ->
        callNotification.update(currentCall)
        if (currentCall == null) {
            callerLookupNumber = ""
        } else {
            resolveCallerNameWhenNumberIsReady()
        }
    }

    override fun onCreate() {
        super.onCreate()
        callNotification = CallNotificationController(this)
        CallManager.addListener(notificationListener)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "通话进来了：state=${call.state}")
        CallManager.attachService(this)
        CallManager.onCallAdded(call)
        MainActivity.finishForActiveCall()
        showCallUi()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (CallAudioRecorder.isRecording) CallAudioRecorder.stop()
        // 先记录再清状态 —— 清完就拿不到号码和接通时间了
        CallRecorder.record(
            applicationContext,
            call,
            CallManager.connectedAt,
            CallManager.callerName,
            CallManager.outgoing,
        )
        CallManager.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudioStateChanged(audioState)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        CallManager.onAvailableEndpointsChanged(availableEndpoints)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        CallManager.onEndpointChanged(callEndpoint)
    }

    override fun onDestroy() {
        if (CallAudioRecorder.isRecording) CallAudioRecorder.stop()
        CallManager.removeListener(notificationListener)
        callNotification.stop()
        CallManager.attachService(null)
        super.onDestroy()
    }

    /**
     * Telecom 不保证 onCallAdded 时号码已经写进 Call.Details。
     * 每次状态或详情变化都经过监听器，只在号码真正出现/改变时查一次。
     */
    private fun resolveCallerNameWhenNumberIsReady() {
        val number = CallManager.number.trim()
        if (number.isBlank() || number == callerLookupNumber) return

        val numberChanged = callerLookupNumber.isNotBlank()
        callerLookupNumber = number
        // 同一通话的号码若被系统修正，不能继续显示旧号码查到的名字。
        if (numberChanged) CallManager.updateCallerName("")

        // 内存命中会在 showCallUi() 之前完成，因此第一帧直接是联系人姓名。
        // 后台查询仍会运行一次，用最新联系人覆盖旧缓存，但不会让界面退回号码。
        CallerIdResolver.resolveCached(applicationContext, number)
        CallerIdResolver.resolveAsync(applicationContext, number)
    }

    /**
     * 把通话界面拉到前台。
     *
     * FLAG_ACTIVITY_NEW_TASK 是必须的（从 Service 启动 Activity）。
     * CallActivity 自己带 showWhenLocked / turnScreenOn，
     * 所以锁屏状态下也能直接亮屏显示来电。
     */
    private fun showCallUi() {
        runCatching {
            startActivity(
                Intent(this, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { Log.e(TAG, "拉起通话界面失败", it) }
    }

    private companion object {
        const val TAG = "PhoneInCallService"
    }
}
