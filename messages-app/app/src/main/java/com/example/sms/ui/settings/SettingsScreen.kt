package com.example.sms.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sms.ui.theme.SEED_NONE
import com.example.sms.ui.theme.seedOptions
import com.example.sms.data.prefs.ListStyle
import com.example.sms.data.prefs.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onNotifications: (Boolean) -> Unit,
    onBlockSpam: (Boolean) -> Unit,
    onRcs: (Boolean) -> Unit,
    onListStyle: (ListStyle) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onSeedColor: (Int) -> Unit,
    onDeleteSystemSmsAfterImport: (Boolean) -> Unit,
    onSetDefaultApp: () -> Unit,
    onReimport: () -> Unit,
    onClearSystemSms: () -> Unit,
) {
    val s = state.settings
    var showClearSystemSmsDialog by rememberSaveable { mutableStateOf(false) }

    if (showClearSystemSmsDialog) {
        AlertDialog(
            onDismissRequest = { showClearSystemSmsDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, null) },
            title = { Text("清除系统短信？") },
            text = {
                Text("将删除 Android 系统短信库中的全部短信，其他短信 App 将无法再读取。本应用私密库中的短信不会删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSystemSmsDialog = false
                        onClearSystemSms()
                    },
                ) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearSystemSmsDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                if (!state.isDefaultSmsApp) {
                    Surface(
                        Modifier.fillMaxWidth().padding(16.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "尚未设为默认短信应用",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "设为默认后才能接收新短信并写入系统短信库。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )

                            // 资格自检：让用户直接看到系统认了几个必需组件
                            state.roleCheck?.let { rc ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    CheckLine("SENDTO Activity", rc.sendToActivity)
                                    CheckLine("快捷回复 Service", rc.respondService)
                                    CheckLine("SMS_DELIVER 接收器", rc.smsDeliver)
                                    CheckLine("WAP_PUSH_DELIVER 接收器", rc.wapPushDeliver)
                                    CheckLine("系统开放短信角色", rc.roleAvailable)
                                    Text(
                                        rc.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    rc.currentDefault?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            "当前默认：" + it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }

                            Button(onClick = onSetDefaultApp) { Text("设为默认短信应用") }
                        }
                    }
                } else {
                    ListItem(
                        headlineContent = { Text("已是默认短信应用") },
                        leadingContent = { Icon(Icons.Default.CheckCircle, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item { SectionLabel("通知与拦截") }
            item {
                ListItem(
                    headlineContent = { Text("通知与提醒") },
                    supportingContent = { Text("收到新短信时推送通知") },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    trailingContent = { Switch(checked = s.notificationsEnabled, onCheckedChange = onNotifications) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("骚扰拦截") },
                    supportingContent = { Text("本月已拦截 " + state.blockedThisMonth + " 条") },
                    leadingContent = { Icon(Icons.Default.Block, null) },
                    trailingContent = { Switch(checked = s.blockSpam, onCheckedChange = onBlockSpam) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item { SectionLabel("聊天") }
            item {
                ListItem(
                    headlineContent = { Text("RCS 标识（仅显示）") },
                    supportingContent = { Text("第三方应用不能发送 RCS 消息") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                    trailingContent = { Switch(checked = s.rcsEnabled, onCheckedChange = onRcs) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { SectionLabel("外观") }
            item {
                ListItem(
                    headlineContent = { Text("列表样式") },
                    supportingContent = {
                        Column(Modifier.padding(top = 8.dp)) {
                            SingleChoiceSegmentedButtonRow {
                                val options = listOf(
                                    ListStyle.LIST_1A to "标准",
                                    ListStyle.LIST_1B to "分类",
                                    ListStyle.LIST_1C to "卡片",
                                )
                                options.forEachIndexed { i, (style, label) ->
                                    SegmentedButton(
                                        selected = s.listStyle == style,
                                        onClick = { onListStyle(style) },
                                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                    ) { Text(label) }
                                }
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Default.ViewAgenda, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("深浅色") },
                    supportingContent = {
                        Column(Modifier.padding(top = 8.dp)) {
                            SingleChoiceSegmentedButtonRow {
                                val options = listOf(
                                    ThemeMode.SYSTEM to "跟随系统",
                                    ThemeMode.LIGHT to "浅色",
                                    ThemeMode.DARK to "深色",
                                )
                                options.forEachIndexed { i, (mode, label) ->
                                    SegmentedButton(
                                        selected = s.themeMode == mode,
                                        onClick = { onThemeMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                    ) { Text(label) }
                                }
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Default.DarkMode, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("动态取色") },
                    supportingContent = { Text("Android 12+ 跟随壁纸配色") },
                    leadingContent = { Icon(Icons.Default.ColorLens, null) },
                    trailingContent = { Switch(checked = s.dynamicColor, onCheckedChange = onDynamicColor) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                SeedColorPicker(
                    selected = s.seedColor,
                    overriddenByWallpaper = s.dynamicColor &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    onSelect = onSeedColor,
                )
            }

            item { SectionLabel("数据") }
            item {
                ListItem(
                    headlineContent = { Text("自动清理系统短信") },
                    supportingContent = {
                        Text("短信确认保存到本应用私密库后，再删除 Android 系统数据库中的副本")
                    },
                    leadingContent = { Icon(Icons.Default.Security, null) },
                    trailingContent = {
                        Switch(
                            checked = s.deleteSystemSmsAfterImport,
                            onCheckedChange = onDeleteSystemSmsAfterImport,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("重新导入系统短信") },
                    supportingContent = { Text("从手机短信数据库同步到本应用（按 ID 去重）") },
                    leadingContent = { Icon(Icons.Default.Sync, null) },
                    modifier = Modifier.clickable(onClick = onReimport),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("清除全部系统短信") },
                    supportingContent = {
                        Text(
                            state.systemCleanupMessage.ifBlank {
                                "只清除 Android 系统短信库，本应用中的短信保持不变"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                    trailingContent = {
                        if (state.clearingSystemSms) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    },
                    modifier = Modifier.clickable(enabled = !state.clearingSystemSms) {
                        showClearSystemSmsDialog = true
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                Text(
                    "所有会话、消息、草稿与设置都保存在本机应用私有目录（sms.db 与 DataStore 文件），不写缓存目录，重启不丢。",
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(
    text,
    Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
)

/**
 * 手动主题色选择器：选一个种子色，Material 3 调色算法会推导出整套配色，
 * Android 12 以下也能换色。选「无」则回到内置基线配色。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedColorPicker(
    selected: Int,
    overriddenByWallpaper: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Default.Palette, null,
                tint = if (overriddenByWallpaper) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurface,
            )
            Column {
                Text("主题色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (overriddenByWallpaper) "当前跟随壁纸，关掉动态取色后生效"
                    else "从种子色生成整套 Material 3 配色",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 「无」= 使用内置基线配色
            SeedSwatch(
                color = MaterialTheme.colorScheme.surfaceVariant,
                label = "无",
                isSelected = selected == SEED_NONE,
                dimmed = overriddenByWallpaper,
                checkTint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onSelect(SEED_NONE) },
            )
            seedOptions.forEach { option ->
                SeedSwatch(
                    color = option.color,
                    label = option.name,
                    isSelected = selected == option.argb,
                    dimmed = overriddenByWallpaper,
                    checkTint = Color.White,
                    onClick = { onSelect(option.argb) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SchemePreview()
    }
}

@Composable
private fun SeedSwatch(
    color: Color,
    label: String,
    isSelected: Boolean,
    dimmed: Boolean,
    checkTint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (dimmed) color.copy(alpha = 0.4f) else color)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) Icon(Icons.Default.Check, "已选中", Modifier.size(22.dp), tint = checkTint)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 实时预览当前生效的配色：切换种子色时能立刻看到整套色板的变化 */
@Composable
private fun SchemePreview() {
    val cs = MaterialTheme.colorScheme
    val swatches = listOf(
        "主色" to cs.primary,
        "主容器" to cs.primaryContainer,
        "次色" to cs.secondaryContainer,
        "第三色" to cs.tertiaryContainer,
        "表面" to cs.surfaceContainerHigh,
    )
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("预览", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                swatches.forEach { (name, c) ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(c)
                                .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
                        )
                        Text(name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 自检的一行：✓ / ✕ + 名称 */
@Composable
private fun CheckLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
            null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
