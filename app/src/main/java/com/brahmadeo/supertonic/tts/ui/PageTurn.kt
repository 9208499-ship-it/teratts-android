package com.brahmadeo.supertonic.tts.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.abs
import kotlin.math.exp

/**
 * How the book is shown: a ribbon to scroll (as before) or pages to turn, and how a page turns
 * (it slides away, or it is turned over like a leaf), and whether a turn rustles.
 */
internal object PageMode {
    const val SLIDE = "slide"
    const val FLIP = "flip"
    private fun p(c: Context) = c.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    fun paged(c: Context) = p(c).getBoolean("page_mode", false)
    fun setPaged(c: Context, on: Boolean) = p(c).edit().putBoolean("page_mode", on).apply()
    fun anim(c: Context) = p(c).getString("page_anim", FLIP)?.takeIf { it == SLIDE || it == FLIP } ?: FLIP
    fun setAnim(c: Context, a: String) = p(c).edit().putString("page_anim", a).apply()
    fun sound(c: Context) = p(c).getBoolean("page_sound", true)
    fun setSound(c: Context, on: Boolean) = p(c).edit().putBoolean("page_sound", on).apply()
}

/**
 * The rustle of a page turned by hand: a third of a second of paper — two soft bursts of noise
 * (the leaf lifted, the leaf laid down) and a few crackles — made by the app itself, quietly.
 * Not while the voice reads: then the page turns after it, silently.
 */
internal object PageSound {
    private const val RATE = 22050
    private var track: AudioTrack? = null

    private fun make(): ShortArray {
        val n = (0.34 * RATE).toInt()
        val rnd = java.util.Random(3)
        val x = DoubleArray(n) { rnd.nextDouble() * 2 - 1 }
        fun hp(v: DoubleArray, fc: Double): DoubleArray {
            val a = exp(-2 * Math.PI * fc / RATE); var p = 0.0; var px = 0.0
            return DoubleArray(v.size) { i -> p = a * (p + v[i] - px); px = v[i]; p }
        }
        fun lp(v: DoubleArray, fc: Double): DoubleArray {
            val a = exp(-2 * Math.PI * fc / RATE); var p = 0.0
            return DoubleArray(v.size) { i -> p = (1 - a) * v[i] + a * p; p }
        }
        val y = lp(lp(hp(hp(x, 1200.0), 1200.0), 6000.0), 6000.0)
        fun env(t: Double, t0: Double, att: Double, dec: Double, amp: Double) =
            amp * if (t < t0) 0.0 else if (t < t0 + att) (t - t0) / att else exp(-(t - t0 - att) / dec)
        val cr = DoubleArray(n)
        repeat(14) {
            val i = (0.01 * RATE).toInt() + rnd.nextInt((0.23 * RATE).toInt())
            val amp = (0.4 + 0.6 * rnd.nextDouble()) * (if (rnd.nextBoolean()) 1 else -1)
            for (k in 0 until 40) if (i + k < n) cr[i + k] += amp * exp(-k / 6.0)
        }
        val crh = hp(cr, 2000.0)
        val out = DoubleArray(n) { i ->
            val t = i.toDouble() / RATE
            val e = env(t, 0.0, 0.02, 0.07, 1.0) + env(t, 0.15, 0.012, 0.05, 0.55) +
                0.25 * exp(-((t - 0.09) / 0.05) * ((t - 0.09) / 0.05))
            y[i] * e + crh[i] * 0.5
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6)
        return ShortArray(n) { i -> (out[i] / peak * 0.45 * 32767).toInt().toShort() }
    }

    @Synchronized
    fun play(context: Context) {
        if (!PageMode.sound(context)) return
        try {
            val t = track ?: run {
                val pcm = make()
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build().also { it.write(pcm, 0, pcm.size) }
            }
            track = t
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) { }
    }
}
