package com.evelorion.contacts.ui

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 后台线程池。
 *
 * ── 为什么不直接用 Executors.newSingleThreadExecutor() ──────
 *
 * 因为 `executor.execute { ... }` 里抛出的异常**没有人接**：
 * ThreadPoolExecutor 让它一路冒到工作线程外面，走默认的
 * UncaughtExceptionHandler —— 也就是**整个进程被杀掉**。
 *
 * 这不是理论问题。一次真实的闪退就是这么来的：同步设置页在后台线程里
 * 读同步库，数据库打不开抛了 SQLiteException，用户看到的是「点开就闪退」，
 * 而崩溃点和真正的原因（数据库文件加密状态不对）中间隔着十几层框架栈。
 *
 * 一个读不出状态的页面应该是「显示读取失败」，不该是「App 没了」。
 * 所以这里在**工作线程本身**外面包一层：任务抛什么都拦得住，
 * 而且拦下来还能记日志 —— 比在十个调用点各写一个 try/catch 可靠。
 *
 * 注意它只保证**进程不死**。任务失败后界面该显示什么，
 * 仍然要各个页面自己处理（一般是把 UI 更新也放进同一个 try 里）。
 */
object Bg {

    private const val TAG = "Bg"

    fun single(name: String): ExecutorService {
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        return Executors.newSingleThreadExecutor { runnable ->
            Thread({
                try {
                    runnable.run()
                } catch (t: Throwable) {
                    // 走到这里说明某个任务抛了异常。不再往上抛 ——
                    // 往上抛就是进程自杀，而这里丢的只是一个后台任务。
                    Log.e(TAG, "后台任务异常（$name），已拦下，进程继续", t)
                }
            }, "bg-$name-${counter.incrementAndGet()}")
        }
    }
}
