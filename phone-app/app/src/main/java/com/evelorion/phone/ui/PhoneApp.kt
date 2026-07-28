package com.evelorion.phone.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evelorion.phone.bridge.ContactsBridge
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.screens.*
import com.evelorion.phone.ui.screens.SettingsStatus

/**
 * 主界面里的页面。
 *
 * **来电屏和通话中屏不在这里** —— 它们归 CallActivity 管。
 * 通话界面要能在锁屏上方亮屏显示，而且必须在 App 没启动时也能被系统拉起来，
 * 挂在主 Activity 的导航里做不到这两点。
 */
enum class Screen { Recents, Contacts, Favorites, Dialpad, Search, Detail, Settings, Recordings }

class PhoneState {
    var screen by mutableStateOf(Screen.Recents)
    var previous by mutableStateOf(Screen.Recents)
    var selectedId by mutableStateOf<String?>("mom")
    var dial by mutableStateOf("")
    var query by mutableStateOf("")
    var spamShield by mutableStateOf(true)
    var showCity by mutableStateOf(true)
    var pinFavorites by mutableStateOf(true)
    var recording by mutableStateOf(false)

    fun go(target: Screen) {
        if (target != screen) previous = screen
        screen = target
    }

    fun back() {
        screen = if (previous == screen) Screen.Recents else previous
        query = ""
    }

    /**
     * 拨号。
     *
     * 真正的拨出交给 Telecom（Dialer.place），通话界面由系统回调
     * InCallService 之后自己拉起来 —— 我们不能自己跳到通话界面，
     * 因为那时候通话还没建立，界面上会显示一个不存在的通话。
     */
    var pendingNumber by mutableStateOf("")
        private set

    fun requestCall(number: String) {
        pendingNumber = number
    }

    /**
     * 按联系人拨号。
     *
     * 保留这个签名是为了让九个界面里的 `state.call(p.id)` 一个字都不用改 ——
     * 那些是设计稿代码，动得越少越不容易和设计走样。
     * 号码在这里解析：界面只知道"这个人"，不该关心他的号码存在哪。
     */
    /** 设置页要显示的真实状态。后台线程刷新，界面直接读。 */
    var settingsStatus by mutableStateOf(SettingsStatus())

    fun call(id: String?) {
        val number = PhoneData.person(id)?.number.orEmpty()
        if (number.isNotBlank()) requestCall(number)
    }

    fun consumeCallRequest(): String = pendingNumber.also { pendingNumber = "" }
}

@Composable
fun PhoneApp(
    state: PhoneState = remember { PhoneState() },
    /** 请求成为默认电话应用。由 Activity 提供 —— 它要 startActivityForResult。 */
    onRequestDialerRole: () -> Unit = {},
    /** 跳去通讯录（让用户解锁保险库 / 建家人分组）。 */
    onOpenContacts: () -> Unit = {},
) {
    // 联系人和通话记录都要跨进程/查数据库，必须离开主线程。
    // 放在 LaunchedEffect 里而不是 remember{}：remember 的初始化在
    // 组合期间同步执行，那就等于在主线程上做 IO。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var contactsRevision by remember { mutableStateOf(0) }

    DisposableEffect(context, lifecycleOwner) {
        val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                contactsRevision++
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                ContactsBridge.CONTACTS_URI,
                true,
                contactsObserver,
            )
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) contactsRevision++
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(contactsObserver) }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    androidx.compose.runtime.LaunchedEffect(contactsRevision) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { PhoneData.load(context) }
            // 状态也在这里刷 —— 三个来源都要跨进程或读库，全是耗时操作
            runCatching { state.settingsStatus = PhoneData.settingsStatus(context) }
        }
    }

    // 屏幕间使用 M3 emphasized 转场：下层淡入上移，上层淡出
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                val isOverlay = targetState == Screen.Dialpad
                if (isOverlay) {
                    (slideInVertically(Motion.emphasized()) { it / 4 } +
                            fadeIn(tween(Motion.DurationShort))) togetherWith
                            fadeOut(tween(Motion.DurationShort))
                } else {
                    (fadeIn(tween(Motion.DurationMedium)) +
                            scaleIn(Motion.emphasized(), initialScale = 0.985f)) togetherWith
                            (fadeOut(tween(Motion.DurationShort)) +
                                    slideOutVertically(Motion.emphasized()) { -it / 20 })
                }
            },
            label = "screen"
        ) { screen ->
            when (screen) {
                Screen.Recents -> RecentsScreen(state)
                Screen.Contacts -> ContactsScreen(state)
                Screen.Favorites -> FavoritesScreen(state)
                Screen.Dialpad -> DialpadScreen(state)
                Screen.Search -> SearchScreen(state)
                Screen.Detail -> CallDetailScreen(state)
                Screen.Settings -> SettingsScreen(
                    state = state,
                    status = state.settingsStatus,
                    onRequestDialerRole = onRequestDialerRole,
                    onOpenContacts = onOpenContacts,
                )
                Screen.Recordings -> RecordingsScreen(state)
            }
        }
    }
}
