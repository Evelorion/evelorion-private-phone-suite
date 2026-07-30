package com.evelorion.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.RoundIconButton
import com.evelorion.phone.ui.theme.CallGreenContainer
import com.evelorion.phone.ui.theme.OnCallGreenContainer

@Composable
fun SearchScreen(state: PhoneState) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val q = state.query.trim()
    val results = if (q.isEmpty()) emptyList() else PhoneData.people.filter {
        it.name.contains(q) || it.number.replace(" ", "").contains(q.replace(" ", ""))
    }

    Column(Modifier.fillMaxSize().background(scheme.surface).padding(top = 44.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { state.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = scheme.onSurface)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) {
                    Text("搜索姓名或号码", color = scheme.onSurfaceVariant, fontSize = 17.sp)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 17.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { state.query = "" }) {
                    Icon(Icons.Filled.Close, "清除", tint = scheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

        if (q.isNotEmpty() && results.isEmpty()) {
            Text(
                "没有匹配的联系人或通话记录",
                Modifier.fillMaxWidth().padding(60.dp),
                color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 15.sp
            )
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(results, key = { it.id }) { p ->
                Row(
                    Modifier.padding(horizontal = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .clickable { state.showPerson(p.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(p.initial, p.bg, p.fg, size = 46.dp, fontSize = 18)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("${p.number} · ${p.city}", color = scheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    RoundIconButton(
                        Icons.Filled.Call, "呼叫",
                        bg = CallGreenContainer, fg = OnCallGreenContainer, size = 44.dp, corner = 16.dp
                    ) { state.call(p.id) }
                }
            }
        }
    }
}
