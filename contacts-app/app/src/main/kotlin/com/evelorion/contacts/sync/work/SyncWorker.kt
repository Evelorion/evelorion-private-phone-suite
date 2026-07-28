package com.evelorion.contacts.sync.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.engine.SyncEngine
import java.util.concurrent.TimeUnit

/**
 * 后台同步。
 *
 * 用 WorkManager 而不是自己起 Service：厂商 ROM 对后台进程的杀伤力很强，
 * WorkManager 是唯一能在国产 ROM 上还算稳的调度方式，而且它自带指数退避
 * 和网络约束，不用自己写重试。
 *
 * 保险库锁定时直接返回成功而不是失败 —— 锁定不是错误，重试也没用，
 * 标成失败只会让 WorkManager 一直退避重排。
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val KEY_TRIGGER = "trigger"

    }

    override fun doWork(): Result {
        val vault = VaultManager.get(applicationContext)
        if (!vault.isConfigured) return Result.success()

        if (!vault.isUnlocked) {
            // 试一次静默解锁。要求屏幕锁的情况下这里会失败，那就等用户下次开 App。
            val unlocked = runCatching { vault.unlockFromCache() }.getOrDefault(false)
            if (!unlocked) {
                Log.i(TAG, "保险库锁定，跳过这次后台同步")
                return Result.success()
            }
        }

        val report = SyncEngine(applicationContext).sync()
        return when {
            report.ok -> {
                Log.i(TAG, "同步完成：拉取 ${report.pulled}，推送 ${report.pushed}，删除 ${report.deleted}")
                // 通知界面刷新。不通知的话，后台拉下来的联系人要等用户
                // 手动切页面才看得见，他会以为同步没生效。
                SyncEvents.notifyFinished(report.pulled, report.pushed)
                Result.success(
                    Data.Builder()
                        .putInt("pulled", report.pulled)
                        .putInt("pushed", report.pushed)
                        .putInt("conflicts", report.conflicts)
                        .build()
                )
            }
            // 登录失效重试没有意义，等用户去设置页重新登录
            report.error.contains("登录已失效") -> Result.failure()
            else -> Result.retry()
        }
    }
}

object SyncScheduler {

    private const val PERIODIC_WORK = "fc_sync_periodic"
    private const val ONE_SHOT_WORK = "fc_sync_now"

    /**
     * 周期性同步。最小间隔 15 分钟是 WorkManager 的硬限制，填更小的值会被静默改成 15。
     * 默认要求不计费网络 —— 联系人数据量不大，但用户不该为后台同步付流量费，
     * 想改的话设置页有开关。
     */
    fun schedulePeriodic(context: Context, intervalMinutes: Long = 60, requireUnmetered: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * 立刻同步一次。编辑完联系人、用户下拉刷新、App 回到前台时调用。
     * REPLACE 保证短时间内连续编辑多个联系人只会触发一次同步。
     */
    fun syncNow(context: Context, trigger: String = "manual") {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(Data.Builder().putString(SyncWorker.KEY_TRIGGER, trigger).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(ONE_SHOT_WORK)
        }
    }
}
