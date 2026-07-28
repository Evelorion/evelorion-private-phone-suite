package com.evelorion.contacts.sync.work

import android.os.Handler
import android.os.Looper

/**
 * 同步完成的进程内通知。
 *
 * ── 为什么不用广播 ──────────────────────────────────────────
 *
 * LocalBroadcastManager 已经废弃，而全局广播要多一层权限考虑：
 * 「这台设备刚同步完通讯录」这个事实本身就是元数据，没必要让别的 App 知道。
 *
 * 这里就是个进程内的监听器列表 —— 同步是在同一个进程的 Worker 里跑的，
 * 不需要跨进程机制。
 *
 * ── 监听器为什么要手动移除 ────────────────────────────────
 *
 * 持有 Activity 引用的 lambda 不移除就是内存泄漏：Activity 销毁了，
 * 这个列表还攥着它，整棵 View 树都回收不掉。
 * 调用方在 onStart 注册、onStop 注销。
 */
object SyncEvents {

    fun interface Listener {
        fun onSyncFinished(pulled: Int, pushed: Int)
    }

    private val listeners = mutableSetOf<Listener>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * 同步完成。**从后台线程调用**，所以要切回主线程再通知 ——
     * 监听器基本都是去刷新 UI 的。
     */
    fun notifyFinished(pulled: Int, pushed: Int) {
        val snapshot = synchronized(this) { listeners.toList() }
        if (snapshot.isEmpty()) return
        main.post { snapshot.forEach { it.onSyncFinished(pulled, pushed) } }
    }
}
