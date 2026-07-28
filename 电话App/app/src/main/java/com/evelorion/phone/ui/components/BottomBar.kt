package com.evelorion.phone.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.ui.Motion
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen

private data class NavItem(val icon: ImageVector, val label: String, val screen: Screen)

/**
 * Expressive 底部栏：药丸导航条 + 分离的加宽 FAB，
 * 选中指示器宽度带弹性动画。
 */
@Composable
fun BoxScope.PhoneBottomBar(state: PhoneState) {
    val items = listOf(
        NavItem(Icons.Outlined.History, "最近", Screen.Recents),
        NavItem(Icons.Outlined.Contacts, "联系人", Screen.Contacts),
        NavItem(Icons.Outlined.Star, "常用", Screen.Favorites)
    )
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(104.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.38f to scheme.surface,
                    1f to scheme.surface
                )
            )
            .padding(start = 14.dp, end = 14.dp, top = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(scheme.surfaceContainerHigh),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = state.screen == item.screen ||
                        (item.screen == Screen.Recents &&
                                (state.screen == Screen.Detail || state.screen == Screen.Search))
                val pillWidth by animateDpAsState(
                    if (selected) 64.dp else 48.dp, Motion.springy(), label = "pill"
                )
                val pillColor by animateColorAsState(
                    if (selected) scheme.primary.copy(alpha = 0.55f) else Color.Transparent,
                    Motion.emphasized(), label = "pillColor"
                )
                val fg = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.clickable(
                        remember { MutableInteractionSource() }, indication = null
                    ) { state.go(item.screen) }
                ) {
                    Box(
                        Modifier
                            .width(pillWidth)
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(pillColor),
                        contentAlignment = Alignment.Center
                    ) { Icon(item.icon, item.label, tint = fg, modifier = Modifier.size(23.dp)) }
                    Text(item.label, color = fg, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        MorphingSurface(
            Modifier.size(width = 76.dp, height = 72.dp),
            color = scheme.primary,
            restingCorner = 26.dp,
            pressedCorner = 16.dp,
            onClick = { state.go(Screen.Dialpad) }
        ) {
            Icon(
                Icons.Filled.Dialpad, "键盘",
                tint = scheme.onPrimary, modifier = Modifier.size(30.dp)
            )
        }
    }
}
