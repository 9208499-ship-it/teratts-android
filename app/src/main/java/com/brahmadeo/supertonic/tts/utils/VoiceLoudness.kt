package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Voices come out of the model at different loudness: one character whispers next to the
 * narrator, another shouts. Each voice's loudness (RMS of the speech, pauses left out) is
 * learned from what it has already said — a running average per voice file, kept across
 * launches — and every voice is brought to the level of the reading's main voice.
 * A main voice that is itself quiet is first raised to a normal level ([TARGET]), so a
 * quiet main voice does not pull all the others down; a loud one is left as it is.
 */
object VoiceLoudness {
    private const val PREFS = "VoiceLoudness"
    private const val KEY_ENABLED = "enabled"
    private const val MIN_FACTOR = 0.5f
    private const val MAX_FACTOR = 2.0f
    /** Normal speech level: RMS of the speech ≈ −20 dBFS (peaks stay below the limiter). */
    private const val TARGET = 0.1f

    private val levels = ConcurrentHashMap<String, Float>()
    private val counts = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    private fun key(voicePath: String) = File(voicePath).name

    private fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (k.endsWith("_n") || k == KEY_ENABLED) continue
            (v as? Float)?.let { levels[k] = it; counts[k] = p.getInt(k + "_n", 1) }
        }
        loaded = true
    }

    /**
     * Gain for [voicePath] so that it sounds as loud as [refPath] (the main voice), or as
     * [TARGET] if the main voice is quieter than that; 1 while the voices are not measured yet.
     */
    fun factor(context: Context, voicePath: String, refPath: String): Float {
        if (voicePath.isEmpty() || !enabled(context)) return 1f
        load(context)
        val v = levels[key(voicePath)] ?: return 1f
        if (v <= 0f) return 1f
        val r = if (refPath.isEmpty() || refPath == voicePath) v else levels[key(refPath)] ?: return 1f
        return (maxOf(r, TARGET) / v).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** Learn from [pcm] (16-bit mono, produced with [gainUsed]) how loud [voicePath] is. */
    fun observe(context: Context, voicePath: String, pcm: ByteArray, gainUsed: Float, rate: Int) {
        if (voicePath.isEmpty() || gainUsed <= 0f) return
        val level = levelOf(pcm, rate) ?: return
        load(context)
        val k = key(voicePath)
        val raw = level / gainUsed
        val n = counts[k] ?: 0
        val old = levels[k]
        // the first phrases count fully, later ones nudge the average
        val alpha = maxOf(1f / (n + 1), 0.1f)
        val v = if (old == null) raw else old + (raw - old) * alpha
        levels[k] = v
        counts[k] = minOf(n + 1, 1000)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(k, v).putInt(k + "_n", counts[k] ?: 1).apply()
    }

    /** RMS of the speech in [pcm]: 20 ms frames, those more than 20 dB below the loudest are pauses. */
    fun levelOf(pcm: ByteArray, rate: Int): Float? {
        val n = pcm.size / 2
        val frame = (rate / 50).coerceAtLeast(160)
        if (n < frame * 10) return null
        val energies = DoubleArray(n / frame)
        for (f in energies.indices) {
            var s = 0.0
            val base = f * frame
            for (i in base until base + frame) {
                val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() / 32768.0
                s += v * v
            }
            energies[f] = s / frame
        }
        val max = energies.maxOrNull() ?: return null
        val threshold = maxOf(max * 0.01, 1e-6)
        var sum = 0.0
        var count = 0
        for (e in energies) if (e >= threshold) { sum += e; count++ }
        if (count < 10) return null
        return sqrt(sum / count).toFloat()
    }
}
