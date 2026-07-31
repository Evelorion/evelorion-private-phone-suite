package com.evelorion.phone.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.evelorion.phone.bridge.ContactsBridge
import com.evelorion.phone.bridge.VaultBridge
import com.evelorion.phone.sync.db.CallDatabase
import com.evelorion.phone.sync.work.CallSyncScheduler
import com.evelorion.phone.telecom.DialerRole
import com.evelorion.phone.telecom.CallScreeningRole
import com.evelorion.phone.ui.screens.SettingsStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 界面的数据来源。
 *
 * ── 它替换的是 SampleData ────────────────────────────────────
 *
 * 刻意保持**和 SampleData 完全一样的 API 形状**（people / person(id) /
 * calls / history / familySubtitles / favoriteSubtitles），
 * 这样九个界面文件只需要改一行 import，其余代码一个字都不用动。
 *
 * 这不是偷懒。那些界面是设计稿本身，改动越多、和设计走样的风险越大；
 * 而「把假数据换成真数据」这件事本来也不该要求界面重写。
 *
 * ── 联系人从哪来 ────────────────────────────────────────────
 *
 * 全部来自通讯录 App 的加密库（跨进程查询）。这边一条都不存 ——
 * 存了就意味着要么再要一次主口令，要么把 DEK 拿过来，两条都不可接受。
 */
object PhoneData {

    private const val TAG = "PhoneData"

    var people by mutableStateOf<List<Person>>(emptyList())
        private set

    var calls by mutableStateOf<List<CallLog>>(emptyList())
        private set

    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /** 通讯录 App 没装 / 没解锁时为 true，界面据此提示用户，而不是显示成"没有联系人"。 */
    var contactsUnavailable by mutableStateOf(false)
        private set

    var contactsAccessState by mutableStateOf(ContactsBridge.AccessState.PROVIDER_ERROR)
        private set

    fun person(id: String?): Person? = people.firstOrNull { it.id == id }

    /**
     * 家人分组的副标题。设计稿里是「妈妈」「爸爸」这类称呼，
     * 真实数据里没有这个字段，退回显示号码。
     */
    /**
     * 家人区的副标题。设计稿里是「妈妈」「爸爸」这类称呼，
     * 而真实联系人没有这个字段 —— 编一个出来是错的，退回显示号码。
     * 界面里写的是 `familySubtitles[p.id] ?: p.number`，所以给空 map 就会走号码。
     */
    val familySubtitles: Map<String, String> get() = emptyMap()
    val favoriteSubtitles: Map<String, String> get() = emptyMap()

    /** 认哪些分组名算「家人」。中英文都收，用户不该被迫用某个特定写法。 */
    private val FAMILY_GROUP_NAMES = setOf("家人", "家庭", "Family")

    /** **耗时，必须在后台线程调用。** */
    fun load(context: Context) {
        loadContacts(context)
        loadCalls(context)
    }

    private fun loadContacts(context: Context) {
        val fromVault = ContactsBridge.loadAll(context)
        contactsAccessState = ContactsBridge.accessState
        contactsUnavailable = fromVault.isEmpty() &&
            contactsAccessState != ContactsBridge.AccessState.AVAILABLE
        people = fromVault.map { c ->
            val letter = Pinyin.initialOf(c.name)
            Person(
                id = c.id.toString(),
                name = c.name,
                number = c.number,
                letter = letter.toString(),
                initial = initialOf(c.name),
                bg = AvatarBg[colorIndex(c.name)],
                fg = AvatarFg[colorIndex(c.name)],
                city = "",
                favorite = c.starred,
                // 「家人」是通讯录里一个**真实存在的分组**，不是这边编出来的。
                // 用户在通讯录里建一个叫「家人」（或「家庭」）的分组并把人放进去，
                // 这一块才会出现。没有这个分组时常用页不显示家人区，
                // 而不是摆一个空盒子让人以为坏了。
                family = c.groups.any { it in FAMILY_GROUP_NAMES },
            )
        }.sortedWith(compareBy({ it.letter == "#" }, { it.letter }, { it.name }))
    }

    /**
     * 通话记录。
     *
     * 读的是**本 App 自己的库**，不是系统通话记录。
     * 系统那份由 CallSyncEngine 负责收编（按时间水位线去重），
     * 界面这一层只认一个来源 —— 两个来源混着读，去重逻辑会散落到界面里。
     */
    private fun loadCalls(context: Context) {
        val byNumber = people.associateBy { normalize(it.number) }
        calls = runCatching {
            CallDatabase.get(context).callDao().recent().map { r ->
                val match = byNumber[normalize(r.number)]
                // 显示名优先用**当前**联系人，其次用记录当时的快照，最后退回号码。
                // 反过来（快照优先）的话，联系人改了名，历史记录还显示旧名字。
                val display = match?.name?.takeIf { it.isNotBlank() }
                    ?: r.name.takeIf { it.isNotBlank() }
                    ?: r.number.ifBlank { "未知号码" }
                CallLog(
                    id = r.uuid,
                    personId = match?.id,
                    group = groupOf(r.startedAt),
                    kind = when (r.kind) {
                        "outgoing" -> CallKind.Outgoing
                        "missed" -> CallKind.Missed
                        else -> CallKind.Incoming
                    },
                    time = TIME.format(Date(r.startedAt)),
                    duration = if (r.durationSeconds > 0) formatDuration(r.durationSeconds.toLong()) else null,
                    displayName = display,
                    displayNumber = r.number,
                    displayInitial = initialOf(display),
                    displayBg = AvatarBg[colorIndex(display)],
                    displayFg = AvatarFg[colorIndex(display)],
                    displayCity = "",
                )
            }
        }.onFailure { Log.w(TAG, "读取通话记录失败", it) }.getOrDefault(emptyList())
    }

