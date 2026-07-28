package com.evelorion.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.Person
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.components.PhoneBottomBar
import com.evelorion.phone.ui.components.RoundIconButton
import com.evelorion.phone.ui.theme.CallGreenContainer
import com.evelorion.phone.ui.theme.OnCallGreenContainer

/** 常用 / 家人：大卡宫格 + 家庭群组 */
@Composable
fun FavoritesScreen(state: PhoneState) {
    val scheme = MaterialTheme.colorScheme
    val favorites = PhoneData.people.filter { it.favorite }
    val family = PhoneData.people.filter { it.family }

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 118.dp)
        ) {
            Text(
                "常用", Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.headlineLarge, color = scheme.onSurface
            )
            Text(
                "轻点头像直接拨出 · 长按可重新排序",
                Modifier.padding(bottom = 16.dp), color = scheme.onSurfaceVariant, fontSize = 14.sp
            )

            val big = favorites.firstOrNull()
            val rest = favorites.drop(1)
            if (state.pinFavorites && big != null) {
                FavoriteCard(
                    person = big, subtitle = PhoneData.favoriteSubtitles[big.id] ?: "",
                    icon = Icons.Filled.Favorite, height = 196.dp, corner = 32.dp,
                    avatarSize = 64.dp, nameSize = 24, modifier = Modifier.fillMaxWidth()
                ) { state.call(big.id) }
                Spacer(Modifier.height(12.dp))
            }
            rest.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { p ->
                        FavoriteCard(
                            person = p, subtitle = PhoneData.favoriteSubtitles[p.id] ?: "",
                            icon = when (p.id) {
                                "chen" -> Icons.Filled.Videocam
                                "li" -> Icons.Filled.PushPin
                                else -> Icons.Filled.Call
                            },
                            height = 162.dp, corner = 28.dp, avatarSize = 52.dp, nameSize = 19,
                            modifier = Modifier.weight(1f)
                        ) { state.call(p.id) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            // 通讯录里没有「家人」分组时，整块不显示。
            // 摆一个空盒子会让人以为功能坏了，而实际上只是还没建分组。
            if (family.isNotEmpty()) {
            Text(
                "家庭群组",
                Modifier.padding(top = 14.dp, bottom = 8.dp),
                color = scheme.primary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(scheme.surfaceContainer).padding(vertical = 8.dp)
            ) {
                family.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { state.call(p.id) }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.initial, p.bg, p.fg, size = 44.dp, corner = 15.dp, fontSize = 17)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                PhoneData.familySubtitles[p.id] ?: p.number,
                                color = scheme.onSurfaceVariant, fontSize = 13.sp
                            )
                        }
                        RoundIconButton(
                            Icons.Filled.Call, "呼叫",
                            bg = CallGreenContainer, fg = OnCallGreenContainer
                        ) { state.call(p.id) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable {}.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                            .border(1.dp, scheme.primary.copy(alpha = 0.45f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.GroupAdd, null, tint = scheme.primary) }
                    Spacer(Modifier.width(14.dp))
                    Text("邀请家人加入群组通话", color = scheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            }
        }
        PhoneBottomBar(state)
    }
}

@Composable
private fun FavoriteCard(
    person: Person,
    subtitle: String,
    icon: ImageVector,
    height: Dp,
    corner: Dp,
    avatarSize: Dp,
    nameSize: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    MorphingSurface(
        modifier.height(height),
        color = person.bg,
        restingCorner = corner,
        pressedCorner = 18.dp,
        onClick = onClick
    ) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Avatar(
                    person.initial, Color.White.copy(alpha = 0.7f), person.fg,
                    size = avatarSize, corner = avatarSize / 3, fontSize = nameSize + 2
                )
                Icon(icon, null, tint = person.fg.copy(alpha = 0.75f), modifier = Modifier.size(22.dp))
            }
            Column {
                Text(person.name, color = person.fg, fontSize = nameSize.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = person.fg.copy(alpha = 0.72f), fontSize = 13.sp)
            }
        }
    }
}
