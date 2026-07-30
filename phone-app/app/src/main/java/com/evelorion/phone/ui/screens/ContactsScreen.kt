package com.evelorion.phone.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.bridge.ContactsBridge
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen
import com.evelorion.phone.ui.components.*
import com.evelorion.phone.ui.theme.CallGreenContainer
import com.evelorion.phone.ui.theme.OnCallGreenContainer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(state: PhoneState) {
    val scheme = MaterialTheme.colorScheme
    val letters = PhoneData.people.map { it.letter }.distinct().sorted()

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        LazyColumn(contentPadding = PaddingValues(top = 44.dp, bottom = 118.dp)) {
            item {
                Text(
                    "联系人",
                    Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = scheme.onSurface
                )
            }
            item {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth().height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { state.go(Screen.Search) }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("搜索 ${PhoneData.people.size} 位联系人", color = scheme.onSurfaceVariant, fontSize = 15.sp)
                }
            }
            if (PhoneData.people.isEmpty()) {
                item {
                    val message = when (PhoneData.contactsAccessState) {
                        ContactsBridge.AccessState.APP_NOT_INSTALLED ->
                            "尚未安装通讯录应用"
                        ContactsBridge.AccessState.ACCESS_DENIED ->
                            "电话与通讯录的发行证书不一致，请安装同一发行版"
                        ContactsBridge.AccessState.PROVIDER_ERROR ->
                            "暂时无法读取通讯录，请打开通讯录后返回重试"
                        ContactsBridge.AccessState.AVAILABLE ->
                            "通讯录中还没有联系人"
                    }
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                        color = if (PhoneData.contactsUnavailable) {
                            scheme.error
                        } else {
                            scheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(selected = true, onClick = {}, label = { Text("常用") },
                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) })
                    FilterChip(selected = false, onClick = {}, label = { Text("家人") })
                    FilterChip(selected = false, onClick = {}, label = { Text("工作") })
                }
            }
            letters.forEach { letter ->
                stickyHeader {
                    Box(Modifier.fillMaxWidth().background(scheme.surface)) {
                        Text(
                            letter,
                            Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp),
                            color = scheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                items(PhoneData.people.filter { it.letter == letter }, key = { it.id }) { p ->
                    Row(
                        Modifier
                            .padding(horizontal = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(26.dp))
                            .clickable {
                                state.selectedId = p.id
                                state.go(com.evelorion.phone.ui.Screen.Detail)
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.initial, p.bg, p.fg, size = 46.dp, fontSize = 18)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(p.number, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        RoundIconButton(
                            Icons.Filled.Call, "呼叫",
                            bg = CallGreenContainer, fg = OnCallGreenContainer,
                            size = 44.dp, corner = 16.dp
                        ) { state.call(p.id) }
                    }
                }
            }
        }
        PhoneBottomBar(state)
    }
}
