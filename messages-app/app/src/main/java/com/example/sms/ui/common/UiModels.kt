package com.example.sms.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.sms.data.db.ConversationEntity
import com.example.sms.data.db.MsgCategory
import com.example.sms.util.PhoneUtils
import com.example.sms.util.TimeFormat

/** 头像：首字母圆片 或 图标圆片（对应设计稿两种样式） */
sealed interface Avatar {
    data class Initial(val text: String, val color: Color) : Avatar
    data class Symbol(val icon: ImageVector, val bg: Color, val fg: Color) : Avatar
}

/** 列表页用的展示模型 */
data class ConversationUi(
    val id: Long,
    val name: String,
    val address: String,
    val preview: String,
    val time: String,
    val unreadCount: Int,
    val avatar: Avatar,
    val isImagePreview: Boolean,
    val category: MsgCategory,
    val pinned: Boolean,
    val muted: Boolean,
    val draft: String,
)

private val avatarPalette = listOf(
    Color(0xFFB3261E), Color(0xFF7D5260), Color(0xFF6750A4), Color(0xFF386A20),
    Color(0xFF8C4A00), Color(0xFF4A4459), Color(0xFF00639B), Color(0xFF8E4585),
)

fun colorForName(name: String): Color {
    if (name.isEmpty()) return avatarPalette[0]
    val h = name.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF
    return avatarPalette[h % avatarPalette.size]
}

private fun iconForCategory(name: String, category: MsgCategory): ImageVector? = when {
    name.contains("快递") || name.contains("驿站") || name.contains("菜鸟") -> Icons.Default.LocalShipping
    name.contains("银行") || name.startsWith("95") || name.startsWith("955") -> Icons.Default.AccountBalance
    name.contains("物业") -> Icons.Default.Apartment
    name.contains("群") || name.contains("（") && name.contains("）") -> Icons.Default.Groups
    category == MsgCategory.PROMO -> Icons.Default.Campaign
    category == MsgCategory.TRANSACTION -> Icons.Default.AccountBalance
    else -> null
}

fun ConversationEntity.toUi(now: Long = System.currentTimeMillis()): ConversationUi {
    val isNumberName = displayName.filter { it.isDigit() }.length >= 5 || displayName.isBlank()
    val shownName = if (displayName.isBlank()) PhoneUtils.format(address) else displayName
    val icon = iconForCategory(shownName, category)
    val avatar = when {
        icon != null -> Avatar.Symbol(icon, colorForName(shownName).copy(alpha = 0.18f), colorForName(shownName))
        isNumberName -> Avatar.Symbol(Icons.Default.Person, colorForName(shownName), Color.White)
        else -> Avatar.Initial(shownName.take(1), colorForName(shownName))
    }
    return ConversationUi(
        id = threadId,
        name = shownName,
        address = address,
        preview = if (draft.isNotBlank()) "草稿：" + draft else snippet,
        time = TimeFormat.listStamp(lastTime, now),
        unreadCount = unreadCount,
        avatar = avatar,
        isImagePreview = snippetIsImage,
        category = category,
        pinned = pinned,
        muted = muted,
        draft = draft,
    )
}
