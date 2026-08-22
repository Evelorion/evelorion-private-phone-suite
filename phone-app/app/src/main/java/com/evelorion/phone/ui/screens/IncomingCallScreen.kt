package com.evelorion.phone.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.telecom.CallManager
import com.evelorion.phone.ui.peer
import com.evelorion.phone.ui.Motion
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen
import com.evelorion.phone.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** 来电：上滑接听 / 下滑拒接，拖动中按钮变色变形 */
@Composable
fun IncomingCallScreen() {
    // 对方信息来自真实通话；系统姓名或内存缓存会在第一帧直接填入，
    // 后台查询只负责校正最新联系人名称。
    val person = CallManager.peer()
    val colors = MaterialTheme.colorScheme
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val threshold = with(density) { 80.dp.toPx() }
    val dragLimit = threshold * 1.25f

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(2000, easing = Motion.EmphasizedDecelerate), RepeatMode.Restart),
        label = "ring"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart), label = "ringAlpha"
    )
    val hintBob by pulse.animateFloat(
        initialValue = 0f, targetValue = -7f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "bob"
    )

    val knobColor by animateColorAsState(
        when {
            drag < -threshold / 2 -> CallGreen
            drag > threshold / 2 -> colors.error
            else -> colors.primaryContainer
        }, Motion.emphasized(Motion.DurationShort), label = "knobColor"
    )
    val knobCorner by animateDpAsState(if (dragging) 30.dp else 42.dp, Motion.springy(), label = "knobCorner")

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallScrimTop, CallScrimMid, CallScrimBottom)))
            .padding(top = 74.dp, start = 24.dp, end = 24.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("来电", color = DarkOnSurfaceVariant, fontSize = 14.sp, letterSpacing = 1.6.sp)
        Box(Modifier.padding(top = 34.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(152.dp).scale(ring).alpha(ringAlpha)
                    .border(2.dp, colors.primary, CircleShape)
            )
            Box(
                Modifier.size(132.dp).clip(RoundedCornerShape(44.dp)).background(person.bg),
                contentAlignment = Alignment.Center
            ) { Text(person.initial, color = person.fg, fontSize = 48.sp, fontWeight = FontWeight.Medium) }
        }
        Text(person.name, Modifier.padding(top = 24.dp), color = DarkOnSurface, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        // 没有归属地库时不要留一个孤零零的「·」
        val subtitle = if (person.city.isBlank()) person.number else "${person.number} · ${person.city}"
        Text(subtitle, color = DarkOnSurfaceVariant, fontSize = 15.sp)

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SecondaryAction(Icons.AutoMirrored.Filled.Message, "回信息")
            SecondaryAction(Icons.Filled.NotificationsOff, "静音铃声")
        }

        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.align(Alignment.TopCenter).alpha(if (drag > 20f) 0.25f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.KeyboardDoubleArrowUp, null, tint = Color(0xFFB6F0C6),
                    modifier = Modifier.size(26.dp).offset { IntOffset(0, hintBob.roundToInt()) }
                )
                Text("上滑接听", color = Color(0xFFB6F0C6), fontSize = 13.sp)
            }
            Box(
                Modifier
                    .offset { IntOffset(0, drag.roundToInt()) }
                    .size(84.dp)
                    .clip(RoundedCornerShape(knobCorner))
                    .background(knobColor)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                when {
                                    // 上滑接听、下滑拒接。方向和 ZIP 里的一致，
                                    // 只是接的动作从演示改成了真的操作 Telecom。
                                    drag < -threshold -> CallManager.answer()
                                    drag > threshold -> CallManager.reject()
                                }
                                drag = 0f
                            },
                            onDragCancel = { dragging = false; drag = 0f },
                            onVerticalDrag = { _, delta ->
                                drag = (drag + delta).coerceIn(-dragLimit, dragLimit)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (drag > threshold / 2) Icons.Filled.CallEnd else Icons.Filled.Call,
                    "接听",
                    tint = if (abs(drag) > threshold / 2) Color.White else colors.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).alpha(if (drag < -20f) 0.25f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("下滑拒接", color = Color(0xFFF2B8B5), fontSize = 13.sp)
                Icon(
                    Icons.Filled.KeyboardDoubleArrowDown, null, tint = Color(0xFFF2B8B5),
                    modifier = Modifier.size(26.dp).offset { IntOffset(0, -hintBob.roundToInt()) }
                )
            }
        }
    }
}

@Composable
private fun SecondaryAction(icon: ImageVector, label: String) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainerHigh.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
        Text(label, color = DarkOnSurfaceVariant, fontSize = 12.sp)
    }
}
