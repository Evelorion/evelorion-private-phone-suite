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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.FavoriteOrderStore
import com.evelorion.phone.data.Person
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.components.PhoneBottomBar
import com.evelorion.phone.ui.components.RoundIconButton

private data class FavoriteColor(val background: Color, val foreground: Color)

private val FavoriteColors = listOf(
    FavoriteColor(Color(0xFFEADDFF), Color(0xFF21005D)),
    FavoriteColor(Color(0xFFD7E3FF), Color(0xFF001B3D)),
    FavoriteColor(Color(0xFFFFD8E4), Color(0xFF31111D)),
    FavoriteColor(Color(0xFFFFDDB3), Color(0xFF2B1700)),
    FavoriteColor(Color(0xFFE0E0EC), Color(0xFF1A1B22)),
)

@Composable
fun FavoritesScreen(state: PhoneState) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val sourceFavorites = PhoneData.people.filter { it.favorite }
    val favoriteIds = sourceFavorites.map { it.id }
    var orderedIds by remember { mutableStateOf(emptyList<String>()) }
    var reordering by remember { mutableStateOf(false) }
    val peopleById = sourceFavorites.associateBy { it.id }
    val favorites = orderedIds.mapNotNull(peopleById::get)
    val family = PhoneData.people.filter { it.family }

    LaunchedEffect(favoriteIds) {
        orderedIds = FavoriteOrderStore.resolve(context, favoriteIds)
    }

    fun move(index: Int, offset: Int) {
        val target = index + offset
        if (index !in orderedIds.indices || target !in orderedIds.indices) return
        orderedIds = orderedIds.toMutableList().apply {
            add(target, removeAt(index))
        }
        FavoriteOrderStore.save(context, orderedIds)
    }

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 44.dp, start = 16.dp, end = 16.dp, bottom = 118.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "常用",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineLarge,
                    color = scheme.onSurface,
                )
                if (favorites.size > 1) {
                    IconButton(onClick = { reordering = !reordering }) {
                        Icon(
                            if (reordering) Icons.Filled.Done else Icons.Filled.SwapVert,
                            contentDescription = if (reordering) "完成排序" else "重新排序",
                            tint = scheme.primary,
                        )
                    }
                }
            }

            val big = favorites.firstOrNull()
            val rest = if (state.pinFavorites) favorites.drop(1) else favorites
            if (state.pinFavorites && big != null) {
                FavoriteCard(
                    person = big,
                    subtitle = PhoneData.favoriteSubtitles[big.id].orEmpty(),
                    color = FavoriteColors[0],
                    height = 196.dp,
                    corner = 32.dp,
                    avatarSize = 64.dp,
                    nameSize = 24,
                    reordering = reordering,
                    canMoveUp = false,
                    canMoveDown = favorites.size > 1,
                    modifier = Modifier.fillMaxWidth(),
                    onMoveUp = {},
                    onMoveDown = { move(0, 1) },
                    onCall = { state.call(big.id) },
                    onClick = {
                        state.selectedId = big.id
                        state.go(com.evelorion.phone.ui.Screen.Detail)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            rest.chunked(2).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { columnIndex, person ->
                        val index = rowIndex * 2 + columnIndex + 1
                        FavoriteCard(
                            person = person,
                            subtitle = PhoneData.favoriteSubtitles[person.id].orEmpty(),
                            color = FavoriteColors[index % FavoriteColors.size],
                            height = 162.dp,
                            corner = 28.dp,
                            avatarSize = 52.dp,
                            nameSize = 19,
                            reordering = reordering,
                            canMoveUp = index > 0,
                            canMoveDown = index < favorites.lastIndex,
                            modifier = Modifier.weight(1f),
                            onMoveUp = { move(index, -1) },
                            onMoveDown = { move(index, 1) },
                            onCall = { state.call(person.id) },
                            onClick = {
                                state.selectedId = person.id
                                state.go(com.evelorion.phone.ui.Screen.Detail)
                            },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (family.isNotEmpty()) {
                Text(
                    "家庭群组",
                    Modifier.padding(top = 14.dp, bottom = 8.dp),
                    color = scheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(scheme.surfaceContainer)
                        .padding(vertical = 8.dp)
                ) {
                    family.forEach { person ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    state.selectedId = person.id
                                    state.go(com.evelorion.phone.ui.Screen.Detail)
                                }
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                person.initial,
                                person.bg,
                                person.fg,
                                size = 44.dp,
                                corner = 15.dp,
                                fontSize = 17,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    person.name,
                                    color = scheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    PhoneData.familySubtitles[person.id] ?: person.number,
                                    color = scheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                            RoundIconButton(
                                Icons.Filled.Call,
                                "呼叫",
                                bg = scheme.primaryContainer,
                                fg = scheme.onPrimaryContainer,
                            ) { state.call(person.id) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {}
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .border(
                                    1.dp,
                                    scheme.primary.copy(alpha = 0.45f),
                                    RoundedCornerShape(15.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.GroupAdd, null, tint = scheme.primary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "邀请家人加入群组通话",
                            color = scheme.primary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
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
    color: FavoriteColor,
    height: Dp,
    corner: Dp,
    avatarSize: Dp,
    nameSize: Int,
    reordering: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCall: () -> Unit,
    onClick: () -> Unit,
) {
    MorphingSurface(
        modifier.height(height),
        color = color.background,
        restingCorner = corner,
        pressedCorner = 18.dp,
        onClick = { if (!reordering) onClick() },
    ) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Avatar(
                    person.initial,
                    Color.White.copy(alpha = 0.7f),
                    color.foreground,
                    size = avatarSize,
                    corner = avatarSize / 3,
                    fontSize = nameSize + 2,
                )
                if (reordering) {
                    Row {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                "向前移动",
                                tint = color.foreground.copy(alpha = if (canMoveUp) 0.85f else 0.25f),
                            )
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                "向后移动",
                                tint = color.foreground.copy(alpha = if (canMoveDown) 0.85f else 0.25f),
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onCall) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = "呼叫",
                            tint = color.foreground.copy(alpha = 0.75f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Column {
                Text(
                    person.name,
                    color = color.foreground,
                    fontSize = nameSize.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = color.foreground.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
