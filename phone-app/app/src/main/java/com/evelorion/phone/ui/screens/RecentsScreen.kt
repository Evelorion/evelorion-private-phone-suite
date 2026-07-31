package com.evelorion.phone.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.*
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen
import com.evelorion.phone.ui.components.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsScreen(state: PhoneState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sheetFor by remember { mutableStateOf<CallLog?>(null) }
    val scheme = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        LazyColumn(contentPadding = PaddingValues(top = 44.dp, bottom = 118.dp)) {
            item {
                Text(
                    "电话",
                    Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = scheme.onSurface
                )
            }
            item { SearchEntry(state) }

            PhoneData.calls.groupBy { it.group }.forEach { (group, calls) ->
                item(key = "header-$group") { SectionHeader(group) }
                items(calls, key = { it.id }) { call ->
                    CallRow(
                        call = call,
                        spamShield = state.spamShield,
                        onClick = { state.showCall(call.id, call.personId) },
                        onLongClick = { sheetFor = call },
                        onAction = { state.call(call.personId, call.displayNumber) }
                    )
                }
            }
        }
        PhoneBottomBar(state)
    }

    sheetFor?.let { call ->
        CallActionSheet(call = call, onDismiss = { sheetFor = null }, onCall = {
            sheetFor = null
            state.call(call.personId, call.displayNumber)
        }, onBlockedChanged = {
            state.blockedRevision++
            state.settingsStatus = state.settingsStatus.copy(
                blockedCount = com.evelorion.phone.data.BlockedNumberStore.all(context).size
            )
        })
    }
}

@Composable
private fun SearchEntry(state: PhoneState) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable { state.go(Screen.Search) }
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, "搜索", tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            "搜索通话记录与联系人",
            Modifier.weight(1f),
            color = scheme.onSurfaceVariant,
            fontSize = 15.sp
        )
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(scheme.primary)
                .clickable { state.go(Screen.Settings) },
            contentAlignment = Alignment.Center
        ) { Text("我", color = scheme.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    call: CallLog,
    spamShield: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val person = PhoneData.person(call.personId)
    val name = call.displayName ?: person?.name ?: "未知号码"
    val initial = call.displayInitial ?: person?.initial ?: "?"
    val bg = call.displayBg ?: person?.bg ?: scheme.surfaceContainerHigh
    val fg = call.displayFg ?: person?.fg ?: scheme.onSurface
    val missed = call.kind == CallKind.Missed

    val icon: ImageVector = when (call.kind) {
        CallKind.Incoming -> Icons.Filled.CallReceived
        CallKind.Outgoing -> Icons.Filled.CallMade
        CallKind.Missed -> Icons.Filled.CallMissed
        CallKind.Video -> Icons.Filled.Videocam
    }
    val meta = buildString {
        if (call.spam && spamShield) append("疑似骚扰 · ")
        append(call.kind.label)
        append(" · ").append(call.time)
        call.duration?.let { append(" · ").append(it) }
    }

    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(initial, bg, fg, corner = if (call.spam) 16.dp else 18.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (call.repeatCount > 1) "$name (${call.repeatCount})" else name,
                color = if (missed) scheme.error else scheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = if (missed) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(meta, color = scheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        RoundIconButton(
            icon = when {
                call.spam && spamShield -> Icons.Filled.Block
                call.kind == CallKind.Video -> Icons.Filled.Videocam
                else -> Icons.Filled.Call
            },
            contentDescription = "呼叫",
            bg = scheme.primaryContainer,
            fg = scheme.onPrimaryContainer,
            onClick = onAction
        )
    }
}
