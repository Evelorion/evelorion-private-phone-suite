package com.example.sms.ui.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
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
import androidx.compose.ui.unit.dp
import com.example.sms.ui.common.AvatarView
import com.example.sms.ui.common.ConversationUi

/* =========================================================
 * 1b — 分类过滤：Filter chips + 未读优先
 * ========================================================= */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListFilteredScreen(
    state: ListUiState,
    onFilterChange: (MsgFilter) -> Unit,
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
    val unread = state.unread
    val earlier = state.earlier

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("信息") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Default.EditNote, "新对话")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            banner?.let { b -> item { b() } }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MsgFilter.entries.forEach { f ->
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { onFilterChange(f) },
                            label = {
                                Text(
                                    if (f == MsgFilter.UNREAD && state.unreadTotal > 0)
                                        f.label + " " + state.unreadTotal
                                    else f.label
                                )
                            },
                            leadingIcon = if (state.filter == f) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                }
            }

            if (state.isEmpty) item { EmptyState() }

            if (unread.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "未读",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onMarkAllRead,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Default.DoneAll, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("全部已读", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column {
                            unread.forEachIndexed { i, c ->
                                CompactRow(
                                    c, unreadStyle = true, onClick = { onOpen(c) },
                                    onPin = { onPin(c) }, onMute = { onMute(c) },
                                    onMarkRead = { onMarkRead(c) }, onDelete = { onDelete(c) },
                                )
                                if (i != unread.lastIndex) {
                                    HorizontalDivider(
                                        Modifier.padding(horizontal = 16.dp),
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
                    CompactRow(
                        c, onClick = { onOpen(c) },
                        onPin = { onPin(c) }, onMute = { onMute(c) },
                        onMarkRead = { onMarkRead(c) }, onDelete = { onDelete(c) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactRow(
    c: ConversationUi,
    unreadStyle: Boolean = c.unreadCount > 0,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AvatarView(c.avatar)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (unreadStyle) FontWeight.Bold else FontWeight.Medium),
                    )
                    Text(
                        c.time,
                        color = if (unreadStyle) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    c.preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (unreadStyle) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (c.unreadCount > 0) Badge { Text(c.unreadCount.toString()) }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (c.pinned) "取消置顶" else "置顶") },
                onClick = { onPin(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text(if (c.muted) "取消静音" else "静音") },
                onClick = { onMute(); menuOpen = false },
            )
            if (c.unreadCount > 0) {
                DropdownMenuItem(text = { Text("标记为已读") }, onClick = { onMarkRead(); menuOpen = false })
            }
            DropdownMenuItem(text = { Text("删除会话") }, onClick = { onDelete(); menuOpen = false })
        }
    }
}

/* =========================================================
 * 1c — Expressive：大圆角卡片 + 强对比字重
 * ========================================================= */

@Composable
fun ConversationListExpressiveScreen(
    state: ListUiState,
    onOpen: (ConversationUi) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onMarkAllRead: () -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "信息",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    )
                    if (state.unreadTotal > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.unreadTotal.toString() + " 条未读",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = onMarkAllRead,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Default.DoneAll, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全部已读", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        Text(
                            "没有未读",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                FilledTonalIconButton(onClick = onSearch, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Search, "搜索")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Settings, "设置")
                }
            }

            if (state.isEmpty) EmptyState()

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                banner?.let { b -> item { b() } }
                items(state.shown, key = { it.id }) { c -> ExpressiveCard(c) { onOpen(c) } }
            }

            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onCompose,
                    shape = CircleShape,
                    modifier = Modifier.height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp),
                ) {
                    Icon(Icons.Default.EditNote, null)
                    Spacer(Modifier.width(10.dp))
                    Text("写新信息", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ExpressiveCard(c: ConversationUi, onClick: () -> Unit) {
    val unread = c.unreadCount > 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = if (unread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = if (unread) 18.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AvatarView(c.avatar, shape = RoundedCornerShape(22.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (unread) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        c.time,
                        color = if (unread) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    c.preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (unread) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
