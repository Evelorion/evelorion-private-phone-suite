package com.evelorion.phone.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.evelorion.phone.sync.engine.CallSyncEngine
import java.util.concurrent.TimeUnit

/**
 * 通话记录的自动同步。
 *
 * 通讯录那边踩过的坑：SyncScheduler 写好了但**没有任何地方调用它**，
 * 所以从来没有自动同步过，用户只能手点，还以为是坏了。
 * 这里把触发点写清楚，一共两个：
 *
 *   · 每通电话结束后（CallRecorder 里）—— 刚产生的记录立刻上去
 *   · 每 6 小时一次的周期任务 —— 兜底，捡起前面失败的
 *
 * 周期不设更短：通话记录不是高频数据，而后台唤醒是要耗电的。
 */
object CallSyncScheduler {

    private const val PERIODIC = "calls-sync-periodic"
    private const val ONESHOT = "calls-sync-now"

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CallSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT,
            // REPLACE：连着挂断几通电话时，只跑最后那一次就够了
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }
}

class CallSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val report = CallSyncEngine(applicationContext).sync()
        // 失败就重试。保险库锁着这种情况重试也没用，但 WorkManager 的退避
        // 会越等越久，不至于空转耗电。
        return if (report.ok) Result.success() else Result.retry()
    }
}
