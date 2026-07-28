package com.evelorion.phone.telecom

import android.content.Context
import android.telecom.Call
import android.util.Log
import com.evelorion.phone.sync.db.CallDatabase
import com.evelorion.phone.sync.db.CallRecordEntity
import com.evelorion.phone.sync.work.CallSyncScheduler
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 通话一结束就自己记一条。
 *
 * ── 为什么不等着扫系统通话记录 ──────────────────────────────
 *
 * 系统写入通话记录是**异步且有延迟**的，挂断后立刻去扫，最后一通往往还没写进去。
 * 结果就是「刚打完的电话在列表里看不到，过一会儿才冒出来」——
 * 用户会以为 App 漏记了。
 *
 * 而且系统那条路要 READ_CALL_LOG 权限；用户拒绝的话，这个 App 自己的
 * 通话记录也跟着没了，那说不过去 —— 这通电话本来就是它接的。
 *
 * 所以自己记一份。系统那份仍然会被 CallSyncEngine 收编，
 * 靠时间水位线去重，两边不会打架。
 */
object CallRecorder {

    private const val TAG = "CallRecorder"
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread({ runCatching { r.run() }.onFailure { Log.w(TAG, "记录通话失败", it) } }, "call-recorder")
    }

    fun record(context: Context, call: Call, connectedAt: Long, resolvedName: String) {
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        if (number.isBlank()) return

        val now = System.currentTimeMillis()
        // connectedAt 为 0 表示从未接通 —— 那就是一通未接来电
        val answered = connectedAt > 0
        val duration = if (answered) ((now - connectedAt) / 1000).toInt() else 0
        val outgoing = call.details?.callDirection == Call.Details.DIRECTION_OUTGOING

        io.execute {
            CallDatabase.get(context).callDao().upsert(
                CallRecordEntity(
                    uuid = UUID.randomUUID().toString(),
                    number = number,
                    name = resolvedName,
                    kind = when {
                        outgoing -> "outgoing"
                        answered -> "incoming"
                        else -> "missed"
                    },
                    // 未接来电没有接通时间，用挂断时间当发生时间
                    startedAt = if (answered) connectedAt else now,
                    durationSeconds = duration,
                    dirty = true,
                )
            )
            // 顺手排一次同步。失败也无所谓，周期任务会补上。
            runCatching { CallSyncScheduler.syncNow(context) }
        }
    }
}
