package com.example.sms.util

import android.media.MediaPlayer

/** 单例播放器，保证同一时间只播一条语音 */
object VoicePlayer {

    private var player: MediaPlayer? = null
    private var playingUri: String? = null

    fun toggle(uri: String, onComplete: () -> Unit): Boolean {
        if (playingUri == uri) {
            stop()
            return false
        }
        stop()
        return runCatching {
            val p = MediaPlayer()
            p.setDataSource(uri.removePrefix("file:"))
            p.setOnCompletionListener {
                stop()
                onComplete()
            }
            p.prepare()
            p.start()
            player = p
            playingUri = uri
            true
        }.getOrElse {
            stop()
            false
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        playingUri = null
    }

    fun isPlaying(uri: String): Boolean = playingUri == uri
}
