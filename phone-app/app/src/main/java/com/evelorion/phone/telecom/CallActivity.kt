package com.evelorion.phone.telecom

import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.evelorion.phone.ui.screens.IncomingCallScreen
import com.evelorion.phone.ui.screens.InCallScreen
import com.evelorion.phone.ui.theme.PhoneM3Theme

/**
 * 来电 / 通话中界面的宿主。
 *
 * ── 为什么不复用 MainActivity ────────────────────────────────
 *
 * 来电要能在锁屏上方直接亮屏显示，靠的是 manifest 里的
 * showWhenLocked / turnScreenOn / showOnLockScreen。这几个属性作用于
 * 整个 Activity —— 让日常的拨号界面也带上，等于任何时候打开 App 都会
 * 强行点亮屏幕并绕过锁屏，那是个安全问题。
 *
 * singleInstance + 独立 taskAffinity：通话界面自己一个任务栈，
 * 用户从最近任务里划走拨号界面时不会把通话界面一起划掉。
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Android 16 的预测返回手势不再调用 Activity.onBackPressed()。
        // 使用 AndroidX dispatcher，按返回时把仍在通话的任务移到后台。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (CallManager.hasCall) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            PhoneM3Theme {
                var call by remember { mutableStateOf(CallManager.call) }

                // 通话结束时把界面关掉。不关的话用户会盯着一个已经挂断的
                // 通话界面，只能自己按返回 —— 像是卡死了。
                DisposableEffect(Unit) {
                    val listener: (Call?) -> Unit = { current ->
                        call = current
                        if (current == null) finish()
                    }
                    CallManager.addListener(listener)
                    onDispose { CallManager.removeListener(listener) }
                }

                if (call != null) {
                    if (CallManager.isRinging) IncomingCallScreen() else InCallScreen()
                }
            }
        }
    }

}
