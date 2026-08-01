package com.evelorion.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Diversity1
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Quickreply
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen

@Composable
fun SettingsScreen(
    state: PhoneState,
    status: SettingsStatus = SettingsStatus(),
    onRequestDialerRole: () -> Unit = {},
    onRequestCallScreeningRole: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onSyncCalls: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(scheme.surfaceContainer.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState()).padding(top = 44.dp, bottom = 40.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = scheme.onSurface)
            }
            Text("设置", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        }

        // ── 真实状态区 ───────────────────────────────────────────
        //
        // 这三条是设计稿里没有的，但必须加：它们回答的是用户
        // 「为什么这个 App 好像没在工作」的疑问。
        // 没有这一块，来电界面不出现、通话记录不同步时，
        // 用户完全没有线索，只能猜。
        SettingsGroup("状态") {
            StatusRow(
                icon = Icons.Filled.VerifiedUser,
                title = "默认电话应用",
                ok = status.isDefaultDialer,
                okText = "已设为默认，来电界面由本应用接管",
                badText = "还不是默认电话应用 —— 来电和通话界面仍然是系统的，点这里去设置",
                onClick = { onRequestDialerRole() },
            )
            StatusRow(
                icon = Icons.Filled.Star,
                title = "同步账号",
                ok = status.vaultUsable,
                okText = status.vaultMessage,
                badText = status.vaultMessage,
                onClick = { onOpenContacts() },
            )
            StatusRow(
                icon = Icons.Filled.Block,
                title = "来电拦截",
                ok = status.isCallScreeningEnabled,
                okText = "系统已允许本应用拦截名单中的号码",
                badText = "尚未取得系统来电筛选角色，点这里开启",
                onClick = onRequestCallScreeningRole,
            )
            LinkRow(
                Icons.Filled.Quickreply, "通话记录",
                if (status.callSyncError.isNotBlank()) "${status.callCount} 条 · ${status.callSyncError}"
                else if (status.pendingCount > 0) "${status.callCount} 条 · 还有 ${status.pendingCount} 条待上传，点此立即同步"
                else "${status.callCount} 条 · 已全部同步",
                onClick = onSyncCalls,
            )
        }

        SettingsGroup("来电与骚扰") {
            SwitchRow(
                Icons.Filled.VerifiedUser,
                "启用号码拦截",
                "自动拒接本地拦截名单中的来电",
                state.spamShield,
            ) {
                state.spamShield = it
                com.evelorion.phone.data.BlockedNumberStore.setEnabled(context, it)
            }
            SwitchRow(Icons.Filled.LocationOn, "归属地显示", "在来电界面显示城市", state.showCity) { state.showCity = it }
            LinkRow(
                Icons.Filled.Block,
                "拦截的号码",
                if (status.blockedCount > 0) "${status.blockedCount} 个号码" else "还没有拦截号码",
                onClick = { state.go(Screen.BlockedNumbers) },
            )
        }
        SettingsGroup("常用联系人") {
            SwitchRow(Icons.Filled.Star, "置顶常用联系人", "在「常用」首屏显示大卡片", state.pinFavorites) { state.pinFavorites = it }
            LinkRow(Icons.Filled.Diversity1, "家庭群组",
                if (status.familyCount > 0) "${status.familyCount} 位成员 · 在通讯录里管理"
                else "在通讯录里建一个叫「家人」的分组即可")
        }
        SettingsGroup("通话") {
            LinkRow(
                Icons.Filled.FiberManualRecord,
                "通话录音",
                "通话中手动开始；文件保存在系统 Recordings/Evelorion",
                onClick = { state.go(Screen.Recordings) },
            )
            LinkRow(Icons.Filled.Notifications, "铃声与振动", "“波纹” · 中等振动")
            LinkRow(Icons.Filled.Quickreply, "快捷回复", "4 条模板")
        }
    }
}

@Composable
private fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.padding(12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(30.dp)).background(scheme.surface).padding(vertical = 6.dp)
    ) {
        Text(
            label,
            Modifier.padding(start = 22.dp, top = 14.dp, bottom = 6.dp),
            color = scheme.primary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp
        )
        content()
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = scheme.onSurface, fontSize = 16.sp)
            Text(subtitle, color = scheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = scheme.onSurface, fontSize = 16.sp)
            Text(subtitle, color = scheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = scheme.outline)
    }
}

/** 设置页要显示的真实状态。默认值全是「不可用」，这样忘了传就会明显看出来。 */
data class SettingsStatus(
    val isDefaultDialer: Boolean = false,
    val isCallScreeningEnabled: Boolean = false,
    val vaultUsable: Boolean = false,
    val vaultMessage: String = "尚未连接通讯录",
    val callCount: Int = 0,
    val pendingCount: Int = 0,
    val callSyncError: String = "",
    val familyCount: Int = 0,
    val blockedCount: Int = 0,
)

/**
 * 带状态色的一行。
 *
 * 正常时用普通前景色，异常时用 error 色 —— 不是为了好看，
 * 是因为「没设成默认电话应用」和「已设好」这两种状态，
 * 用同样的灰色小字写出来，用户根本不会注意到区别。
 */
@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !ok, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (ok) scheme.primary else scheme.error, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                if (ok) okText else badText,
                color = if (ok) scheme.onSurfaceVariant else scheme.error,
                fontSize = 12.sp,
            )
        }
        if (!ok) Icon(Icons.Filled.ChevronRight, null, tint = scheme.error)
    }
}
