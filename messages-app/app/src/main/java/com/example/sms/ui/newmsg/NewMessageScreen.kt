package com.example.sms.ui.newmsg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sms.data.system.SystemContact
import com.example.sms.ui.common.colorForName
import com.example.sms.util.PhoneUtils

/* 1e — 新建短信：收件人 chips + 建议联系人（用系统键盘） */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    state: NewMessageUiState,
    hasContactsPermission: Boolean,
    onQueryChange: (String) -> Unit,
    onAddContact: (SystemContact) -> Unit,
    onAddRawNumber: (String) -> Unit,
    onRemoveRecipient: (Recipient) -> Unit,
    onRequestContacts: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("新对话") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                RecipientsField(
                    recipients = state.recipients,
                    query = state.query,
                    onQueryChange = onQueryChange,
                    onRemove = onRemoveRecipient,
                    onCommitRaw = onAddRawNumber,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (!hasContactsPermission) {
                Surface(
                    Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "允许读取联系人后可以按姓名搜索",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = onRequestContacts) { Text("允许") }
                    }
                }
            }

            Text(
                "建议联系人",
                Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(Modifier.weight(1f)) {
                items(state.suggestions, key = { it.id + it.phone }) { c ->
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(PhoneUtils.format(c.phone)) },
                        leadingContent = {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(colorForName(c.name)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    c.name.take(1), color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        },
                        modifier = Modifier.clickable { onAddContact(c) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (state.showRawNumberOption) {
                    item {
                        ListItem(
                            headlineContent = { Text("发送到 “" + state.query + "”") },
                            supportingContent = { Text("作为新号码") },
                            leadingContent = {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.PersonAdd, null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onAddRawNumber(state.query) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
                if (state.suggestions.isEmpty() && !state.showRawNumberOption && !state.loadingContacts) {
                    item {
                        Text(
                            "没有匹配的联系人，直接输入手机号即可",
                            Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                shape = CircleShape,
            ) { Text("开始对话") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecipientsField(
    recipients: List<Recipient>,
    query: String,
    onQueryChange: (String) -> Unit,
    onRemove: (Recipient) -> Unit,
    onCommitRaw: (String) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "收件人",
            Modifier.align(Alignment.CenterVertically),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        recipients.forEach { r ->
            InputChip(
                selected = true,
                onClick = { onRemove(r) },
                label = { Text(r.name) },
                avatar = {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(colorForName(r.name)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            r.name.take(1), color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                trailingIcon = { Icon(Icons.Default.Close, "移除", Modifier.size(16.dp)) },
            )
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.widthIn(min = 140.dp),
            singleLine = true,
            placeholder = { Text("姓名或号码") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { if (PhoneUtils.isLikelyPhone(query)) onCommitRaw(query) },
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}
