package com.evelorion.phone.telecom

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.evelorion.phone.bridge.ContactsBridge
import com.evelorion.phone.sync.db.CallDatabase
import java.util.concurrent.Executors

/**
 * 来电显示。
 *
 * 号码 → 通讯录里的名字。优先级依次是 Telecom 已提供的姓名、电话进程内存
 * 缓存、最近通话姓名快照；这些快速路径会在来电界面的第一帧前后完成。
 * 跨进程解密查询只在后台校正最新值，不阻塞来电界面。
 */
object CallerIdResolver {

    private const val TAG = "CallerId"
    private val io = Executors.newSingleThreadExecutor { r ->
        // 包一层：查询失败不该把整个电话进程带走 —— 正在响铃呢
        Thread({ runCatching { r.run() }.onFailure { Log.w(TAG, "来电显示查询异常", it) } }, "callerid")
    }

    /**
     * 电话主页已经读取过通讯录时，在显示来电 Activity 之前直接填名字。
     * 这里只读内存，不允许触发任何数据库或跨进程调用。
     */
    fun resolveCached(context: Context, number: String): Boolean {
        val name = ContactsBridge.lookupCached(context, number)?.name.orEmpty()
        if (name.isBlank()) return false
        CallManager.updateCallerName(name)
        return true
    }

    fun resolveAsync(context: Context, number: String) {
        if (number.isBlank()) return
        val alreadyNamed = CallManager.callerName.isNotBlank() || resolveCached(context, number)
        io.execute {
            val startedAt = android.os.SystemClock.elapsedRealtime()

            // 冷启动时通讯录 Provider 可能要启动另一个进程。先从本机通话记录的
            // 姓名快照找同一号码，通常几十毫秒内即可显示；随后仍以通讯录为准。
            val snapshotName = runCatching {
                CallDatabase.get(context).callDao().recent(200)
                    .firstOrNull { record ->
                        record.name.isNotBlank() && sameNumber(context, number, record.number)
                    }?.name.orEmpty()
            }.getOrDefault("")
            if (!alreadyNamed && snapshotName.isNotBlank()) {
                postNameIfCurrent(context, number, snapshotName)
            }

            // 即使内存已经命中也在后台读一次最新值；联系人刚被改名时不会一直显示旧缓存。
            val hit = ContactsBridge.lookupFresh(context, number)
            if (hit != null && hit.name.isNotBlank()) {
                postNameIfCurrent(context, number, hit.name)
            }
            Log.i(TAG, "来电姓名查询完成，耗时=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    @Suppress("DEPRECATION")
    private fun sameNumber(context: Context, first: String, second: String): Boolean =
        PhoneNumberUtils.compare(context, first, second)

    private fun postNameIfCurrent(context: Context, number: String, name: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // 查询期间可能已经挂断，或 Telecom 把号码从临时值修正成真实值。
            // 旧查询的结果绝不能盖到下一通电话上。
            if (CallManager.hasCall && sameNumber(context, number, CallManager.number)) {
                CallManager.updateCallerName(name)
            }
        }
    }
}
