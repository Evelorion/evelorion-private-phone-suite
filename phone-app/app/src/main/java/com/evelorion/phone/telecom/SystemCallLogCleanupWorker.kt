package com.evelorion.phone.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog as AndroidCallLog
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 通话结束后删除 Android 系统保存的那一份，只保留本 App 的记录。
 *
 * Telecom 把通话交给 InCallService 后，系统 CallLog 往往还要过几秒才落盘，
 * 所以不能在 onCallRemoved 里立刻 delete。任务会延迟执行并有限重试。
 */
class SystemCallLogCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "没有 WRITE_CALL_LOG 权限，无法清理系统通话记录")
            return Result.success()
        }

        val target = SystemCallLogTarget(
            number = inputData.getString(KEY_NUMBER).orEmpty(),
            kind = inputData.getString(KEY_KIND).orEmpty(),
            startedAt = inputData.getLong(KEY_STARTED_AT, 0L),
            endedAt = inputData.getLong(KEY_ENDED_AT, 0L),
            durationSeconds = inputData.getInt(KEY_DURATION, 0),
        )
        if (target.number.isBlank() || target.startedAt <= 0L || target.endedAt <= 0L) {
            return Result.failure()
        }

        return runCatching {
            val candidates = queryCandidates(target)
            val match = SystemCallLogMatcher.bestMatch(target, candidates)
            if (match == null) {
                if (runAttemptCount < MAX_RETRIES) Result.retry() else {
                    Log.w(TAG, "重试后仍未找到对应的系统通话记录")
                    Result.success()
                }
            } else {
                val deleted = applicationContext.contentResolver.delete(
                    AndroidCallLog.Calls.CONTENT_URI,
                    "${AndroidCallLog.Calls._ID} = ?",
                    arrayOf(match.id.toString()),
                )
                if (deleted > 0) {
                    Log.i(TAG, "已删除系统通话记录 id=${match.id}")
                    Result.success()
                } else if (runAttemptCount < MAX_RETRIES) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        }.getOrElse {
            Log.w(TAG, "清理系统通话记录失败", it)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private fun queryCandidates(target: SystemCallLogTarget): List<SystemCallLogCandidate> {
        val from = target.startedAt - MATCH_MARGIN_MS
        val to = target.endedAt + MATCH_MARGIN_MS
        val result = ArrayList<SystemCallLogCandidate>()
        applicationContext.contentResolver.query(
            AndroidCallLog.Calls.CONTENT_URI,
            arrayOf(
                AndroidCallLog.Calls._ID,
                AndroidCallLog.Calls.NUMBER,
                AndroidCallLog.Calls.TYPE,
                AndroidCallLog.Calls.DATE,
                AndroidCallLog.Calls.DURATION,
            ),
            "${AndroidCallLog.Calls.DATE} BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()),
            AndroidCallLog.Calls.DATE + " DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += SystemCallLogCandidate(
                    id = cursor.getLong(0),
                    number = cursor.getString(1).orEmpty(),
                    type = cursor.getInt(2),
                    date = cursor.getLong(3),
                    durationSeconds = cursor.getInt(4),
                )
            }
        }
        return result
    }

    companion object {
        private const val TAG = "SystemCallLogCleanup"
        private const val KEY_NUMBER = "number"
        private const val KEY_KIND = "kind"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_ENDED_AT = "ended_at"
        private const val KEY_DURATION = "duration"
        private const val MATCH_MARGIN_MS = 60_000L
        private const val MAX_RETRIES = 3

        fun schedule(
            context: Context,
            recordId: String,
            number: String,
            kind: String,
            startedAt: Long,
            endedAt: Long,
            durationSeconds: Int,
        ) {
            val input = Data.Builder()
                .putString(KEY_NUMBER, number)
                .putString(KEY_KIND, kind)
                .putLong(KEY_STARTED_AT, startedAt)
                .putLong(KEY_ENDED_AT, endedAt)
                .putInt(KEY_DURATION, durationSeconds)
                .build()
            val request = OneTimeWorkRequestBuilder<SystemCallLogCleanupWorker>()
                .setInputData(input)
                .setInitialDelay(2, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "system-call-log-cleanup-$recordId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

internal data class SystemCallLogTarget(
    val number: String,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Int,
)

internal data class SystemCallLogCandidate(
    val id: Long,
    val number: String,
    val type: Int,
    val date: Long,
    val durationSeconds: Int,
)

internal object SystemCallLogMatcher {
    fun bestMatch(
        target: SystemCallLogTarget,
        candidates: List<SystemCallLogCandidate>,
    ): SystemCallLogCandidate? = candidates
        .asSequence()
        .filter { normalize(it.number) == normalize(target.number) }
        .filter { typeMatches(target.kind, it.type) }
        .filter { abs(it.durationSeconds - target.durationSeconds) <= DURATION_TOLERANCE_SECONDS }
        .minByOrNull {
            abs(it.date - target.startedAt) +
                abs(it.durationSeconds - target.durationSeconds) * 1_000L
        }

    private fun typeMatches(kind: String, type: Int): Boolean = when (kind) {
        "outgoing" -> type == AndroidCallLog.Calls.OUTGOING_TYPE
        "incoming" -> type == AndroidCallLog.Calls.INCOMING_TYPE
        "missed" -> type != AndroidCallLog.Calls.OUTGOING_TYPE &&
            type != AndroidCallLog.Calls.INCOMING_TYPE
        else -> false
    }

    private fun normalize(raw: String): String =
        raw.filter(Char::isDigit).takeLast(8)

    private const val DURATION_TOLERANCE_SECONDS = 5
}
