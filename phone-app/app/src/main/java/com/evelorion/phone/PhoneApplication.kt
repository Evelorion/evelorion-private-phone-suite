package com.evelorion.phone

import android.app.Application
import com.evelorion.phone.sync.work.CallSyncScheduler
import com.evelorion.phone.ui.CrashReport

/**
 * 电话 App 的入口。
 *
 * ── 这个 App 自己不管密钥 ────────────────────────────────────
 *
 * 主口令、DEK、恢复码全部归通讯录管。这里只在需要的时候通过
 * VaultBridge 向它要一把 **calls 子密钥**（HKDF(DEK, "calls") 派生的），
 * 用来加密通话记录。
 *
 * 为什么不各管各的：那意味着用户要记两个主口令、抄两份恢复码，
 * 而且两边的密钥体系一旦不一致，"同一个人的数据"就再也对不上了。
 */
class PhoneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 通话记录只在“通话结束写入新记录”、删除记录或用户手动操作时同步。
        // 升级时顺手取消旧版本留下的周期任务，避免无新记录也唤醒网络和加密数据库。
        runCatching { CallSyncScheduler.disablePeriodic(this) }
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // 第一件事就是装崩溃兜底 —— 比它更早的崩溃记不下来。
        // 真机上拿不到 logcat，没有这个就只剩「闪退了」三个字。
        runCatching { CrashReport.install(this) }
    }
}
