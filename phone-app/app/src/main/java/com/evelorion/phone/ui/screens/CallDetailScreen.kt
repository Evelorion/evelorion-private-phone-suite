package com.evelorion.phone.ui.screens

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.theme.CallGreen

@Composable
fun CallDetailScreen(state: PhoneState) {
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

    // 历史记录要按这个人筛一遍。以前 PhoneData.history 从来没人填，
    // 详情页的历史区永远是空的 —— 而界面看不出「空」和「还没加载」的区别。
    androidx.compose.runtime.LaunchedEffect(state.selectedId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { PhoneData.historyFor(state.selectedId, number) }
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
            IconButton(onClick = {}) { Icon(Icons.Filled.StarBorder, "收藏", tint = scheme.onSurface) }
            IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, "更多", tint = scheme.onSurface) }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(initial, bg, fg, size = 104.dp, corner = 34.dp, fontSize = 40)
            Text(name, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
            Text("$number · 中国移动", color = scheme.onSurfaceVariant, fontSize = 15.sp)
            if (state.showCity) {
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
            DetailAction(Modifier.weight(1f), Icons.Filled.Block, "拦截", scheme.errorContainer, scheme.onErrorContainer) {}
        }

        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(32.dp)).background(scheme.surface).padding(vertical = 12.dp)
        ) {
            Text(
                "通话历史",
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
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon, null,
                        tint = if (h.missed) scheme.error else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.kind, color = scheme.onSurface, fontSize = 15.sp)
                        Text(h.when_, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text(h.duration, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

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
