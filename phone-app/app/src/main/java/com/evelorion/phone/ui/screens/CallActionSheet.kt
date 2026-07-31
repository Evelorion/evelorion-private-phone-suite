package com.evelorion.phone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.CallLog
import com.evelorion.phone.data.PhoneData

/** 长按通话记录弹出的操作菜单 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallActionSheet(
    call: CallLog,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onBlockedChanged: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val person = PhoneData.person(call.personId)
    val name = call.displayName ?: person?.name ?: "未知号码"
    val number = call.displayNumber ?: person?.number ?: ""
    val blocked = remember(number) {
        number.isNotBlank() && com.evelorion.phone.data.BlockedNumberStore.isBlocked(context, number)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainer,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                Text(number, color = scheme.onSurfaceVariant, fontSize = 14.sp)
            }
            SheetAction(Icons.Filled.Call, "呼叫", scheme.onSurface, onCall)
            SheetAction(Icons.Filled.Message, "发送信息", scheme.onSurface, onDismiss)
            SheetAction(Icons.Filled.PersonAdd, "添加到联系人", scheme.onSurface, onDismiss)
            SheetAction(Icons.Filled.ContentCopy, "复制号码", scheme.onSurface, onDismiss)
            SheetAction(
                Icons.Filled.Block,
                if (blocked) "解除拦截" else "拦截这个号码",
                scheme.error,
            ) {
                if (blocked) {
                    com.evelorion.phone.data.BlockedNumberStore.remove(context, number)
                } else {
                    com.evelorion.phone.data.BlockedNumberStore.add(context, number, name)
                }
                onBlockedChanged()
                onDismiss()
            }
            SheetAction(Icons.Filled.Delete, "删除这条记录", scheme.error) {
                // 打删除标记而不是直接删行：这条记录可能已经同步到服务器和
                // 其它设备上了，只删本地的话下次同步又会被拉回来。
                // 墓碑推上去之后，所有设备上才会真正消失。
                PhoneData.deleteCall(context, call.id)
                onDismiss()
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}
