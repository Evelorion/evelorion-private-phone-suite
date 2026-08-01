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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
    val person = CallManager.peer()
    var seconds by remember { mutableIntStateOf(0) }
    var padVisible by remember { mutableStateOf(false) }
    val recording = CallAudioRecorder.isRecording
    val muted = CallManager.muted
    val speaker = CallManager.speakerOn
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

    LaunchedEffect(recording) {
        while (CallAudioRecorder.isRecording) {
            delay(250)
            CallAudioRecorder.sampleAmplitude()
        }
    }

    LaunchedEffect(CallManager.connectedAt) {
        while (true) {
            val start = CallManager.connectedAt
            seconds = if (start == 0L) 0 else ((System.currentTimeMillis() - start) / 1_000).toInt()
            delay(500)
        }
    }

    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)
    val stateLabel = when {
        onHold -> "通话已保持"
        CallManager.connectedAt == 0L -> "正在连接…"
        else -> "通话中  $timer"
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
                CallControl(Icons.Filled.VolumeUp, if (speaker) "关闭免提" else "免提", speaker) {
                    if (!CallManager.toggleSpeaker()) unavailable("无法切换音频：系统尚未连接通话服务")
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
private fun DialTonePad(typed: String, onDigit: (Char) -> Unit) {
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp))
            .background(Color(0xB51A3147)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            typed.takeLast(18).ifBlank { "输入分机号或按键" },
            color = if (typed.isBlank()) Color(0xFFBFD0DF) else Color.White,
            fontSize = 16.sp,
            letterSpacing = 1.8.sp,
        )
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { digit ->
                    Box(
                        Modifier.weight(1f).height(43.dp).clip(RoundedCornerShape(18.dp))
                            .background(Color(0x4DFFFFFF)).clickable { onDigit(digit) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digit.toString(), color = Color.White, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

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
