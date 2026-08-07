package com.evelorion.phone.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.ui.Motion

/** M3 Expressive 的超椭圆式头像：方形圆角而非圆形 */
@Composable
fun Avatar(
    initial: String,
    bg: Color,
    fg: Color,
    size: Dp = 48.dp,
    corner: Dp = 16.dp,
    fontSize: Int = 19
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Medium)
    }
}

/** 按下时圆角收缩的容器 —— Expressive 的核心形态反馈 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MorphingSurface(
    modifier: Modifier = Modifier,
    color: Color,
    restingCorner: Dp,
    pressedCorner: Dp = restingCorner / 2,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) pressedCorner else restingCorner,
        Motion.springy(),
        label = "corner"
    )
    val bg by animateColorAsState(color, Motion.emphasized(), label = "bg")
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .then(
                if (onClick != null)
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                else Modifier
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    bg: Color,
    fg: Color,
    size: Dp = 42.dp,
    corner: Dp = 14.dp,
    iconSize: Dp = 21.dp,
    onClick: () -> Unit
) {
    MorphingSurface(
        Modifier.size(size),
        color = bg,
        restingCorner = corner,
        onClick = onClick
    ) {
        Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp
    )
}

/** 状态栏占位（真实项目里由 WindowInsets 处理，这里给设计稿一致的 44dp 顶部空间） */
@Composable
fun StatusBarSpacer(height: Dp = 44.dp) = Spacer(Modifier.height(height))
