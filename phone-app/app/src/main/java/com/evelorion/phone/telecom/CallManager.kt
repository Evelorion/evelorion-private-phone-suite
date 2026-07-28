package com.evelorion.phone.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf

/**
 * 当前通话的唯一真相来源。
 *
 * ── 为什么是全局单例 ────────────────────────────────────────
 *
 * 通话状态的拥有者是**系统**，不是某个界面。InCallService 由系统创建和销毁，
 * 时机和 Activity 完全无关：来电可能在 App 没启动时到达，用户也可能在通话中
 * 把界面划走再切回来。把状态挂在某个 Activity 或 ViewModel 上，
 * 一旦它被回收，通话还在继续但界面已经不知道自己该显示什么了。
 *
 * 所以状态放在进程级单例里，Service 往里写，Compose 从里读。
 *
 * ── 为什么用 Compose 的 mutableStateOf 而不是 StateFlow ────
 *
 * 这些值只被 Compose 消费，mutableStateOf 直接触发重组，不需要
 * collectAsState 那一层。Telecom 的回调都在主线程上，不存在线程安全问题。
 */
object CallManager {

    /** Telecom 交给我们的当前通话。null 表示现在没有通话。 */
    var call: Call? = null
        private set

    /** InCallService 实例，用来控制音频路由。Service 销毁时置空。 */
    private var service: InCallService? = null

    var state by mutableStateOf(Call.STATE_DISCONNECTED)
        private set

    /** 对方号码。拿不到（隐藏号码）时是空串。 */
    var number by mutableStateOf("")
        private set

    /** 来电显示查出来的名字。查不到就保持空，界面显示号码。 */
    var callerName by mutableStateOf("")
        private set

    /** 通话开始的时间戳，用来算时长。0 表示还没接通。 */
    var connectedAt by mutableStateOf(0L)
        private set

    var muted by mutableStateOf(false)
        private set

    var speakerOn by mutableStateOf(false)
        private set

    /** 通话中在拨号盘上按出来的号码，显示在界面上。 */
    var dtmfTyped by mutableStateOf("")
        private set

    /** 观察者：状态变化时叫醒界面（比如把来电界面拉起来）。 */
    private val listeners = mutableStateListOf<(Call?) -> Unit>()

    val hasCall: Boolean get() = call != null
    val isRinging: Boolean get() = state == Call.STATE_RINGING
    val isActive: Boolean get() = state == Call.STATE_ACTIVE

    // ------------------------------------------------------------ Service 侧

    fun attachService(inCallService: InCallService?) {
        service = inCallService
    }

    fun onCallAdded(newCall: Call) {
        call = newCall
        newCall.registerCallback(callback)
        syncFrom(newCall)
        notifyListeners()
    }

    fun onCallRemoved(removed: Call) {
        removed.unregisterCallback(callback)
        if (call === removed) {
            call = null
            state = Call.STATE_DISCONNECTED
            number = ""
            callerName = ""
            connectedAt = 0L
            dtmfTyped = ""
            muted = false
            speakerOn = false
            notifyListeners()
        }
    }

    fun onAudioStateChanged(audioState: CallAudioState) {
        muted = audioState.isMuted
        speakerOn = audioState.route == CallAudioState.ROUTE_SPEAKER
    }

    /**
     * 来电显示查到名字后回填。查询是异步的（跨进程 + 解密），所以单独一个入口。
     *
     * 名字**不叫** setCallerName：`var callerName` 已经生成了一个
     * JVM 层面的 setCallerName，同名函数会「Platform declaration clash」编译不过。
     */
    fun updateCallerName(name: String) {
        callerName = name
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, newState: Int) = syncFrom(c)
    }

    private fun syncFrom(c: Call) {
        state = c.state
        number = c.details?.handle?.schemeSpecificPart.orEmpty()
        if (c.state == Call.STATE_ACTIVE && connectedAt == 0L) {
            connectedAt = System.currentTimeMillis()
        }
        notifyListeners()
    }

    // ------------------------------------------------------------ 界面侧动作

    /**
     * 接听。
     *
     * 需要 ANSWER_PHONE_CALLS 权限**并且**本 App 是默认电话应用 ——
     * 两个条件缺一个，这行就是静默无效，按钮点了没反应。
     */
    fun answer() {
        call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /** 拒接。挂断已接通的通话要用 disconnect()，reject() 只对响铃中的有效。 */
    fun reject() {
        call?.reject(false, null)
    }

    fun hangUp() {
        val c = call ?: return
        if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }

    fun toggleMute() {
        val target = !muted
        service?.setMuted(target)
        // 乐观更新：真实值会通过 onCallAudioStateChanged 回来纠正。
        // 不这么做的话按钮要等系统回调才变，手感上像是没点中。
        muted = target
    }

    fun toggleSpeaker() {
        val target = if (speakerOn) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        service?.setAudioRoute(target)
        speakerOn = !speakerOn
    }

    fun sendDtmf(digit: Char) {
        call?.playDtmfTone(digit)
        call?.stopDtmfTone()
        dtmfTyped += digit
    }

    fun addListener(listener: (Call?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Call?) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        // 复制一份再遍历：回调里可能会 removeListener，直接遍历会 ConcurrentModification
        listeners.toList().forEach { it(call) }
    }
}
