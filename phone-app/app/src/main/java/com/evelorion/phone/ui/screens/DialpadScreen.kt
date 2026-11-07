package com.evelorion.phone.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.data.Person
import com.evelorion.phone.data.PhoneData
import com.evelorion.phone.ui.Motion
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.components.Avatar
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.components.simTint
import com.evelorion.phone.ui.theme.CallGreen
import com.evelorion.phone.telecom.DialNumber
import com.evelorion.phone.telecom.SimCards

private val keys = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to ""
)

/** 拨号键盘：输入时实时匹配联系人 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadScreen(state: PhoneState) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 双卡机器上拨号前先明确用哪张卡；单卡（或没权限读订阅）时整行不显示。
    val sims = remember { SimCards.available(context) }
    var chosenSubId by remember {
        mutableIntStateOf(SimCards.selected(context)?.subscriptionId ?: -1)
    }
    // 号码的手动编辑：点击号码打开，粘贴之后也会自动打开，
    // 这样粘错一位不用整串删掉重来。
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
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
            Box(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .combinedClickable(
                        onClick = {
                            draft = state.dial
                            editing = true
                        },
                        onLongClick = {
                            val pasted = clipboard.getText()?.text
                                ?.let { DialNumber.sanitizeInput(it) }
                                .orEmpty()
                            if (pasted.isNotBlank()) {
                                state.dial = pasted
                                draft = pasted
                                editing = true
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.dial.ifBlank { "点这里编辑 · 长按粘贴号码" },
                    color = if (state.dial.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                    fontSize = if (state.dial.isBlank()) 15.sp else fontSize.sp,
                    letterSpacing = if (state.dial.isBlank()) 0.sp else 2.sp,
                    textAlign = TextAlign.Center,
                )
            }
            if (sims.size > 1) {
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sims.forEach { sim ->
                        val selected = sim.subscriptionId == chosenSubId
                        Row(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh
                                )
                                .clickable {
                                    chosenSubId = sim.subscriptionId
                                    // 记住选择：下次进来还是这张卡，不用每次都点。
                                    SimCards.remember(context, sim.subscriptionId)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(simTint(sim)))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                buildString {
                                    append(sim.slotLabel)
                                    val extra = sim.displayName.ifBlank { sim.carrierName }
                                    if (extra.isNotBlank()) append(" · ").append(extra)
                                },
                                color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
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
                            onClick = {
                                state.dial = DialNumber.sanitizeInput(state.dial + digit)
                            },
                            onLongClick = if (digit == "0") ({
                                state.dial = DialNumber.sanitizeInput("+" + state.dial.removePrefix("+"))
                            }) else null,
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

    // 粘贴/点击之后的可编辑窗口。粘错一位不必整串删掉重来。
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑号码") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = DialNumber.sanitizeInput(it) },
                    singleLine = true,
                    label = { Text("号码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.dial = draft
                        editing = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            },
        )
    }
}
