package com.evelorion.phone.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.evelorion.phone.R
import com.evelorion.phone.MainActivity
import com.evelorion.phone.telecom.CallAudioRecorder
import com.evelorion.phone.telecom.CallManager
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.peer
import com.evelorion.phone.ui.theme.ErrorColor
import kotlinx.coroutines.delay

/**
 * 通话中界面。
 *
 * 背景是随 APK 打包的原创印象派睡莲花园，不依赖网络。所有按钮的选中状态都来自
 * Telecom 回调；静音、音频端点、保持和 DTMF 都会真正发给系统，不只改变按钮颜色。
 */
@Composable
fun InCallScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val person = CallManager.peer()
    var seconds by remember { mutableIntStateOf(0) }
    var padVisible by remember { mutableStateOf(false) }
    var audioRouteDialogVisible by remember { mutableStateOf(false) }
    val recording = CallAudioRecorder.isRecording
    val muted = CallManager.muted
    val speaker = CallManager.speakerOn
    val audioRoutes = CallManager.audioRoutes
    val activeAudioRouteId = CallManager.activeAudioRouteId
    val onHold = CallManager.onHold

    fun unavailable(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val startRecording = {
        when (val result = CallAudioRecorder.start(context, CallManager.number)) {
            is CallAudioRecorder.Result.Saved ->
                Toast.makeText(context, result.location, Toast.LENGTH_SHORT).show()
            is CallAudioRecorder.Result.Failed ->
                Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
        }
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(context, "需要麦克风权限才能录音", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(lifecycleOwner, CallManager.connectedAt) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val start = CallManager.connectedAt
                seconds = if (start == 0L) 0 else ((System.currentTimeMillis() - start) / 1_000).toInt()
                // 只有通话页可见时才更新；切到游戏后不再产生 Compose 定时唤醒。
                delay(1_000)
            }
        }
    }

    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)
    val stateLabel = when {
        onHold -> "通话已保持"
        CallManager.connectedAt == 0L -> "正在连接…"
        else -> "通话中  $timer"
    }

    if (audioRouteDialogVisible) {
        AlertDialog(
            onDismissRequest = { audioRouteDialogVisible = false },
            title = { Text("选择通话音频") },
            text = {
                Column {
                    audioRoutes.forEach { route ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                                if (!CallManager.selectAudioRoute(route.id)) {
                                    unavailable("这个音频设备暂时不可用")
                                }
                                audioRouteDialogVisible = false
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = route.id == activeAudioRouteId,
                                onClick = null,
                            )
                            Text(route.label, Modifier.padding(start = 8.dp), maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { audioRouteDialogVisible = false }) { Text("关闭") }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.monet_water_lilies_call),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xCC071A2F),
                    0.42f to Color(0x85223855),
                    0.72f to Color(0xA60A2340),
                    1f to Color(0xED071526),
                )
            )
        )

        Column(
            Modifier.fillMaxSize().padding(top = 48.dp, start = 18.dp, end = 18.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(18.dp))
                    .background(Color(0x5EFFFFFF))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stateLabel, color = Color.White, fontSize = 13.sp, letterSpacing = 0.4.sp)
            }

            Box(
                Modifier.padding(top = 18.dp).size(88.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFDCC7E8), Color(0xFF8FB7AA)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(person.initial, color = Color(0xFF17334A), fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                person.name,
                Modifier.padding(top = 13.dp),
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (CallManager.number.isNotBlank() && CallManager.number != person.name) {
                Text(CallManager.number, color = Color(0xFFDCE9F4), fontSize = 14.sp)
            }

            AnimatedVisibility(
                visible = padVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                DialTonePad(
                    typed = CallManager.dtmfTyped,
                    onDigit = { digit ->
                        if (!CallManager.sendDtmf(digit)) unavailable("当前没有可发送按键音的通话")
                    },
                    onDismiss = { padVisible = false },
                )
            }

            Spacer(Modifier.weight(1f))

            val controls = listOf(
                CallControl(
                    if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    if (muted) "取消静音" else "静音",
                    muted,
                ) {
                    if (!CallManager.toggleMute()) unavailable("静音不可用：请先把本应用设为默认电话应用")
                },
                CallControl(Icons.Filled.Dialpad, "键盘", padVisible) { padVisible = !padVisible },
                CallControl(
                    Icons.Filled.VolumeUp,
                    when {
                        speaker -> "扬声器"
                        audioRoutes.firstOrNull { it.id == activeAudioRouteId }?.isHeadset == true -> "耳机"
                        else -> "音频"
                    },
                    speaker || audioRoutes.firstOrNull { it.id == activeAudioRouteId }?.isHeadset == true,
                ) {
                    if (audioRoutes.isEmpty()) {
                        unavailable("系统还没有提供可用的通话音频设备")
                    } else {
                        audioRouteDialogVisible = true
                    }
                },
                CallControl(Icons.Filled.AddIcCall, "添加通话", false) {
                    if (!CallManager.holdForAdditionalCall()) {
                        unavailable("运营商或当前通话不支持保持，无法添加第二通电话")
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(context, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_OPEN_DIALPAD, true)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        }.onFailure { unavailable("无法打开拨号盘") }
                    }
                },
                CallControl(
                    if (onHold) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    if (onHold) "继续通话" else "保持",
                    onHold,
                ) {
                    if (!CallManager.toggleHold()) unavailable("运营商或当前通话不支持保持")
                },
                CallControl(
                    Icons.Filled.FiberManualRecord,
                    if (recording) "停止录音" else "录音",
                    recording,
                ) {
                    if (recording) {
                        when (val result = CallAudioRecorder.stop()) {
                            is CallAudioRecorder.Result.Saved -> Toast.makeText(
                                context,
                                result.warning.ifBlank { "已保存到 ${result.location}" },
                                Toast.LENGTH_LONG,
                            ).show()
                            is CallAudioRecorder.Result.Failed ->
                                Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                        }
                    } else if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startRecording()
                    } else {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp))
                    .background(Color(0x52364F62)).padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    controls.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            row.forEach { control -> MonetControl(Modifier.weight(1f), control) }
                        }
                    }
                }
            }

            MorphingSurface(
                Modifier.padding(top = 14.dp).size(width = 164.dp, height = 64.dp),
                color = ErrorColor,
                restingCorner = 32.dp,
                pressedCorner = 21.dp,
                onClick = { CallManager.hangUp() },
            ) {
                Icon(Icons.Filled.CallEnd, "挂断电话", tint = Color.White, modifier = Modifier.size(31.dp))
            }
        }
    }
}

