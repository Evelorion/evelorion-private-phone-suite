package com.evelorion.phone.ui

import androidx.compose.ui.graphics.Color
import com.evelorion.phone.telecom.CallManager

/**
 * 通话对方在界面上的样子。
 *
 * ZIP 里的设计稿用的是示例数据里现成的 bg/fg/initial，接真实通话之后
 * 这些都得现算 —— 来电只给一个号码，名字要查、首字母要取、颜色要挑。
 *
 * 配色规则和通讯录里的头像保持一致（按稳定哈希取模），
 * 这样同一个人在两个 App 里颜色相同。不一致的话用户会以为是两个人。
 */
data class CallPeer(
    val name: String,
    val number: String,
    val initial: String,
    val bg: Color,
    val fg: Color,
) {
    /** 号码归属地。没有离线号码库，暂时留空，界面上会自动省掉这一段。 */
    val city: String get() = ""
}

private val AvatarBg = listOf(
    Color(0xFFEADDFF), Color(0xFFC4EED0), Color(0xFFFFD8E4), Color(0xFFD7E3FF),
    Color(0xFFFFDDB3), Color(0xFFD3E8E4), Color(0xFFF2DDE1), Color(0xFFE0E0EC),
)
private val AvatarFg = listOf(
    Color(0xFF21005D), Color(0xFF07361C), Color(0xFF31111D), Color(0xFF001B3D),
    Color(0xFF2B1700), Color(0xFF00201C), Color(0xFF31101B), Color(0xFF1A1B22),
)

/** 当前通话的对方。没有通话时返回一个占位，界面不会因此崩。 */
fun CallManager.peer(): CallPeer {
    val displayName = callerName.ifBlank { number.ifBlank { "未知号码" } }
    return CallPeer(
        name = displayName,
        number = number,
        initial = initialOf(displayName),
        bg = AvatarBg[colorIndex(displayName)],
        fg = AvatarFg[colorIndex(displayName)],
    )
}

/**
 * 取首字母。
 *
 * 中文取第一个字，英文取首字母大写，号码也取开头字符。
 */
private fun initialOf(text: String): String {
    val first = text.firstOrNull() ?: return "?"
    return first.uppercase()
}

/** 稳定哈希：同一个名字每次都得到同一个颜色，重启 App 也不变。 */
private fun colorIndex(text: String): Int {
    var h = 0
    for (c in text) h = h * 31 + c.code
    return ((h % AvatarBg.size) + AvatarBg.size) % AvatarBg.size
}
