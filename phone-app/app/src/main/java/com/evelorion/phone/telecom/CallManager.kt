package com.evelorion.phone.telecom

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
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

    data class AudioRouteOption(
        val id: String,
        val label: String,
        val isSpeaker: Boolean = false,
        val isHeadset: Boolean = false,
    )

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

    /** 呼叫方向。Android 10+ 读系统 direction，旧版按加入时是否振铃判断。 */
    var outgoing by mutableStateOf(false)
        private set

    /** 通话开始的时间戳，用来算时长。0 表示还没接通。 */
    var connectedAt by mutableStateOf(0L)
        private set

    var muted by mutableStateOf(false)
        private set

    var speakerOn by mutableStateOf(false)
        private set

    /** 系统当前实际提供的听筒、扬声器、有线耳机和蓝牙耳机。 */
    var audioRoutes by mutableStateOf<List<AudioRouteOption>>(emptyList())
        private set

    var activeAudioRouteId by mutableStateOf("")
        private set

    val activeAudioRouteLabel: String
        get() = audioRoutes.firstOrNull { it.id == activeAudioRouteId }?.label
            ?: if (speakerOn) "扬声器" else "听筒"

    var onHold by mutableStateOf(false)
        private set

    var canHold by mutableStateOf(false)
        private set

    private var currentEndpoint: CallEndpoint? = null
    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        outgoing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            newCall.details?.callDirection == Call.Details.DIRECTION_OUTGOING
        } else {
            newCall.state != Call.STATE_RINGING
        }
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
            outgoing = false
            connectedAt = 0L
            dtmfTyped = ""
            muted = false
            speakerOn = false
            audioRoutes = emptyList()
            activeAudioRouteId = ""
            onHold = false
            canHold = false
            currentEndpoint = null
            availableEndpoints = emptyList()
            notifyListeners()
        }
    }

    fun onAudioStateChanged(audioState: CallAudioState) {
        muted = audioState.isMuted
        speakerOn = audioState.route == CallAudioState.ROUTE_SPEAKER
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            audioRoutes = legacyAudioRoutes(audioState.supportedRouteMask)
            activeAudioRouteId = legacyRouteId(audioState.route)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun onAvailableEndpointsChanged(endpoints: List<CallEndpoint>) {
        availableEndpoints = endpoints
        audioRoutes = endpoints.map { endpoint ->
            AudioRouteOption(
                id = endpoint.identifier.toString(),
                label = endpointLabel(endpoint),
                isSpeaker = endpoint.endpointType == CallEndpoint.TYPE_SPEAKER,
                isHeadset = endpoint.endpointType == CallEndpoint.TYPE_BLUETOOTH ||
                    endpoint.endpointType == CallEndpoint.TYPE_WIRED_HEADSET,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun onEndpointChanged(endpoint: CallEndpoint) {
        currentEndpoint = endpoint
        speakerOn = endpoint.endpointType == CallEndpoint.TYPE_SPEAKER
        activeAudioRouteId = endpoint.identifier.toString()
    }

    /**
     * 来电显示查到名字后回填。查询是异步的（跨进程 + 解密），所以单独一个入口。
     *
     * 名字**不叫** setCallerName：`var callerName` 已经生成了一个
     * JVM 层面的 setCallerName，同名函数会「Platform declaration clash」编译不过。
     */
    fun updateCallerName(name: String) {
        callerName = name
        notifyListeners()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, newState: Int) = syncFrom(c)

        /**
         * 来电刚加入时，部分基带只先给状态，号码会在随后的 Details 回调里才出现。
         * 以前只监听状态，第一次查号拿到空串后就再也不会查，所以已存联系人也只显示号码。
         */
        override fun onDetailsChanged(c: Call, details: Call.Details) = syncFrom(c)
    }

    private fun syncFrom(c: Call) {
        state = c.state
        onHold = c.state == Call.STATE_HOLDING
        canHold = c.details?.let {
            it.can(Call.Details.CAPABILITY_HOLD) || it.can(Call.Details.CAPABILITY_SUPPORT_HOLD)
        } == true
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

    fun toggleMute(): Boolean {
        val activeService = service ?: return false
        val target = !muted
        activeService.setMuted(target)
        // 乐观更新：真实值会通过 onCallAudioStateChanged 回来纠正。
        // 不这么做的话按钮要等系统回调才变，手感上像是没点中。
        muted = target
        return true
    }

    fun toggleSpeaker(): Boolean {
        val enableSpeaker = !speakerOn
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val preferred = if (enableSpeaker) {
                availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
            } else {
                // 关闭免提时优先回到已经连接的耳机，不能硬切听筒。
                availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
                    ?: availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }
                    ?: availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_EARPIECE }
            }
            return preferred?.let { selectAudioRoute(it.identifier.toString()) } ?: false
        }

        val route = if (enableSpeaker) {
            CallAudioState.ROUTE_SPEAKER
        } else {
            audioRoutes.firstOrNull { it.isHeadset }?.id?.removePrefix(LEGACY_PREFIX)?.toIntOrNull()
                ?: CallAudioState.ROUTE_EARPIECE
        }
        return selectAudioRoute(legacyRouteId(route))
    }

    /** 选择用户在通话界面点中的真实系统音频端点。 */
    fun selectAudioRoute(routeId: String): Boolean {
        val activeService = service ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val endpoint = availableEndpoints.firstOrNull { it.identifier.toString() == routeId }
                ?: return false
            activeService.requestCallEndpointChange(
                endpoint,
                activeService.mainExecutor,
                object : OutcomeReceiver<Void?, CallEndpointException> {
                    override fun onResult(result: Void?) = Unit
                    override fun onError(error: CallEndpointException) {
                        activeAudioRouteId = currentEndpoint?.identifier?.toString().orEmpty()
                        speakerOn = currentEndpoint?.endpointType == CallEndpoint.TYPE_SPEAKER
                    }
                },
            )
            activeAudioRouteId = routeId
            speakerOn = endpoint.endpointType == CallEndpoint.TYPE_SPEAKER
            return true
        }

        val route = routeId.removePrefix(LEGACY_PREFIX).toIntOrNull() ?: return false
        @Suppress("DEPRECATION")
        activeService.setAudioRoute(route)
        activeAudioRouteId = routeId
        speakerOn = route == CallAudioState.ROUTE_SPEAKER
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun endpointLabel(endpoint: CallEndpoint): String = when (endpoint.endpointType) {
        CallEndpoint.TYPE_BLUETOOTH -> endpoint.endpointName.toString().ifBlank { "蓝牙耳机" }
        CallEndpoint.TYPE_WIRED_HEADSET -> "有线耳机"
        CallEndpoint.TYPE_SPEAKER -> "扬声器"
        CallEndpoint.TYPE_EARPIECE -> "听筒"
        CallEndpoint.TYPE_STREAMING -> "其它设备"
        else -> endpoint.endpointName.toString().ifBlank { "其它音频设备" }
    }

    @Suppress("DEPRECATION")
    private fun legacyAudioRoutes(mask: Int): List<AudioRouteOption> = buildList {
        fun addIf(route: Int, label: String, speaker: Boolean = false, headset: Boolean = false) {
            if (mask and route != 0) add(AudioRouteOption(legacyRouteId(route), label, speaker, headset))
        }
        addIf(CallAudioState.ROUTE_EARPIECE, "听筒")
        addIf(CallAudioState.ROUTE_WIRED_HEADSET, "有线耳机", headset = true)
        addIf(CallAudioState.ROUTE_BLUETOOTH, "蓝牙耳机", headset = true)
        addIf(CallAudioState.ROUTE_SPEAKER, "扬声器", speaker = true)
    }

    private fun legacyRouteId(route: Int) = "$LEGACY_PREFIX$route"

    fun toggleHold(): Boolean {
        val currentCall = call ?: return false
        if (!canHold && !onHold) return false
        if (onHold) currentCall.unhold() else currentCall.hold()
        onHold = !onHold
        return true
    }

    fun holdForAdditionalCall(): Boolean {
        if (onHold) return true
        return toggleHold()
    }

    fun sendDtmf(digit: Char): Boolean {
        val currentCall = call ?: return false
        currentCall.playDtmfTone(digit)
        // 立即 stop 会让不少基带根本来不及发送。保留 160 ms，接近实体键盘按键时长。
        mainHandler.postDelayed({
            if (call === currentCall) currentCall.stopDtmfTone()
        }, 160L)
        dtmfTyped += digit
        return true
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

    private const val LEGACY_PREFIX = "legacy:"
}
