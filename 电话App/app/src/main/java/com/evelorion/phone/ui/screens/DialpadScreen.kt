package com.evelorion.phone.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.Person
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.Motion
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.theme.CallGreen

private val keys = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "＊" to "", "0" to "+", "＃" to ""
)

/** 拨号键盘：输入时实时匹配联系人 */
@Composable
fun DialpadScreen(state: PhoneState) {
    val scheme = MaterialTheme.colorScheme
    val digits = state.dial.filter { it.isDigit() }
    val match: Person? = if (digits.length >= 2)
        PhoneData.people.firstOrNull { it.number.replace(" ", "").contains(digits) } else null
    val fontSize by animateFloatAsState(
        if (state.dial.length > 9) 30f else 40f, Motion.emphasized(), label = "dialSize"
    )

    Column(Modifier.fillMaxSize().background(scheme.surface).padding(top = 44.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { state.back() }) {
                Icon(Icons.Filled.Close, "关闭", tint = scheme.onSurface)
            }
            Text("键盘", Modifier.weight(1f), textAlign = TextAlign.Center, color = scheme.onSurfaceVariant, fontSize = 15.sp)
            Spacer(Modifier.width(48.dp))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.dial, color = scheme.onSurface,
                fontSize = fontSize.sp, letterSpacing = 2.sp,
                modifier = Modifier.heightIn(min = 56.dp)
            )
            AnimatedVisibility(
                visible = match != null,
                enter = fadeIn() + scaleIn(Motion.springy(), initialScale = 0.9f),
                exit = fadeOut() + scaleOut()
            ) {
                match?.let { p ->
                    Row(
                        Modifier.padding(top = 14.dp).height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(scheme.secondaryContainer)
                            .clickable { state.call(p.id) }
                            .padding(start = 10.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.initial, p.bg, p.fg, size = 40.dp, corner = 14.dp, fontSize = 16)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.name, color = scheme.onSecondaryContainer, fontSize = 15.sp)
                            Text(p.number, color = scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (match == null && digits.length >= 3) {
                Text(
                    "未在联系人中 · 添加到联系人",
                    Modifier.padding(top = 16.dp), color = scheme.onSurfaceVariant, fontSize = 14.sp
                )
            }
        }

        Column(Modifier.padding(horizontal = 26.dp)) {
            keys.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    row.forEach { (digit, sub) ->
                        MorphingSurface(
                            Modifier.weight(1f).height(74.dp),
                            color = scheme.surfaceContainer,
                            restingCorner = 34.dp,
                            pressedCorner = 22.dp,
                            onClick = { if (state.dial.length < 13) state.dial += digit }
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, color = scheme.onSurface, fontSize = 28.sp)
                                Text(sub, color = scheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.4.sp)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                MorphingSurface(
                    Modifier.size(width = 112.dp, height = 68.dp),
                    color = CallGreen, restingCorner = 34.dp, pressedCorner = 20.dp,
                    onClick = { if (state.dial.isNotEmpty()) state.requestCall(state.dial) }
                ) { Icon(Icons.Filled.Call, "拨打", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(30.dp)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    // 显式写全限定名：这里外层既有 Row 又有 Box，
                    // 编译器会优先挑 RowScope 的扩展版本，然后报「隐式接收者不匹配」。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.dial.isNotEmpty(), enter = fadeIn(), exit = fadeOut()
                    ) {
                        IconButton(onClick = { state.dial = state.dial.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "删除", tint = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
