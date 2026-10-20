package com.brahmadeo.supertonic.tts.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Saving to a file on Huawei / Honor: with the screen off or the app in the background the
 * system freezes the app (foreground service and wake lock or not) unless it is playing
 * sound — the saving stopped and went on only when the app was opened again. While the
 * file is being made, a silent track plays (zeros, 8 kHz mono: no sound, next to no work,
 * no audio focus taken — music in another app is not interrupted). Other phones need none.
 */
object SilentKeepAlive {
    private const val TAG = "SilentKeepAlive"
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null

    fun needed(): Boolean = Build.MANUFACTURER.lowercase().let { it.contains("huawei") || it.contains("honor") }

    @Synchronized
    fun start() {
        if (!needed() || track != null) return
        try {
            val rate = 8000
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            val silence = ByteArray(rate / 5) // 0.1 s
            thread = Thread {
                try {
                    while (track === t) {
                        if (t.write(silence, 0, silence.size) < 0) break
                    }
                } catch (_: Throwable) { }
            }.apply { name = "SilentKeepAlive"; isDaemon = true; start() }
            Log.i(TAG, "silent track on while saving")
        } catch (e: Throwable) {
            Log.w(TAG, "silent track failed: ${e.message}")
            track = null
        }
    }

    @Synchronized
    fun stop() {
        val t = track ?: return
        track = null
        try { t.pause(); t.flush(); t.stop() } catch (_: Throwable) { }
        try { thread?.join(500) } catch (_: Throwable) { }
        thread = null
        try { t.release() } catch (_: Throwable) { }
    }
}
