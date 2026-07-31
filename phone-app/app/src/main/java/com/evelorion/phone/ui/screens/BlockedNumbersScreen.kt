package com.evelorion.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.BlockedNumberStore
import com.evelorion.phone.ui.PhoneState

@Composable
fun BlockedNumbersScreen(state: PhoneState) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val entries = remember(state.blockedRevision) { BlockedNumberStore.all(context) }
    var showAdd by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(scheme.surfaceContainer.copy(alpha = 0.6f))) {
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 44.dp, bottom = 112.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { state.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                    Text("拦截的号码", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (entries.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Block, null, Modifier.size(40.dp), tint = scheme.onSurfaceVariant)
                        Text("还没有拦截号码", fontWeight = FontWeight.Medium)
                        Text("从最近通话、号码详情或右下角添加", color = scheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
            } else {
                items(entries, key = { it.number }) { entry ->
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            .fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(scheme.surface).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Block, null, tint = scheme.error)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.label.ifBlank { entry.number }, fontWeight = FontWeight.Medium)
                            if (entry.label.isNotBlank()) {
                                Text(entry.number, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                        IconButton(onClick = {
                            BlockedNumberStore.remove(context, entry.number)
                            state.blockedRevision++
                            state.settingsStatus = state.settingsStatus.copy(
                                blockedCount = BlockedNumberStore.all(context).size
                            )
                        }) {
                            Icon(Icons.Filled.Delete, "解除拦截", tint = scheme.error)
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, "添加拦截号码")
        }
    }

    if (showAdd) {
        AddBlockedNumberDialog(
            onDismiss = { showAdd = false },
            onAdd = { number, label ->
                if (BlockedNumberStore.add(context, number, label)) {
                    state.blockedRevision++
                    state.settingsStatus = state.settingsStatus.copy(
                        blockedCount = BlockedNumberStore.all(context).size
                    )
                    showAdd = false
                }
            },
        )
    }
}

@Composable
private fun AddBlockedNumberDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var number by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加拦截号码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("电话号码") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(number, label) }, enabled = number.any(Char::isDigit)) {
                Text("添加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
