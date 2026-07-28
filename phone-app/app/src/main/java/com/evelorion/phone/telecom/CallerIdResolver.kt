package com.evelorion.phone.telecom

import android.content.Context
import android.util.Log
import com.evelorion.phone.bridge.ContactsBridge
import java.util.concurrent.Executors

/**
 * 来电显示。
 *
 * 号码 → 通讯录里的名字。查询要跨进程、要解密，所以必然是异步的，
 * 而来电界面是立刻就要显示的 —— 先显示号码，查到名字再替换。
 *
 * 反过来做（等查到再显示界面）会让来电界面晚零点几秒才出现，
 * 那零点几秒里用户会以为手机卡了。
 */
object CallerIdResolver {

    private const val TAG = "CallerId"
    private val io = Executors.newSingleThreadExecutor { r ->
        // 包一层：查询失败不该把整个电话进程带走 —— 正在响铃呢
        Thread({ runCatching { r.run() }.onFailure { Log.w(TAG, "来电显示查询异常", it) } }, "callerid")
    }

    fun resolveAsync(context: Context, number: String) {
        if (number.isBlank()) return
        io.execute {
            val hit = ContactsBridge.lookup(context, number)
            if (hit != null && hit.name.isNotBlank()) {
                // 回主线程写 Compose 状态
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    CallManager.updateCallerName(hit.name)
                }
            }
        }
    }
}
