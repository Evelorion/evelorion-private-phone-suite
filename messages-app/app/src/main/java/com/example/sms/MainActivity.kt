package com.example.sms

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.sms.data.prefs.ListStyle
import com.example.sms.ui.chat.ChatScreen
import com.example.sms.ui.chat.ChatViewModel
import com.example.sms.ui.common.SimpleFactory
import com.example.sms.ui.conversations.ConversationListExpressiveScreen
import com.example.sms.ui.conversations.ConversationListFilteredScreen
import com.example.sms.ui.conversations.ConversationListScreen
import com.example.sms.ui.conversations.ConversationListViewModel
import com.example.sms.ui.newmsg.NewMessageScreen
import com.example.sms.ui.newmsg.NewMessageViewModel
import com.example.sms.ui.search.SearchScreen
import com.example.sms.ui.search.SearchViewModel
import com.example.sms.ui.settings.SettingsScreen
import com.example.sms.ui.settings.SettingsViewModel
import com.example.sms.ui.theme.SmsTheme
import com.example.sms.util.SimUtils
import com.example.sms.util.SmsRoleCheck
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        enableEdgeToEdge()

        val initialThreadId = intent?.getLongExtra(EXTRA_THREAD_ID, -1L) ?: -1L
        val sendToAddress = extractSendToAddress(intent)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            val listVm: ConversationListViewModel =
                viewModel(factory = SimpleFactory { ConversationListViewModel() })
            val settings by listVm.settings.collectAsStateWithLifecycle()

            SmsTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                seedColor = settings.seedColor,
            ) {
                SmsApp(
                    listVm = listVm,
                    initialThreadId = initialThreadId,
                    sendToAddress = sendToAddress,
                    sharedText = sharedText,
                )
            }
        }
    }

    /** 处理从拨号盘/浏览器过来的 smsto: 意图 */
    private fun extractSendToAddress(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        return when (data.scheme) {
            "sms", "smsto", "mms", "mmsto" -> data.schemeSpecificPart
                ?.substringBefore("?")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

private val smsPermissions = buildList {
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun Context.isDefaultSmsApp() =
    SmsRoleCheck.isDefaultSmsApp(this)

private fun Context.copyToClipboard(text: String, toast: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("sms", text))
    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

@Composable
fun SmsApp(
    listVm: ConversationListViewModel,
    initialThreadId: Long = -1L,
    sendToAddress: String? = null,
    sharedText: String? = null,
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val settings by listVm.settings.collectAsStateWithLifecycle()

    var permissionsAsked by remember { mutableStateOf(false) }
    var isDefaultSms by remember { mutableStateOf(context.isDefaultSmsApp()) }
    var hasPhoneStatePermission by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.READ_PHONE_STATE))
    }
    val sims = remember(hasPhoneStatePermission) { SimUtils.activeSims(context) }
    var bannerDismissed by rememberSaveable { mutableStateOf(false) }

    // 从系统设置页回来后立刻刷新「是否已是默认短信应用」
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefaultSms = context.isDefaultSmsApp()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var hasSmsPermission by remember { mutableStateOf(context.hasPermission(Manifest.permission.READ_SMS)) }
    var hasContactsPermission by remember { mutableStateOf(context.hasPermission(Manifest.permission.READ_CONTACTS)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasSmsPermission = result[Manifest.permission.READ_SMS] ?: hasSmsPermission
        hasContactsPermission = result[Manifest.permission.READ_CONTACTS] ?: hasContactsPermission
        hasPhoneStatePermission = context.hasPermission(Manifest.permission.READ_PHONE_STATE)
        if (hasSmsPermission) listVm.importSystemSms()
    }

    val defaultAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefaultSms = context.isDefaultSmsApp()
        if (hasSmsPermission) listVm.importSystemSms()
    }

    val requestDefaultSmsApp: () -> Unit = {
        // 首选：RoleManager（Android 10+）/ ACTION_CHANGE_DEFAULT（更早版本）
        val launched = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = context.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    defaultAppLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                defaultAppLauncher.launch(
                    Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                        putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                    }
                )
                true
            }
        }.getOrDefault(false)

        // 兜底：部分定制系统不响应上面的请求，直接打开系统「默认应用」设置页
        if (!launched) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            Toast.makeText(
                context,
                if (opened) "请在「短信应用」里选择「信息」"
                else "请到 设置 → 应用 → 默认应用 → 短信应用 中选择「信息」",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionsAsked) {
            permissionsAsked = true
            val missing = smsPermissions.filterNot { context.hasPermission(it) }
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
            else listVm.importSystemSms()
        }
    }

    // 从通知点进来时直接跳到该会话
    LaunchedEffect(initialThreadId) {
        if (initialThreadId > 0) nav.navigate("chat/" + initialThreadId)
    }

    // 从 smsto: 意图进来时建会话并跳过去
    LaunchedEffect(sendToAddress) {
        val address = sendToAddress ?: return@LaunchedEffect
        val threadId = Graph.repository.ensureThread(address)
        if (!sharedText.isNullOrBlank()) Graph.repository.saveDraft(threadId, sharedText)
        nav.navigate("chat/" + threadId)
    }

    NavHost(nav, startDestination = "list") {

        composable("list") {
            val state by listVm.state.collectAsStateWithLifecycle()
            val open: (Long) -> Unit = { nav.navigate("chat/" + it) }

            val bannerSlot: (@Composable () -> Unit)? =
                if (!isDefaultSms && !bannerDismissed) {
                    {
                        DefaultAppBanner(
                            onSet = requestDefaultSmsApp,
                            onDismiss = { bannerDismissed = true },
                        )
                    }
                } else null

            run {
                when (settings.listStyle) {
                    ListStyle.LIST_1A -> ConversationListScreen(
                        state = state,
                        onOpen = { open(it.id) },
                        onCompose = { nav.navigate("new") },
                        onSearch = { nav.navigate("search") },
                        onSettings = { nav.navigate("settings") },
                        onPin = { listVm.togglePin(it.id, !it.pinned) },
                        onMute = { listVm.toggleMute(it.id, !it.muted) },
                        onMarkRead = { listVm.markRead(it.id) },
                        onDelete = { listVm.delete(it.id) },
                        onMarkAllRead = { listVm.markAllRead() },
                        banner = bannerSlot,
                    )
                    ListStyle.LIST_1B -> ConversationListFilteredScreen(
                        state = state,
                        onFilterChange = listVm::setFilter,
                        onOpen = { open(it.id) },
                        onCompose = { nav.navigate("new") },
                        onSearch = { nav.navigate("search") },
                        onSettings = { nav.navigate("settings") },
                        onPin = { listVm.togglePin(it.id, !it.pinned) },
                        onMute = { listVm.toggleMute(it.id, !it.muted) },
                        onMarkRead = { listVm.markRead(it.id) },
                        onDelete = { listVm.delete(it.id) },
                        onMarkAllRead = { listVm.markAllRead() },
                        banner = bannerSlot,
                    )
                    ListStyle.LIST_1C -> ConversationListExpressiveScreen(
                        state = state,
                        onOpen = { open(it.id) },
                        onCompose = { nav.navigate("new") },
                        onSearch = { nav.navigate("search") },
                        onSettings = { nav.navigate("settings") },
                        onMarkAllRead = { listVm.markAllRead() },
                        banner = bannerSlot,
                    )
                }
            }
        }

        composable(
            route = "chat/{threadId}",
            arguments = listOf(navArgument("threadId") { type = NavType.LongType }),
        ) { entry ->
            val threadId = entry.arguments?.getLong("threadId") ?: return@composable
            val vm: ChatViewModel = viewModel(
                key = "chat-" + threadId,
                factory = SimpleFactory { ChatViewModel(threadId) },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            val scope = androidx.compose.runtime.rememberCoroutineScope()

            ChatScreen(
                state = state,
                smartReplyEnabled = settings.smartReplyEnabled,
                rcsEnabled = settings.rcsEnabled,
                onBack = { vm.persistDraft(); nav.popBackStack() },
                onDraftChange = vm::onDraftChange,
                onSend = vm::send,
                onSendImage = { vm.sendImage(it) },
                onReact = { id, emoji -> vm.react(id, emoji) },
                onRetry = { id, body -> vm.retry(id, body) },
                onDeleteMessage = vm::deleteMessage,
                onCall = {
                    val number = state.address.substringBefore(";")
                    if (number.isNotBlank()) {
                        scope.launch {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                },
                onCopy = { context.copyToClipboard(it, "已复制") },
                sims = sims,
                selectedSubId = settings.sendSubId,
                onSelectSim = { subId -> scope.launch { Graph.settings.setSendSubId(subId) } },
            )
        }

        composable("new") {
            val vm: NewMessageViewModel = viewModel(factory = SimpleFactory { NewMessageViewModel() })
            val state by vm.state.collectAsStateWithLifecycle()

            NewMessageScreen(
                state = state,
                hasContactsPermission = hasContactsPermission,
                onQueryChange = vm::onQueryChange,
                onAddContact = vm::addContact,
                onAddRawNumber = vm::addRawNumber,
                onRemoveRecipient = vm::removeRecipient,
                onRequestContacts = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    vm.loadContacts()
                },
                onBack = { nav.popBackStack() },
                onStart = {
                    vm.startConversation { threadId ->
                        nav.navigate("chat/" + threadId) { popUpTo("list") }
                    }
                },
            )
        }

        composable("search") {
            val vm: SearchViewModel = viewModel(factory = SimpleFactory { SearchViewModel() })
            val state by vm.state.collectAsStateWithLifecycle()

            SearchScreen(
                state = state,
                onQueryChange = vm::onQueryChange,
                onClear = vm::clear,
                onBack = { nav.popBackStack() },
                onOpenThread = { nav.navigate("chat/" + it) },
                onCopyCode = { context.copyToClipboard(it, "已复制 " + it) },
            )
        }

        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = SimpleFactory { SettingsViewModel() })
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(isDefaultSms) { vm.refreshRole() }

            SettingsScreen(
                state = state,
                onBack = { nav.popBackStack() },
                onNotifications = vm::setNotifications,
                onBlockSpam = vm::setBlockSpam,
                onRcs = vm::setRcs,
                onSmartReply = vm::setSmartReply,
                onListStyle = vm::setListStyle,
                onThemeMode = vm::setThemeMode,
                onDynamicColor = vm::setDynamicColor,
                onSeedColor = vm::setSeedColor,
                onSetDefaultApp = requestDefaultSmsApp,
                onReimport = vm::reimport,
            )
        }
    }
}

@Composable
private fun DefaultAppBanner(onSet: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Sms, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "设为默认短信应用才能收新短信",
                Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onSet) { Text("去设置") }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close, "关闭",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
