package com.evelorion.contacts

import android.app.Application
import android.content.Context
import android.util.Log
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.work.SyncScheduler
import com.evelorion.contacts.sync.localdb.EncryptedDatabases
import com.evelorion.contacts.ui.CrashReport
import com.evelorion.contacts.ui.theme.M3Theme

/**
 * Application。
 *
 * ── 为什么 SQLCipher 要在 attachBaseContext 里装 ────────────
 *
 * Android 的启动顺序是：
 *
 *     Application.attachBaseContext()
 *   → ContentProvider.onCreate()        ← 我们的 PrivateContactsProvider 在这里
 *   → Application.onCreate()
 *
 * 放在 onCreate 里的话，ContentProvider 已经先一步用**明文方式**把数据库
 * 打开了，之后再塞加密实例就晚了 —— 那个进程里会同时存在两个指向同一个
 * 文件的连接，一个明文一个加密，行为完全不可预测。
 */
class ContactsApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 装失败不能让 App 起不来 —— 那样用户连进设置页关掉加密的机会都没有。
        // 失败时数据库保持明文，设置页的状态会如实显示「未加密」。
        // 第一件事：装崩溃兜底。比它更早的崩溃我们记不下来，
        // 所以它必须排在所有初始化前面 —— 包括数据库加密层。
        runCatching { CrashReport.install(this) }

        runCatching { EncryptedDatabases.install(this) }
            .onFailure { Log.w(TAG, "本地数据库加密层安装失败，回退到明文", it) }
    }

    override fun onCreate() {
        super.onCreate()
        // 必须早于任何 Activity 创建，否则第一个页面会先按系统默认渲染一帧
        // 再翻过来，肉眼能看到闪一下
        M3Theme.applyDarkModeGlobally(this)

        // 把 DEK 从 Keystore 取回内存。
        //
        // DEK 只活在内存里，进程被系统回收后就没了。不在这里恢复的话，
        // 用户重开 App 后一切需要解密的操作都会报「保险库已锁定」——
        // 而且他什么都没做错。
        //
        // 取不回来是正常情况（没登录过、或者用了「主口令派生」模式），
        // 那时由具体的操作去要求用户输口令，这里不打扰他。
        runCatching { VaultManager.get(this).unlockFromCache() }

        // 排周期同步。
        //
        // 之前 SyncScheduler 写好了但**没有任何地方调用**，所以从来没有
        // 自动同步过 —— 用户只能手点「立即同步」，还会以为是坏了。
        //
        // 只在配置过同步的情况下排。没登录就排一个必然失败的任务，
        // 除了耗电没有任何用。
        runCatching {
            if (VaultManager.get(this).isConfigured) {
                SyncScheduler.schedulePeriodic(this)
            }
        }
    }

    private companion object {
        const val TAG = "ContactsApp"
    }
}
