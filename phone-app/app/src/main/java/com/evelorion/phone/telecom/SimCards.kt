package com.evelorion.phone.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.lang.reflect.Modifier

/**
 * 一张 SIM 卡在界面上需要知道的全部信息。
 *
 * [slotIndex] 是卡槽序号（0 起），[subscriptionId] 是系统给这张卡的运行时 id。
 * 两者都不能省：双卡机器上用户认的是「卡 1 / 卡 2」，而所有 Telecom/Telephony
 * 接口要的是 subId。
 */
data class SimCard(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
    val number: String,
    val tint: Int,
) {
    /** 卡槽标签。取不到卡槽（部分虚拟卡）时退化成「SIM」。 */
    val slotLabel: String
        get() = if (slotIndex >= 0) "卡 ${slotIndex + 1}" else "SIM"

    /**
     * 完整的卡片标签：「卡 1 · 中国移动」。
     *
     * 系统给的 displayName 常常就是运营商名，此时不再重复拼；
     * 用户自己改过名（「工作」「流量卡」）时优先显示用户的名字。
     */
    val label: String
        get() {
            val extra = displayName.trim().ifBlank { carrierName.trim() }
            if (extra.isBlank() || extra.equals(slotLabel, ignoreCase = true)) return slotLabel
            return "$slotLabel · $extra"
        }
}

/**
 * 双卡识别。
 *
 * ── 为什么不能只读「默认 SIM」 ────────────────────────────────
 *
 * 默认语音卡只决定**拨出**默认用哪张卡；来电路由到哪张卡是网络决定的。
 * 通话中的用户问「这是打到我哪个号的」，只有 Telecom 交下来的
 * PhoneAccountHandle 能回答，默认卡回答不了。
 *
 * ── 识别顺序（从准到糙） ─────────────────────────────────────
 *
 *   1. PhoneAccount 的 subscriptionAddress（tel: 号码）与订阅号码比对；
 *   2. PhoneAccountHandle.id 与 ICCID 比对，或直接当 subId 解析；
 *   3. 逐张卡查 TelephonyManager.getCallState(subId)：通话中的那张不是 IDLE；
 *   4. 单卡直接用；多卡都查不到时退回默认语音卡。
 *
 * 每一步都吞异常：通话界面绝不能因为读不到 SIM 而崩。
 */
object SimCards {

    private const val PREFS = "sim_choice"
    private const val KEY_SUB = "preferred_subscription_id"

    private var cardsAt = 0L
    private var cachedCards: List<SimCard> = emptyList()

    /**
     * 读订阅列表需要 READ_PHONE_STATE；没有权限时系统直接抛 SecurityException。
     *
     * 通话状态每变一次都会问一遍，而订阅列表一秒内不会变，所以缓存 3 秒：
     * 既避免频繁的 binder 调用，又保证插拔卡后很快能看到新状态。
     */
    fun available(context: Context): List<SimCard> {
        val now = SystemClock.elapsedRealtime()
        if (now - cardsAt < 3_000L) return cachedCards
        if (!hasPhoneState(context)) return emptyList()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val infos = runCatching { manager.activeSubscriptionInfoList }.getOrNull() ?: return emptyList()
        val cards = infos
            .sortedWith(compareBy({ it.simSlotIndex }, { it.subscriptionId }))
            .map { it.toSimCard() }
        cachedCards = cards
        cardsAt = now
        return cards
    }

