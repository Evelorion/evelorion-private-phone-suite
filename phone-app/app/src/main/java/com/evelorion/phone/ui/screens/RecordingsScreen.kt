package com.evelorion.phone.ui.screens

import android.content.ContentUris
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evelorion.phone.ui.PhoneState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(state: PhoneState) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf<List<RecordingItem>?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        recordings = withContext(Dispatchers.IO) { loadRecordings(context) }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    fun playOrPause(item: RecordingItem) {
        if (playingUri == item.uri) {
            val active = player ?: return
            if (active.isPlaying) active.pause() else active.start()
            isPlaying = active.isPlaying
            return
        }

        player?.release()
        player = runCatching {
            MediaPlayer.create(context, item.uri).also { mediaPlayer ->
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false
                    playingUri = null
                }
                mediaPlayer.start()
            }
        }.getOrNull()
        playingUri = if (player != null) item.uri else null
        isPlaying = player?.isPlaying == true
    }

    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 44.dp, start = 12.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = scheme.onSurface)
            }
            Text(
                "通话录音",
                color = scheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            val count = recordings?.size
            if (count != null) Text("$count 条", color = scheme.onSurfaceVariant, fontSize = 13.sp)
        }

        when (val items = recordings) {
            null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }

            emptyList<RecordingItem>() -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(42.dp),
                )
                Text(
                    "暂无通话录音",
                    Modifier.padding(top = 14.dp),
                    color = scheme.onSurfaceVariant,
                    fontSize = 16.sp,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(items, key = { it.uri.toString() }) { item ->
                    val active = playingUri == item.uri && isPlaying
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { playOrPause(item) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { playOrPause(item) }) {
                            Icon(
                                if (active) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (active) "暂停" else "播放",
                                tint = scheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.displayNumber,
                                color = scheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${formatDate(item.dateAddedSeconds)} · ${formatDuration(item.durationMs)} · ${formatSize(item.sizeBytes)}",
                                color = scheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private data class RecordingItem(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
) {
    val displayNumber: String
        get() = fileName.removePrefix("call-")
            .substringBeforeLast("-")
            .substringBeforeLast("-")
            .ifBlank { "未知号码" }
}

private fun loadRecordings(context: Context): List<RecordingItem> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(root, "Recordings").listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .map { file ->
                RecordingItem(
                    uri = Uri.fromFile(file),
                    fileName = file.name,
                    durationMs = mediaDuration(file),
                    sizeBytes = file.length(),
                    dateAddedSeconds = file.lastModified() / 1_000,
                )
            }
    }

    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_ADDED,
    )
    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH}=?"
    val selectionArgs = arrayOf("Recordings/Evelorion/")

    return context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        "${MediaStore.Audio.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        buildList {
            while (cursor.moveToNext()) {
                add(
                    RecordingItem(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                        fileName = cursor.getString(nameColumn),
                        durationMs = cursor.getLong(durationColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        dateAddedSeconds = cursor.getLong(dateColumn),
                    )
                )
            }
        }
    }.orEmpty()
}

private fun mediaDuration(file: File): Long {
    val player = runCatching { MediaPlayer().apply { setDataSource(file.absolutePath); prepare() } }.getOrNull()
        ?: return 0L
    return player.duration.toLong().also { player.release() }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatSize(bytes: Long): String =
    if (bytes < 1024 * 1024) "${bytes / 1024} KB"
    else String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))

private fun formatDate(seconds: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * 1_000))
