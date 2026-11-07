package com.evelorion.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.telecom.SimCard

/**
 * 通话界面上「这通电话在哪张卡上」的小标签。
 *
 * 来电和通话中两个界面都是深色背景，所以 [onDark] 默认 true；
 * 浅色界面传 false 会用深色文字。
 */
@Composable
fun SimBadge(
    sim: SimCard,
    modifier: Modifier = Modifier,
    onDark: Boolean = true,
) {
    val background = if (onDark) Color(0x33FFFFFF) else Color(0x14000000)
    val foreground = if (onDark) Color(0xFFE3EFF9) else Color(0xFF3C4A57)
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(simTint(sim)))
        Spacer(Modifier.width(6.dp))
        Text(sim.label, color = foreground, fontSize = 11.sp, letterSpacing = 0.3.sp)
    }
}

/** SIM 卡自带的颜色（系统设置里给双卡配的那个），取不到就按卡槽分色。 */
fun simTint(sim: SimCard): Color =
    if (sim.tint != 0) Color(sim.tint)
    else if (sim.slotIndex > 0) Color(0xFF7FB3FF)
    else Color(0xFF8BD6A8)
