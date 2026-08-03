package com.evelorion.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.evelorion.phone.telecom.DialerRole
import com.evelorion.phone.telecom.CallScreeningRole
import com.evelorion.phone.ui.CrashReport
import com.evelorion.phone.ui.PhoneApp
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.theme.PhoneM3Theme

class MainActivity : ComponentActivity() {

    private var dialIntentRevision by mutableIntStateOf(0)
    private var dialIntentNumber = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 上次崩着退出的，先把堆栈摆出来。继续初始化多半会再崩一次，
        // 那时用户又是一片空白，什么信息都拿不到。
        if (CrashReport.showIfPending(this)) {
            finish()
            return
        }

        setContent {
            PhoneM3Theme {
                val state = androidx.compose.runtime.remember { PhoneState() }

                LaunchedEffect(dialIntentRevision) {
                    if (dialIntentRevision > 0) {
                        state.dial = dialIntentNumber
                        state.go(com.evelorion.phone.ui.Screen.Dialpad)
                    }
                }

                // 来电只有在本 App 是默认电话应用时才会交给 PhoneInCallService。
                // 首次启动就申请，不能把这个关键步骤藏在设置页里等用户自己找。
                val dialerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    state.settingsStatus = state.settingsStatus.copy(
                        isDefaultDialer = DialerRole.isDefault(this@MainActivity)
                    )
                }
                val requestDialerRole = {
                    if (!DialerRole.isDefault(this@MainActivity)) {
                        val intent = DialerRole.requestIntent(this@MainActivity)
                        if (intent != null) dialerLauncher.launch(intent)
                        else Toast.makeText(
                            this@MainActivity,
                            "这台设备上没有可用的设置入口",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                val screeningLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    state.settingsStatus = state.settingsStatus.copy(
                        isCallScreeningEnabled = CallScreeningRole.isHeld(this@MainActivity)
                    )
                }
                val requestCallScreeningRole = {
                    if (!CallScreeningRole.isHeld(this@MainActivity)) {
                        val intent = CallScreeningRole.requestIntent(this@MainActivity)
                        if (intent != null) screeningLauncher.launch(intent)
                        else Toast.makeText(
                            this@MainActivity,
                            "这台设备不支持第三方来电拦截",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                // 运行时权限。没有这些，拨号盘按了没反应、最近通话是空的，
                // 而且都不会报错 —— 所以一进来就要。
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    requestDialerRole()
                }
                LaunchedEffect(Unit) {
                    val missing = REQUIRED_PERMISSIONS.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    } else {
                        requestDialerRole()
                    }
                }

                // 拨号请求。真正的拨出交给 Telecom，界面不自己跳转 ——
                // 通话建立之前跳过去，用户会看到一个不存在的通话。
                LaunchedEffect(state.pendingNumber) {
                    val number = state.consumeCallRequest()
                    if (number.isNotBlank()) placeCall(number)
                }

                PhoneApp(
                    state = state,
                    onRequestDialerRole = requestDialerRole,
                    onRequestCallScreeningRole = requestCallScreeningRole,
                    onOpenContacts = { openContactsApp() },
                )
            }
        }

        // 进来先看一眼有没有待拨的号码（从别的 App 点 tel: 链接过来）
        handleDialIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    /** 别的 App 通过 tel: 把号码递过来。填进拨号盘，不直接拨 —— 用户还没确认。 */
    private fun handleDialIntent(intent: Intent?) {
        val data = intent?.data
        val openEmptyDialpad = intent?.getBooleanExtra(EXTRA_OPEN_DIALPAD, false) == true
        if (data?.scheme != "tel" && !openEmptyDialpad) return
        dialIntentNumber = data?.let { Uri.decode(it.schemeSpecificPart).orEmpty() }.orEmpty()
        dialIntentRevision++
    }

    /**
     * 真正拨出去。
     *
     * placeCall 需要 CALL_PHONE 权限；没有权限时它**不抛异常也不拨号**，
     * 静默失败。所以这里自己先查一遍，查不过就明确告诉用户。
     */
    private fun placeCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "还没有拨打电话的权限", Toast.LENGTH_SHORT).show()
            return
        }
        val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        runCatching {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        }.onFailure {
            Toast.makeText(this, "拨号失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 跳去通讯录 App。
     *
     * 用户在设置页看到「保险库锁着」时，要去的是通讯录那边解锁 ——
     * 光在这里点没有用。两个包名都试（debug 版带 .debug 后缀），
     * 都找不到就说明通讯录没装。
     */
    private fun openContactsApp() {
        for (pkg in listOf("com.evelorion.contacts", "com.evelorion.contacts.debug")) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, "没有找到通讯录应用，请先安装", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_OPEN_DIALPAD = "com.evelorion.phone.extra.OPEN_DIALPAD"

        val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.WRITE_CALL_LOG)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