    /** 用户上次选的卡；没选过就用系统默认语音卡。 */
    fun selected(context: Context): SimCard? {
        val cards = available(context)
        if (cards.isEmpty()) return null
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SUB, -1)
        return cards.firstOrNull { it.subscriptionId == stored }
            ?: defaultVoice(context, cards)
            ?: cards.first()
    }

    /** 记住拨号盘上选的卡，下次打开还是这张。 */
    fun remember(context: Context, subscriptionId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SUB, subscriptionId)
            .apply()
    }

    /** 当前通话到底在哪张卡上。拿不到返回 null，界面就不显示标签。 */
    fun forCall(context: Context, call: Call?): SimCard? = forDetails(context, call?.details)

    /** 来电筛选服务只能拿到 Call.Details，用同一个入口解析。 */
    fun forDetails(context: Context, details: Call.Details?): SimCard? {
        val handle = details?.accountHandle
        val cards = available(context)

        if (handle != null && cards.size > 1) {
            fromAccount(context, handle)?.let { return it }
        }

        // accountHandle 缺失或不认识：通话中的那张卡 callState 不是 IDLE。
        val byState = cards.firstOrNull {
            callStateOf(context, it.subscriptionId) != TelephonyManager.CALL_STATE_IDLE
        }
        if (byState != null) return byState

        return when (cards.size) {
            0 -> null
            1 -> cards.first()
            else -> defaultVoice(context, cards)
        }
    }

    /** 来电筛选服务里没有 Call，只有 Call.Details。 */
    fun labelForDetails(context: Context, details: Call.Details?): String =
        runCatching { forDetails(context, details)?.label.orEmpty() }.getOrDefault("")

    /** 拨号时把「用户选的卡」翻译成 Telecom 认的账户；找不到返回 null（走系统默认）。 */
    fun phoneAccountFor(context: Context, subscriptionId: Int): PhoneAccountHandle? {
        if (subscriptionId < 0) return null
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        val handles = runCatching { telecom.callCapablePhoneAccounts }.getOrNull().orEmpty()
        val cards = available(context)

        // 先按账户自身的号码/ICCID 认；认不出来再按卡槽顺序对齐。
        handles.firstOrNull { handle ->
            val id = subIdFromAccount(context, handle, cards)
            id == subscriptionId
        }?.let { return it }

        val index = cards.indexOfFirst { it.subscriptionId == subscriptionId }
        return if (index >= 0 && index < handles.size && cards.size == handles.size) handles[index] else null
    }

    fun hasPhoneState(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 从 PhoneAccountHandle 认卡。
     *
     * 先看 PhoneAccount 的 subscriptionAddress（tel: 号码），再比对 ICCID，
     * 最后把 handle.id 当 subId 解析。全部失败返回 null。
     */
    private fun fromAccount(context: Context, handle: PhoneAccountHandle): SimCard? {
        val cards = available(context)
        val subId = subIdFromAccount(context, handle, cards)
        return cards.firstOrNull { it.subscriptionId == subId }
    }

    private fun subIdFromAccount(context: Context, handle: PhoneAccountHandle, cards: List<SimCard>): Int {
        // 1. subscriptionAddress 里的号码
        val address = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecom = context.getSystemService(TelecomManager::class.java)
                telecom?.getPhoneAccount(handle)?.subscriptionAddress?.schemeSpecificPart
            } else {
                null
            }
        }.getOrNull().orEmpty()
        if (address.isNotBlank()) {
            val digits = digitsOf(address)
            if (digits.isNotBlank()) {
                cards.firstOrNull { digitsOf(it.number) == digits }?.let { return it.subscriptionId }
            }
        }

        // 2. handle.id 与 ICCID 比对
        val infos = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()
        infos.firstOrNull { info ->
            runCatching { info.iccId }.getOrNull()?.equals(handle.id, ignoreCase = true) == true
        }?.let { return it.subscriptionId }

        // 3. 公开 API getSubscriptionId(PhoneAccountHandle) 在部分 ROM 上存在，
        //    但它不是所有版本都有，因此用反射，失败就算了。
        runCatching {
            val method = SubscriptionManager::class.java
                .getMethod("getSubscriptionId", PhoneAccountHandle::class.java)
            val manager = context.getSystemService(SubscriptionManager::class.java)
            val receiver = if (Modifier.isStatic(method.modifiers)) null else manager
            (method.invoke(receiver, handle) as? Int)?.takeIf { it > 0 }?.let { return it }
        }

        // 4. handle.id 本身就是 subId
        return handle.id?.toIntOrNull() ?: -1
    }

    private fun digitsOf(raw: String): String = raw.filter(Char::isDigit).takeLast(11)

    private fun defaultVoice(context: Context, cards: List<SimCard>): SimCard? {
        @Suppress("DEPRECATION")
        val subId = runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }.getOrDefault(-1)
        return cards.firstOrNull { it.subscriptionId == subId }
    }

    @Suppress("DEPRECATION")
    private fun callStateOf(context: Context, subscriptionId: Int): Int = runCatching {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return -1
        telephony.createForSubscriptionId(subscriptionId).callState
    }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

    private fun SubscriptionInfo.toSimCard(): SimCard {
        val tint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { iconTint }.getOrDefault(0)
        } else {
            0
        }
        return SimCard(
            subscriptionId = subscriptionId,
            slotIndex = simSlotIndex,
            displayName = runCatching { displayName?.toString().orEmpty() }.getOrDefault(""),
            carrierName = runCatching { carrierName?.toString().orEmpty() }.getOrDefault(""),
            number = runCatching { number.orEmpty() }.getOrDefault(""),
            tint = tint,
        )
    }
}
