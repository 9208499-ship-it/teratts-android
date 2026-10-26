package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Voices speak at different tempo at the same speed setting: a voice made from a recording
 * (Settings → "Import voice", the voice-cloning notebook) keeps the pace of that recording —
 * a calm reader's voice is much slower than the others even at 1.9×.
 *
 * Each voice's own tempo (letters per second of speech at speed 1, pauses left out) is learned
 * from what it has already said — a running average per voice file, kept across launches, like
 * [VoiceLoudness]. A voice noticeably slower than the main voice (or than the usual tempo of the
 * bundled voices, if it is the main voice itself) is sped up to it. Faster voices are left as they are.
 */
object VoiceTempo {
    private const val PREFS = "VoiceTempo"
    private const val KEY_ENABLED = "enabled"
    /** Below this difference a voice is left alone. */
    private const val SLACK = 1.08f
    private const val MAX_FACTOR = 1.7f
    /** Phrases before a voice's tempo is trusted. */
    private const val MIN_COUNT = 3

    private val tempos = ConcurrentHashMap<String, Float>()
    private val counts = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    // per model: the same voice is faster or slower in TeraTTS and in Supertonic 3
    private fun key(voicePath: String) = AssetManager.engineKey + File(voicePath).name

    private fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (k.endsWith("_n") || k == KEY_ENABLED) continue
            (v as? Float)?.let { tempos[k] = it; counts[k] = p.getInt(k + "_n", 1) }
        }
        loaded = true
    }

    private fun measured(k: String): Float? = tempos[k]?.takeIf { (counts[k] ?: 0) >= MIN_COUNT && it > 0f }

    /** The usual tempo: the median of the bundled voices measured so far (own voices left out). */
    private fun usual(): Float? {
        val prefix = AssetManager.engineKey
        val v = tempos.keys.filter { k ->
            val name = k.removePrefix(prefix)
            k.startsWith(prefix) && !name.startsWith("st3:") &&
                (name.startsWith("ru_") || name.startsWith("eng_") || name.startsWith("st3_") || name.startsWith("lib_")) && measured(k) != null
        }.mapNotNull { measured(it) }.sorted()
        if (v.size < 3) return null
        return v[v.size / 2]
    }

    /**
     * Speed multiplier for [voicePath]: 1, or more if the voice is noticeably slower than [refPath]
     * (the main voice) — or than the usual tempo, when it is the main voice itself.
     */
    fun factor(context: Context, voicePath: String, refPath: String): Float {
        if (voicePath.isEmpty() || !enabled(context)) return 1f
        load(context)
        val k = key(voicePath)
        val v = measured(k) ?: return 1f
        val usual = usual()
        val target = if (refPath.isNotEmpty() && key(refPath) != k) {
            val r = measured(key(refPath)) ?: usual ?: return 1f
            // a slow main voice does not hold the others back
            maxOf(r, usual ?: r)
        } else usual ?: return 1f
        val f = target / v
        return if (f > SLACK) f.coerceAtMost(MAX_FACTOR) else 1f
    }

    /** Learn from [pcm] (16-bit mono) that [voicePath] said [text] at [speedUsed]. */
    fun observe(context: Context, voicePath: String, text: String, pcm: ByteArray, speedUsed: Float, rate: Int) {
        if (voicePath.isEmpty() || speedUsed <= 0f) return
        val letters = text.count { it.isLetter() }
        if (letters < 12) return
        val speech = speechSeconds(pcm, rate) ?: return
        if (speech < 0.5f) return
        val tempo = letters / (speech * speedUsed)
        load(context)
        val k = key(voicePath)
        val n = counts[k] ?: 0
        val old = tempos[k]
        val alpha = maxOf(1f / (n + 1), 0.05f)
        val t = if (old == null) tempo else old + (tempo - old) * alpha
        tempos[k] = t
        counts[k] = minOf(n + 1, 1000)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(k, t).putInt(k + "_n", counts[k] ?: 1).apply()
    }

    /** Seconds of speech in [pcm]: 20 ms frames, those more than 20 dB below the loudest are pauses. */
    fun speechSeconds(pcm: ByteArray, rate: Int): Float? {
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
        val speechFrames = energies.count { it >= threshold }
        return speechFrames * frame.toFloat() / rate
    }
}
