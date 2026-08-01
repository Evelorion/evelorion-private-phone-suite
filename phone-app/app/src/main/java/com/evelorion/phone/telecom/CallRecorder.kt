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
 * 所以自己记一份。写入成功后再安排 [SystemCallLogCleanupWorker]，等待系统
 * CallLog 落盘并删掉对应条目，最终只在本 App 中保留记录。
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
        val createdAt = call.details?.creationTimeMillis?.takeIf { it > 0L }
            ?: connectedAt.takeIf { it > 0L }
            ?: now
        // connectedAt 为 0 表示从未接通 —— 那就是一通未接来电
        val answered = connectedAt > 0
        val duration = if (answered) ((now - connectedAt) / 1000).toInt() else 0
        val outgoing = call.details?.callDirection == Call.Details.DIRECTION_OUTGOING
        val kind = when {
            outgoing -> "outgoing"
            answered -> "incoming"
            else -> "missed"
        }
        val recordId = UUID.randomUUID().toString()

        io.execute {
            CallDatabase.get(context).callDao().upsert(
                CallRecordEntity(
                    uuid = recordId,
                    number = number,
                    name = resolvedName,
                    kind = kind,
                    // 系统 CallLog.DATE 记录呼叫开始时间；使用同一个时间基准，
                    // 后续才能精确找到并删除系统里的对应条目。
                    startedAt = createdAt,
                    endedAt = now,
                    durationSeconds = duration,
                    dirty = true,
                )
            )
            SystemCallLogCleanupWorker.schedule(
                context = context,
                recordId = recordId,
                number = number,
                kind = kind,
                startedAt = createdAt,
                endedAt = now,
                durationSeconds = duration,
            )
            // 顺手排一次同步。失败也无所谓，周期任务会补上。
            runCatching { CallSyncScheduler.syncNow(context) }
        }
    }
}
