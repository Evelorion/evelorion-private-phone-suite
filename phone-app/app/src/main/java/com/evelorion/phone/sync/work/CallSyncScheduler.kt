package com.evelorion.phone.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.evelorion.phone.sync.engine.CallSyncEngine
import com.evelorion.phone.bridge.VaultBridge
import java.util.concurrent.TimeUnit

/**
 * 通话记录的自动同步。
 *
 * 通讯录那边踩过的坑：SyncScheduler 写好了但**没有任何地方调用它**，
 * 所以从来没有自动同步过，用户只能手点，还以为是坏了。
 * 这里把触发点写清楚，一共三个：
 *
 *   · 每通电话结束后（CallRecorder 里）—— 刚产生的记录立刻上去
 *   · 用户删除记录后 —— 把删除墓碑推上去
 *   · 用户在设置里手动点同步
 *
 * 没有新记录时不排周期任务，避免无意义的后台唤醒、发热和耗电。
 */
object CallSyncScheduler {

    private const val PERIODIC = "calls-sync-periodic"
    private const val ONESHOT = "calls-sync-now"
    private const val MIGRATION_PREFS = "call_sync_migrations"
    private const val PERIODIC_DISABLED = "periodic_disabled_v1"

    fun disablePeriodic(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PERIODIC_DISABLED, false)) return

        // WorkManager 初始化是异步的。只发 cancel 不等待时，旧任务可能要等下一次
        // enqueue 才真正从 JobScheduler 消失；放后台线程等完成，绝不阻塞 App 启动。
        Thread({
            runCatching {
                WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
                    .result.get(30, TimeUnit.SECONDS)
                prefs.edit().putBoolean(PERIODIC_DISABLED, true).apply()
            }
        }, "disable-call-periodic").apply { isDaemon = true }.start()
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT,
            // REPLACE：连着挂断几通电话时，只跑最后那一次就够了
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }
}

class CallSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // 没配置或保险库锁着不是错误，继续重试只会反复唤醒两个 App。
        if (!VaultBridge.session(applicationContext).usable) return Result.success()

        val report = CallSyncEngine(applicationContext).sync()
        if (report.ok) return Result.success()

        // 网络临时失败最多再试三次；账号/密钥/服务器配置错误等待用户处理。
        val transient = report.error.contains("网络不可用") || report.error.contains("服务器返回错误")
        return if (transient && runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
