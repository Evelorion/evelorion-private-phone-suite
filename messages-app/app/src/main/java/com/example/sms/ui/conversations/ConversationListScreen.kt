package com.example.sms.ui.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sms.ui.common.AvatarView
import com.example.sms.ui.common.ConversationUi

/* 1a — 标准列表：搜索栏 + 未读分组卡片 + 其余会话列表 + 扩展式 FAB */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    state: ListUiState,
    onOpen: (ConversationUi) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onPin: (ConversationUi) -> Unit,
    onMute: (ConversationUi) -> Unit,
    onMarkRead: (ConversationUi) -> Unit,
    onDelete: (ConversationUi) -> Unit,
    onMarkAllRead: () -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCompose,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                text = { Text("新对话") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            banner?.let { b -> item { b() } }
            item { DockedSearchField(onClick = onSearch, onSettings = onSettings) }

            if (state.isEmpty) {
                item { EmptyState() }
            }

            val unread = state.unread
            val earlier = state.earlier

            if (unread.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = "未读",
                        color = MaterialTheme.colorScheme.primary,
                        top = 6.dp,
                        onMarkAllRead = onMarkAllRead,
                    )
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column {
                            unread.forEachIndexed { i, c ->
                                ConversationRow(
                                    c, horizontalPadding = 16.dp,
                                    onClick = { onOpen(c) },
                                    onPin = { onPin(c) }, onMute = { onMute(c) },
                                    onMarkRead = { onMarkRead(c) }, onDelete = { onDelete(c) },
                                )
                                if (i != unread.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (earlier.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)) }
                items(earlier, key = { it.id }) { c ->
                    ConversationRow(
                        c, horizontalPadding = 20.dp,
                        onClick = { onOpen(c) },
                        onPin = { onPin(c) }, onMute = { onMute(c) },
                        onMarkRead = { onMarkRead(c) }, onDelete = { onDelete(c) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.EditNote, null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("还没有会话", style = MaterialTheme.typography.titleMedium)
        Text(
            "点右下角开始新对话；授予短信权限后会自动导入手机里已有的短信。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
    }
}

/** 未读分组标题：右侧带「全部已读」 */
@Composable
private fun SectionHeader(text: String, color: Color, top: Dp, onMarkAllRead: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = top, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMarkAllRead, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Default.DoneAll, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("全部已读", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DockedSearchField(onClick: () -> Unit, onSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(56.dp),
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        ) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(
                "搜索会话",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    c: ConversationUi,
    horizontalPadding: Dp,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val unread = c.unreadCount > 0

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = horizontalPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AvatarView(c.avatar)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        c.name,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                    if (c.pinned) {
                        Icon(
                            Icons.Default.PushPin, "已置顶", Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (c.muted) {
                        Icon(
                            Icons.Default.NotificationsOff, "已静音", Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        c.time,
                        color = if (unread) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (c.isImagePreview) {
                        Icon(
                            Icons.Default.Image, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        c.preview,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (unread) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                        ),
                    )
                }
            }

            if (unread) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (c.unreadCount > 99) "99+" else c.unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        ConversationMenu(
            expanded = menuOpen,
            conversation = c,
            onDismiss = { menuOpen = false },
            onPin = onPin, onMute = onMute, onMarkRead = onMarkRead, onDelete = onDelete,
        )
    }
}

@Composable
private fun ConversationMenu(
    expanded: Boolean,
    conversation: ConversationUi,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
            onClick = { onPin(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (conversation.muted) "取消静音" else "静音") },
            onClick = { onMute(); onDismiss() },
        )
        if (conversation.unreadCount > 0) {
            DropdownMenuItem(text = { Text("标记为已读") }, onClick = { onMarkRead(); onDismiss() })
        }
        DropdownMenuItem(text = { Text("删除会话") }, onClick = { onDelete(); onDismiss() })
    }
}