    /**
     * 设置页的真实状态。**耗时，后台线程调用。**
     *
     * 三个来源分别是：Telecom（是不是默认拨号器）、通讯录（保险库状态）、
     * 本地库（通话记录条数和待上传条数）。
     */
    fun settingsStatus(context: Context): SettingsStatus {
        val vault = VaultBridge.session(context)
        val dao = runCatching { CallDatabase.get(context).callDao() }.getOrNull()
        return SettingsStatus(
            isDefaultDialer = DialerRole.isDefault(context),
            isCallScreeningEnabled = CallScreeningRole.isHeld(context),
            vaultUsable = vault.usable,
            vaultMessage = vault.message,
            callCount = runCatching { dao?.recent(9999)?.size ?: 0 }.getOrDefault(0),
            pendingCount = runCatching { dao?.countPending() ?: 0 }.getOrDefault(0),
            familyCount = people.count { it.family },
            blockedCount = runCatching { BlockedNumberStore.all(context).size }.getOrDefault(0),
        )
    }

    /**
     * 某个人的通话历史。详情页用。
     *
     * 同时按 personId 和号码筛：陌生号码没有 personId，只能靠号码认；
     * 而同一个人可能有多个号码，那时 personId 才管用。只用一个条件会漏。
     */
    fun historyFor(personId: String?, number: String = "") {
        val target = normalize(number)
        history = calls.filter {
            (personId != null && it.personId == personId) ||
                (target.isNotEmpty() && normalize(it.displayNumber.orEmpty()) == target)
        }.map {
            HistoryEntry(
                kind = it.kind.label,
                when_ = it.time,
                duration = it.duration ?: "未接通",
                missed = it.kind == CallKind.Missed,
            )
        }
    }

    /**
     * 删除一条通话记录。
     *
     * **打墓碑标记，不直接删行。** 这条记录可能已经同步到服务器和其它设备上，
     * 只删本地的话下次同步又会被拉回来 —— 用户会觉得「删不掉」。
     * 墓碑推上去之后所有设备才会真正消失。
     *
     * 这也是本 App 里**唯一**产生删除的地方。同步引擎从设计上不会因为
     * 「扫描时找不到」就推墓碑 —— 通讯录那边正是那样丢过一次数据。
     */
    fun deleteCall(context: Context, uuid: String) {
        io.execute {
            runCatching {
                val dao = CallDatabase.get(context).callDao()
                val record = dao.byUuid(uuid) ?: return@runCatching
                if (record.rev == 0) {
                    // 从没同步上去过，服务器上没有对应记录，直接删干净即可
                    dao.deleteByUuid(uuid)
                } else {
                    dao.upsert(record.copy(deletedLocally = true, dirty = true))
                }
                loadCalls(context)
                CallSyncScheduler.syncNow(context)
            }.onFailure { Log.w(TAG, "删除通话记录失败", it) }
        }
    }

    /**
     * 删除、刷新这类零星写操作用的线程池。
     *
     * 这里的任务内部都用 runCatching 收口，避免工作线程异常杀掉进程。
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phonedata").apply { isDaemon = true }
    }

    // ------------------------------------------------------------ 工具

    private val TIME = SimpleDateFormat("HH:mm", Locale.CHINA)

    /** 按记录的真实日期分组；更早的记录显示具体日期，不塞进含糊的「更早」。 */
    private fun groupOf(timestamp: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "今天"
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        if (yesterday) return "昨天"
        return if (Calendar.getInstance().get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            MONTH_DAY.format(Date(timestamp))
        } else {
            FULL_DATE.format(Date(timestamp))
        }
    }

    private fun formatDuration(seconds: Long): String =
        if (seconds >= 3600) "%d小时%d分".format(seconds / 3600, seconds % 3600 / 60)
        else if (seconds >= 60) "%d分%d秒".format(seconds / 60, seconds % 60)
        else "%d秒".format(seconds)

    private val MONTH_DAY = SimpleDateFormat("M月d日", Locale.CHINA)
    private val FULL_DATE = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

    /** 号码归一化。只留数字，取后 8 位 —— 区号和 +86 前缀在两边可能写法不同。 */
    private fun normalize(raw: String): String =
        raw.filter { it.isDigit() }.takeLast(8)

    private fun initialOf(text: String): String {
        val first = text.firstOrNull() ?: return "?"
        return first.uppercase()
    }

    private fun colorIndex(text: String): Int {
        var h = 0
        for (c in text) h = h * 31 + c.code
        return ((h % AvatarBg.size) + AvatarBg.size) % AvatarBg.size
    }

    /** 和通讯录里的头像用同一组颜色 —— 不一致的话同一个人在两个 App 里颜色不同。 */
    private val AvatarBg = listOf(
        Color(0xFFEADDFF), Color(0xFFD9E2FF), Color(0xFFFFD8E4), Color(0xFFD7E3FF),
        Color(0xFFFFDDB3), Color(0xFFE8DEF8), Color(0xFFF2DDE1), Color(0xFFE0E0EC),
    )
    private val AvatarFg = listOf(
        Color(0xFF21005D), Color(0xFF102A56), Color(0xFF31111D), Color(0xFF001B3D),
        Color(0xFF2B1700), Color(0xFF25164A), Color(0xFF31101B), Color(0xFF1A1B22),
    )
}
