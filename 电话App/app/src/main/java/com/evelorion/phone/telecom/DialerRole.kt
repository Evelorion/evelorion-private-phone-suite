package com.evelorion.phone.telecom

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager

/**
 * 「设为默认电话应用」这件事。
 *
 * ── 为什么必须做 ──────────────────────────────────────────
 *
 * 不是默认电话应用时：系统根本不绑定我们的 InCallService，
 * 来电和通话界面用的是系统自带的那套，本 App 里那两屏永远看不到，
 * 接听/拒接/静音也都不归我们管。
 *
 * ── 两条路 ────────────────────────────────────────────────
 *
 * Android 10（Q）起用 RoleManager.ROLE_DIALER，之前用 TelecomManager 的
 * ACTION_CHANGE_DEFAULT_DIALER。两者都要用 startActivityForResult 发起，
 * 由系统弹框让用户确认 —— App 没法自己把自己设成默认。
 */
object DialerRole {

    fun isDefault(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        return telecom.defaultDialerPackage == context.packageName
    }

    /**
     * 返回一个「请求成为默认电话应用」的 Intent，交给调用方去 launch。
     * 返回 null 表示这台设备上没有可用的入口（极少见，通常是定制 ROM 砍掉了）。
     */
    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) return null
            return roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        }
        @Suppress("DEPRECATION")
        return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
    }

    /** 结果回来时用它判断是否成功 —— 别信 resultCode，某些 ROM 上它不准。 */
    fun succeeded(activity: Activity): Boolean = isDefault(activity)
}
