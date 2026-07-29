package com.evelorion.phone.telecom

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当前通话的音频录制器。
 *
 * Android 10 以后普通第三方应用能拿到哪一路通话音频由系统和厂商决定。
 * VOICE_COMMUNICATION 是合法可用的最佳尝试；部分设备仍可能只给本机麦克风。
 */
object CallAudioRecorder {

    sealed interface Result {
        data class Saved(val location: String, val warning: String = "") : Result
        data class Failed(val reason: String) : Result
    }

    var isRecording by mutableStateOf(false)
        private set

    private var recorder: MediaRecorder? = null
    private var output: OutputTarget? = null
    private var appContext: Context? = null
    private var peakAmplitude = 0

    @Synchronized
    fun start(context: Context, number: String): Result {
        if (isRecording) return Result.Failed("录音已经开始")
        if (!CallManager.isActive) return Result.Failed("通话接通后才能录音")

        val target = runCatching { createOutput(context, number) }
            .getOrElse { return Result.Failed(it.message ?: "无法创建录音文件") }

        // 普通第三方应用通常拿不到通话上下行音轨。VOICE_COMMUNICATION 在部分设备上
        // 会“启动成功”却只写入静音，因此先录系统明确允许的本机麦克风。
        val started = runCatching {
            createRecorder(context, target.fileDescriptor, MediaRecorder.AudioSource.MIC)
        }.recoverCatching {
            createRecorder(context, target.fileDescriptor, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }

        val activeRecorder = started.getOrElse {
            target.discard(context)
            return Result.Failed(it.message ?: "这台设备不允许通话录音")
        }

        appContext = context.applicationContext
        output = target
        recorder = activeRecorder
        peakAmplitude = 0
        isRecording = true
        return Result.Saved("录音中")
    }

    @Synchronized
    fun sampleAmplitude() {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amplitude > peakAmplitude) peakAmplitude = amplitude
    }

    @Synchronized
    fun stop(): Result {
        val activeRecorder = recorder ?: return Result.Failed("当前没有录音")
        val target = output
        val context = appContext

        recorder = null
        output = null
        appContext = null
        isRecording = false
        val recordedPeak = peakAmplitude
        peakAmplitude = 0

        return runCatching {
            activeRecorder.stop()
            activeRecorder.release()
            requireNotNull(target)
            requireNotNull(context)
            target.finish(context)
            Result.Saved(
                location = target.location,
                warning = if (recordedPeak == 0) {
                    "系统没有向第三方电话应用提供可录制的音频；文件已保存，但可能是静音"
                } else {
                    ""
                },
            )
        }.getOrElse {
            runCatching { activeRecorder.reset() }
            runCatching { activeRecorder.release() }
            if (target != null && context != null) target.discard(context)
            Result.Failed("录音保存失败：${it.message ?: "未知错误"}")
        }
    }

    private fun createRecorder(
        context: Context,
        fileDescriptor: FileDescriptor,
        audioSource: Int,
    ): MediaRecorder {
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mediaRecorder.setAudioSource(audioSource)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setOutputFile(fileDescriptor)
            mediaRecorder.prepare()
            mediaRecorder.start()
            mediaRecorder
        } catch (error: Throwable) {
            runCatching { mediaRecorder.reset() }
            runCatching { mediaRecorder.release() }
            throw error
        }
    }

    private fun createOutput(context: Context, number: String): OutputTarget {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeNumber = number.filter { it.isDigit() || it == '+' }.takeLast(20).ifBlank { "unknown" }
        val fileName = "call-$safeNumber-$stamp.m4a"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/Evelorion")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ) { "无法在系统录音目录创建文件" }
            val descriptor = requireNotNull(
                context.contentResolver.openFileDescriptor(uri, "w")
            ) { "无法打开录音文件" }
            return OutputTarget.MediaStoreTarget(uri, descriptor, "Recordings/Evelorion/$fileName")
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val directory = File(root, "Recordings").apply { mkdirs() }
        val file = File(directory, fileName)
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE,
        )
        return OutputTarget.FileTarget(file, descriptor)
    }

    private sealed class OutputTarget(
        val descriptor: ParcelFileDescriptor,
        val location: String,
    ) {
        val fileDescriptor: FileDescriptor get() = descriptor.fileDescriptor

        abstract fun finish(context: Context)
        abstract fun discard(context: Context)

        class MediaStoreTarget(
            private val uri: Uri,
            descriptor: ParcelFileDescriptor,
            location: String,
        ) : OutputTarget(descriptor, location) {
            override fun finish(context: Context) {
                descriptor.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
            }

            override fun discard(context: Context) {
                runCatching { descriptor.close() }
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }

        class FileTarget(
            private val file: File,
            descriptor: ParcelFileDescriptor,
        ) : OutputTarget(descriptor, file.absolutePath) {
            override fun finish(context: Context) {
                descriptor.close()
            }

            override fun discard(context: Context) {
                runCatching { descriptor.close() }
                runCatching { file.delete() }
            }
        }
    }
}
