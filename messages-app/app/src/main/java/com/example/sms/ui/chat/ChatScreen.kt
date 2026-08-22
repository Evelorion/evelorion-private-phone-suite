package com.example.sms.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sms.data.db.MsgStatus
import com.example.sms.data.db.MsgType
import com.example.sms.util.SimInfo
import com.example.sms.util.SimUtils
import com.example.sms.util.VoicePlayer
import kotlinx.coroutines.launch

/* 1d / 1h — 会话详情：文字 / 图片 / 语音波形 / 表情回应 / 正在输入 */

private val reactionChoices = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ChatScreen(
    state: ChatUiState,
    rcsEnabled: Boolean,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendImage: (String) -> Unit,
    onReact: (Long, String?) -> Unit,
    onRetry: (Long, String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onCall: () -> Unit,
    onCopy: (String) -> Unit,
    sims: List<SimInfo> = emptyList(),
    selectedSubId: Int = SimUtils.SUB_DEFAULT,
    onSelectSim: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> uri?.let { onSendImage(it.toString()) } }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    // 键盘弹起时把最后一条消息顶到可见区域
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    DisposableEffect(Unit) {
        onDispose { VoicePlayer.stop() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                state.title.take(1),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Column {
                            Text(state.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (rcsEnabled) "RCS · " + state.subtitle else state.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onCall) { Icon(Icons.Default.Call, "通话") }
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "更多") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
        bottomBar = {
            // enableEdgeToEdge() 下 manifest 的 adjustResize 不再自动生效，
            // 必须自己吃掉 IME inset，输入框才会跟着键盘一起抬起。
            // 先 navigationBarsPadding 再 imePadding：前者会消费掉导航栏 inset，
            // 后者只补剩余的键盘高度，两者不会重复叠加。
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                        ) {
                            if (sims.isNotEmpty()) {
                                SimSelector(
                                    sims = sims,
                                    selectedSubId = selectedSubId,
                                    onSelect = onSelectSim,
                                )
                            }
                            TextField(
                                value = state.draft,
                                onValueChange = onDraftChange,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("发送短信") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                                singleLine = false,
                                maxLines = 4,
                            )
                            IconButton(onClick = { onDraftChange(state.draft + "😊") }) {
                                Icon(Icons.Default.SentimentSatisfied, "表情")
                            }
                            IconButton(
                                onClick = {
                                    imagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            ) { Icon(Icons.Default.Image, "图片") }
                        }
                    }

                    // 发送按钮常驻显示；没内容或正在发送时置灰
                    FilledIconButton(
                        onClick = onSend,
                        enabled = state.draft.isNotBlank() && !state.sending,
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items.size) { i ->
                ChatRow(
                    item = state.items[i],
                    onReact = onReact,
                    onRetry = onRetry,
                    onDelete = onDeleteMessage,
                    onCopy = onCopy,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    item: ChatItem,
    onReact: (Long, String?) -> Unit,
    onRetry: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    when (item) {
        is ChatItem.DayDivider -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    item.label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        ChatItem.Typing -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(1f, 0.6f, 0.35f).forEach { a ->
                        Box(
                            Modifier.size(7.dp).clip(CircleShape).background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a)
                            )
                        )
                    }
                }
            }
        }

        is ChatItem.Message -> MessageBubble(item, onReact, onRetry, onDelete, onCopy)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: ChatItem.Message,
    onReact: (Long, String?) -> Unit,
    onRetry: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var reactionOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
    ) {
        Box {
            Box(
                Modifier.combinedClickable(
                    onClick = { if (m.status == MsgStatus.FAILED) onRetry(m.id, m.body) },
                    onLongClick = { menuOpen = true },
                )
            ) {
                when (m.type) {
                    MsgType.TEXT -> Surface(
                        shape = bubbleShape(m.outgoing),
                        color = bubbleColor(m),
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Text(
                            m.body,
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = if (m.outgoing) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    MsgType.IMAGE -> Surface(
                        shape = bubbleShape(m.outgoing),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        if (m.attachmentUri != null) {
                            AsyncImage(
                                model = m.attachmentUri,
                                contentDescription = m.body,
                                modifier = Modifier.size(220.dp, 165.dp).clip(bubbleShape(m.outgoing)),
                            )
                        } else {
                            Box(Modifier.size(200.dp, 150.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    m.body, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    MsgType.VOICE -> VoiceBubble(m)
                }
            }

            m.reaction?.let {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 10.dp),
                ) { Text(it, Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("表情回应") },
                    leadingIcon = { Icon(Icons.Default.SentimentSatisfied, null) },
                    onClick = { menuOpen = false; reactionOpen = true },
                )
                DropdownMenuItem(
                    text = { Text("复制文本") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = { onCopy(m.body); menuOpen = false },
                )
                if (m.status == MsgStatus.FAILED) {
                    DropdownMenuItem(
                        text = { Text("重新发送") },
                        onClick = { onRetry(m.id, m.body); menuOpen = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除消息") },
                    onClick = { onDelete(m.id); menuOpen = false },
                )
            }

            DropdownMenu(expanded = reactionOpen, onDismissRequest = { reactionOpen = false }) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reactionChoices.forEach { e ->
                        TextButton(onClick = { onReact(m.id, e); reactionOpen = false }) { Text(e) }
                    }
                }
                if (m.reaction != null) {
                    DropdownMenuItem(
                        text = { Text("移除回应") },
                        onClick = { onReact(m.id, null); reactionOpen = false },
                    )
                }
            }
        }

        m.statusLabel?.let { label ->
            Text(
                label,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (m.status == MsgStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceBubble(m: ChatItem.Message) {
    var playing by remember(m.attachmentUri) { mutableStateOf(false) }
    Surface(
        shape = bubbleShape(m.outgoing),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(
                onClick = {
                    val uri = m.attachmentUri ?: return@FilledIconButton
                    playing = VoicePlayer.toggle(uri) { playing = false }
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放")
            }
            Waveform(progress = if (playing) 0.6f else 0f, seed = m.id)
            Text(
                formatDuration(m.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}

@Composable
private fun bubbleColor(m: ChatItem.Message) = when {
    !m.outgoing -> MaterialTheme.colorScheme.surfaceContainerHigh
    m.status == MsgStatus.FAILED -> MaterialTheme.colorScheme.error
    m.status == MsgStatus.PENDING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    else -> MaterialTheme.colorScheme.primary
}

private fun bubbleShape(mine: Boolean) = if (mine)
    RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

@Composable
private fun Waveform(progress: Float, seed: Long) {
    val bars = remember(seed) {
        val rnd = java.util.Random(seed)
        List(12) { 8 + rnd.nextInt(20) }
    }
    val playedCount = (bars.size * progress).toInt()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        bars.forEachIndexed { i, h ->
            Box(
                Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(
                    if (i < playedCount) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
    }
}

/** 输入栏里的 SIM 卡选择：发送前就近切换当前使用的卡。 */
@Composable
private fun SimSelector(
    sims: List<SimInfo>,
    selectedSubId: Int,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = sims.firstOrNull { it.subscriptionId == selectedSubId }

    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SimCard, "选择 SIM 卡", Modifier.size(20.dp))
            Text(
                current?.shortLabel ?: "默认",
                Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("系统默认卡") },
                leadingIcon = {
                    Icon(
                        if (selectedSubId == SimUtils.SUB_DEFAULT) Icons.Default.RadioButtonChecked
                        else Icons.Default.RadioButtonUnchecked,
                        null,
                        Modifier.size(18.dp),
                    )
                },
                onClick = { onSelect(SimUtils.SUB_DEFAULT); open = false },
            )
            sims.forEach { sim ->
                DropdownMenuItem(
                    text = { Text(sim.label) },
                    leadingIcon = {
                        Icon(
                            if (selectedSubId == sim.subscriptionId) Icons.Default.RadioButtonChecked
                            else Icons.Default.RadioButtonUnchecked,
                            null,
                            Modifier.size(18.dp),
                        )
                    },
                    onClick = { onSelect(sim.subscriptionId); open = false },
                )
            }
        }
    }
}
