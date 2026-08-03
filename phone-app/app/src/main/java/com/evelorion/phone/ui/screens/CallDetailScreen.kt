package com.evelorion.phone.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.bridge.ContactsBridge
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.data.BlockedNumberStore
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.theme.CallGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CallDetailScreen(state: PhoneState) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val person = PhoneData.person(state.selectedId)
    val call = PhoneData.calls.firstOrNull { it.id == state.selectedCallId }
    val name = person?.name ?: call?.displayName ?: "未知号码"
    val number = person?.number ?: call?.displayNumber ?: ""
    val initial = person?.initial ?: call?.displayInitial ?: "?"
    val bg = person?.bg ?: call?.displayBg ?: scheme.surfaceContainerHigh
    val fg = person?.fg ?: call?.displayFg ?: scheme.onSurface
    // 没有离线号码库，归属地查不到 —— 写「未知归属地」是诚实的，
    // 但设计稿里那行显示的是城市，这里留空让它自然消失。
    val city = person?.city ?: call?.displayCity ?: ""
    val blocked = remember(number, state.blockedRevision) {
        number.isNotBlank() && BlockedNumberStore.isBlocked(context, number)
    }
    val favorite = person?.favorite == true
    val scope = rememberCoroutineScope()
    var favoriteBusy by remember(person?.id) { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }

    // 历史记录要按这个人筛一遍。以前 PhoneData.history 从来没人填，
    // 详情页的历史区永远是空的 —— 而界面看不出「空」和「还没加载」的区别。
    androidx.compose.runtime.LaunchedEffect(state.selectedId, state.selectedCallId, number) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { PhoneData.historyFor(state.selectedId, number, state.selectedCallId) }
        }
    }

    Column(
        Modifier.fillMaxSize().background(scheme.surfaceContainer.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState()).padding(top = 44.dp, bottom = 40.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = scheme.onSurface)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                enabled = !favoriteBusy,
                onClick = {
                    val contactId = person?.id?.toIntOrNull()
                    if (contactId == null) {
                        Toast.makeText(context, "请先把这个号码添加到通讯录，再收藏", Toast.LENGTH_LONG).show()
                    } else {
                        favoriteBusy = true
                        scope.launch {
                            val changed = withContext(Dispatchers.IO) {
                                ContactsBridge.setFavorite(context, contactId, !favorite)
                            }
                            if (changed) {
                                withContext(Dispatchers.IO) { PhoneData.refreshContacts(context) }
                            }
                            Toast.makeText(
                                context,
                                when {
                                    !changed -> "收藏修改失败，请确认通讯录已解锁"
                                    favorite -> "已取消收藏"
                                    else -> "已添加到常用"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                            favoriteBusy = false
                        }
                    }
                },
            ) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) scheme.primary else scheme.onSurface,
                )
            }
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, "更多", tint = scheme.onSurface)
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (person == null) "添加联系人" else "已在通讯录") },
                        leadingIcon = { Icon(Icons.Filled.PersonAdd, null) },
                        enabled = person == null && number.isNotBlank(),
                        onClick = {
                            moreExpanded = false
                            val suggestedName = name.takeIf {
                                it.isNotBlank() && it != number && it != "未知号码"
                            }.orEmpty()
                            openPrivateContactEditor(context, number, suggestedName)
                        },
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(initial, bg, fg, size = 104.dp, corner = 34.dp, fontSize = 40)
            Text(name, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
            if (number.isNotBlank() && number != name) {
                Text(number, color = scheme.onSurfaceVariant, fontSize = 15.sp)
            }
            if (state.showCity && city.isNotBlank()) {
                Box(
                    Modifier.padding(top = 10.dp).height(32.dp)
                        .clip(RoundedCornerShape(16.dp)).background(scheme.secondaryContainer)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text(city, color = scheme.onSecondaryContainer, fontSize = 13.sp) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailAction(Modifier.weight(1f), Icons.Filled.Call, "通话", CallGreen, Color.White) {
                state.call(state.selectedId, number)
            }
            DetailAction(Modifier.weight(1f), Icons.AutoMirrored.Filled.Message, "信息", scheme.secondaryContainer, scheme.onSecondaryContainer) {}
            DetailAction(Modifier.weight(1f), Icons.Filled.Videocam, "视频", scheme.secondaryContainer, scheme.onSecondaryContainer) {}
            DetailAction(
                Modifier.weight(1f),
                Icons.Filled.Block,
                if (blocked) "解除" else "拦截",
                scheme.errorContainer,
                scheme.onErrorContainer,
            ) {
                if (blocked) {
                    BlockedNumberStore.remove(context, number)
                    state.blockedRevision++
                } else {
                    confirmBlock = true
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(32.dp)).background(scheme.surface).padding(vertical = 12.dp)
        ) {
            Text(
                if (state.selectedCallId != null) "本次通话与历史记录" else "通话历史",
                Modifier.padding(start = 20.dp, bottom = 8.dp),
                color = scheme.primary, fontSize = 14.sp
            )
            PhoneData.history.forEach { h ->
                val icon: ImageVector = when {
                    h.kind.startsWith("来电") -> Icons.Filled.CallReceived
                    h.kind.startsWith("拨出") -> Icons.Filled.CallMade
                    h.missed -> Icons.Filled.CallMissed
                    else -> Icons.Filled.Star
                }
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (h.selected) scheme.primaryContainer.copy(alpha = 0.72f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon, null,
                        tint = if (h.missed) scheme.error else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.kind, color = scheme.onSurface, fontSize = 15.sp)
                            if (h.selected) {
                                Text(
                                    "本次",
                                    Modifier.padding(start = 8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(scheme.primary)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                    color = scheme.onPrimary,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Text(h.date, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(h.timeRange, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text(
                        h.duration,
                        color = if (h.missed) scheme.error else scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("拦截这个号码？") },
            text = { Text("$number\n之后的来电会被系统自动拒接，并保留在通话记录中。") },
            confirmButton = {
                Button(onClick = {
                    BlockedNumberStore.add(context, number, name)
                    state.blockedRevision++
                    confirmBlock = false
                }) { Text("确认拦截") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 打开配套加密通讯录的新建页，并预填当前陌生号码。
 *
 * EditActivity 在通讯录侧受 signature 权限保护，第三方 App 即使知道组件名也打不开。
 */
private fun openPrivateContactEditor(context: Context, number: String, suggestedName: String) {
    val opened = listOf("com.evelorion.contacts", "com.evelorion.contacts.debug").any { packageName ->
        runCatching {
            context.startActivity(
                Intent(ACTION_CREATE_PRIVATE_CONTACT)
                    .setClassName(packageName, CONTACT_EDITOR_CLASS)
                    .putExtra(EXTRA_PHONE, number)
                    .putExtra(EXTRA_NAME, suggestedName),
            )
            true
        }.getOrDefault(false)
    }
    if (!opened) {
        Toast.makeText(context, "请先安装或更新配套通讯录 App", Toast.LENGTH_LONG).show()
    }
}

private const val ACTION_CREATE_PRIVATE_CONTACT =
    "com.evelorion.contacts.action.CREATE_PRIVATE_CONTACT"
private const val CONTACT_EDITOR_CLASS = "com.evelorion.contacts.ui.edit.EditActivity"
private const val EXTRA_PHONE = "prefill_phone"
private const val EXTRA_NAME = "prefill_name"

@Composable
private fun DetailAction(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    bg: Color,
    fg: Color,
    onClick: () -> Unit
) {
    MorphingSurface(modifier.height(76.dp), color = bg, restingCorner = 24.dp, pressedCorner = 16.dp, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(23.dp))
            Text(label, color = fg, fontSize = 12.sp)
        }
    }
}
