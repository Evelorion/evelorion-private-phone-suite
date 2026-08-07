package com.evelorion.contacts.sync.work

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
        // WorkManager 的通用线程池不保证低优先级。联系人同步包含数据库、网络和
        // 加解密，明确降到后台优先级，避免偶发同步与前台游戏争抢 CPU。
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        // 旧版周期任务没有 trigger。即使升级与 JobScheduler 启动发生竞态，
        // 也必须在接触保险库、数据库和网络之前直接结束。
        val trigger = inputData.getString(KEY_TRIGGER)
        if (trigger.isNullOrBlank()) {
            Log.i(TAG, "跳过旧版周期同步")
            return Result.success()
        }

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
            // 一次任务最多运行三次。持续故障等下次联系人变更或用户手动重试，
            // 不能在后台无限唤醒网络、数据库和 Argon2/加密流程。
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}

object SyncScheduler {

    private const val PERIODIC_WORK = "fc_sync_periodic"
    private const val ONE_SHOT_WORK = "fc_sync_now"
    /**
     * 新版本不再自动同步。升级安装后必须显式取消旧版本留下的周期任务，
     * 否则 JobScheduler 仍可能每小时在游戏前台启动数据库、网络和加密工作。
     */
    fun disablePeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    /**
     * 立刻同步一次。编辑完联系人、用户下拉刷新、App 回到前台时调用。
     * REPLACE 保证短时间内连续编辑多个联系人只会触发一次同步。
     */
    fun syncNow(context: Context, trigger: String = "manual") {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
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
