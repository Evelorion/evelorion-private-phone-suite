package com.evelorion.phone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.telecom.CallManager
import com.evelorion.phone.telecom.CallAudioRecorder
import com.evelorion.phone.ui.peer
import com.evelorion.phone.ui.PhoneState
import com.evelorion.phone.ui.Screen
import com.evelorion.phone.ui.components.MorphingSurface
import com.evelorion.phone.ui.theme.*
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat

/** 通话中：计时、免提/静音/键盘切换，选中控件圆角收缩 */
@Composable
fun InCallScreen() {
    val context = LocalContext.current
    val person = CallManager.peer()
    var seconds by remember { mutableIntStateOf(0) }
    var pad by remember { mutableStateOf(false) }
    val recording = CallAudioRecorder.isRecording
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

    // 静音和免提的真相在系统那边（AudioManager 的路由），不在这里 ——
    // 用本地 remember 记的话，界面会和实际状态脱节：
    // 比如插上耳机，系统把免提关了，而按钮还亮着。
    val muted = CallManager.muted
    val speaker = CallManager.speakerOn

    // 计时从**接通那一刻**算起，不是从界面出现算起。
    // 用户在通话中切走再切回来，看到的必须还是正确的时长。
    LaunchedEffect(CallManager.connectedAt) {
        while (true) {
            val start = CallManager.connectedAt
            seconds = if (start == 0L) 0 else ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(500)
        }
    }
    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallScrimTop, CallScrimMid, CallScrimBottom)))
            .padding(top = 74.dp, start = 22.dp, end = 22.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(108.dp).clip(RoundedCornerShape(36.dp)).background(person.bg),
            contentAlignment = Alignment.Center
        ) { Text(person.initial, color = person.fg, fontSize = 40.sp, fontWeight = FontWeight.Medium) }
        Text(person.name, Modifier.padding(top = 20.dp), color = DarkOnSurface, fontSize = 29.sp, fontWeight = FontWeight.Medium)
        Text(timer, Modifier.padding(top = 8.dp), color = Color(0xFFD0BCFF), fontSize = 16.sp, letterSpacing = 0.5.sp)

        AnimatedVisibility(pad, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 22.dp)) {
                listOf("123", "456", "789", "＊0＃").forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        row.forEach { ch ->
                            Box(
                                Modifier.weight(1f).height(56.dp)
                                    .clip(RoundedCornerShape(20.dp)).background(DarkSurfaceContainer)
                                    // 通话中的键盘要真的发 DTMF 音（查话费、按分机号靠它）。
                                    // 显示用的是全角＊＃，发出去必须换成 ASCII 的 *#，
                                    // 全角字符 Telecom 不认，表现是按了没反应。
                                    .clickable {
                                        CallManager.sendDtmf(
                                            when (ch) {
                                                '＊' -> '*'
                                                '＃' -> '#'
                                                else -> ch
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text(ch.toString(), color = DarkOnSurface, fontSize = 22.sp) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        val controls = listOf(
            Control(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, "静音", muted) { CallManager.toggleMute() },
            Control(Icons.Filled.Dialpad, "键盘", pad) { pad = !pad },
            Control(Icons.Filled.VolumeUp, "免提", speaker) { CallManager.toggleSpeaker() },
            Control(Icons.Filled.AddIcCall, "添加通话", false) {},
            Control(Icons.Filled.Pause, "保持", false) {},
            Control(Icons.Filled.FiberManualRecord, if (recording) "停止录音" else "录音", recording) {
                if (recording) {
                    when (val result = CallAudioRecorder.stop()) {
                        is CallAudioRecorder.Result.Saved ->
                            Toast.makeText(context, "已保存到 ${result.location}", Toast.LENGTH_LONG).show()
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
            }
        )
        controls.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                row.forEach { c ->
                    MorphingSurface(
                        Modifier.weight(1f).height(82.dp),
                        color = if (c.active) PrimaryContainer else DarkSurfaceContainer,
                        restingCorner = if (c.active) 20.dp else 28.dp,
                        pressedCorner = 16.dp,
                        onClick = c.onClick
                    ) {
                        val fg = if (c.active) OnPrimaryContainer else DarkOnSurface
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(c.icon, null, tint = fg, modifier = Modifier.size(24.dp))
                            Text(c.label, color = fg, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        MorphingSurface(
            Modifier.padding(top = 8.dp).size(width = 172.dp, height = 72.dp),
            color = ErrorColor, restingCorner = 36.dp, pressedCorner = 22.dp,
            onClick = { CallManager.hangUp() }
        ) { Icon(Icons.Filled.CallEnd, "挂断", tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

private data class Control(
    val icon: ImageVector,
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit
)