@Composable
private fun DialTonePad(typed: String, onDigit: (Char) -> Unit, onDismiss: () -> Unit) {
    val rows = listOf(
        listOf(DialKey('1'), DialKey('2', "ABC"), DialKey('3', "DEF")),
        listOf(DialKey('4', "GHI"), DialKey('5', "JKL"), DialKey('6', "MNO")),
        listOf(DialKey('7', "PQRS"), DialKey('8', "TUV"), DialKey('9', "WXYZ")),
        listOf(DialKey('*'), DialKey('0', "+"), DialKey('#')),
    )
    Column(
        Modifier.padding(top = 10.dp).width(280.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(40.dp))
            Text(
                typed.takeLast(18).ifBlank { "拨号键盘" },
                modifier = Modifier.weight(1f),
                color = if (typed.isBlank()) Color(0xFFD4E0EA) else Color.White,
                fontSize = 17.sp,
                letterSpacing = 1.4.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, "收起键盘", tint = Color.White)
            }
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                row.forEach { key ->
                    Box(
                        Modifier.size(62.dp).clip(CircleShape)
                            .background(Color(0xA6385064))
                            .border(1.dp, Color(0x3DFFFFFF), CircleShape)
                            .clickable { onDigit(key.digit) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(key.digit.toString(), color = Color.White, fontSize = 23.sp)
                            if (key.letters.isNotBlank()) {
                                Text(
                                    key.letters,
                                    color = Color(0xFFD0DDE7),
                                    fontSize = 8.sp,
                                    letterSpacing = 1.2.sp,
                                    lineHeight = 9.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DialKey(val digit: Char, val letters: String = "")

@Composable
private fun MonetControl(modifier: Modifier, control: CallControl) {
    val background = if (control.active) Color(0xFFD8C7EA) else Color(0xB72C4658)
    val foreground = if (control.active) Color(0xFF253245) else Color.White
    MorphingSurface(
        modifier.height(70.dp),
        color = background,
        restingCorner = if (control.active) 20.dp else 25.dp,
        pressedCorner = 15.dp,
        onClick = control.onClick,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(control.icon, control.label, tint = foreground, modifier = Modifier.size(23.dp))
            Text(control.label, color = foreground, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private data class CallControl(
    val icon: ImageVector,
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit,
)
