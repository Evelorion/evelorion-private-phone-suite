package com.example.sms.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.sms.ui.common.AvatarView

/* 1f — 搜索结果（关键词高亮 + 取件码/验证码抽取卡） */

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    TextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("搜索会话") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    if (state.hasQuery) {
                        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "清除") }
                    }
                }
            }
        }

        if (state.hasQuery) {
            item { Label("信息 · " + state.hits.size + " 条结果") }
            items(state.hits.size) { i ->
                val h = state.hits[i]
                ListItem(
                    headlineContent = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(h.sender, style = MaterialTheme.typography.titleMedium)
                            Text(
                                h.time, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    supportingContent = { Text(highlight(h.snippet, state.query), maxLines = 3) },
                    leadingContent = { AvatarView(h.avatar, size = 48.dp) },
                    modifier = Modifier.clickable { onOpenThread(h.threadId) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (state.noResults) {
                item {
                    Text(
                        "没有找到包含 “" + state.query + "” 的信息",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.extracted.isNotEmpty()) {
            item {
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            item { Label("从信息中提取") }
            items(state.extracted.size) { i ->
                val card = state.extracted[i]
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            card.code,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                card.label + " · " + card.sender,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (card.context.isNotBlank()) {
                                Text(
                                    card.context,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = { onCopyCode(card.code) }, shape = CircleShape) { Text("复制") }
                    }
                }
            }
        }

        if (!state.hasQuery && state.extracted.isEmpty()) {
            item {
                Text(
                    "输入关键词搜索所有短信内容",
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(
    text,
    Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val hlBg = MaterialTheme.colorScheme.primaryContainer
    val hlFg = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val i = text.indexOf(query, start, ignoreCase = true)
            if (i < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, i))
            withStyle(SpanStyle(background = hlBg, color = hlFg)) {
                append(text.substring(i, i + query.length))
            }
            start = i + query.length
        }
    }
}
