package com.brahmadeo.supertonic.tts.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A recording made fit to be a Pocket TTS voice. The model repeats the sample as it is — its noise,
 * hum, pauses and muffled highs (Kyutai: "the audio quality of the sample is reproduced"; ArtShtorm:
 * it copies the timbre, the tempo and the pitch range). So before it becomes a voice:
 *  - the rumble below ~70 Hz is cut;
 *  - long pauses are shortened (pauses in the sample slow the reading down);
 *  - the cleanest 6–10 s of speech is taken, from a phrase's beginning to a pause;
 *  - it is levelled by the loudness of the speech, not by its loudest click;
 *  - what cannot be mended is told: overload, a muffled (messenger, phone) recording, noise.
 * Plain arithmetic on 24 kHz mono, no Android: tested on the computer.
 */
object VoicePrep {
    const val RATE = 24000
    /** Analysis window: 20 ms. */
    private const val WIN = RATE / 50

    /** Which piece of the recording becomes the voice. */
    const val PART_START = 0
    const val PART_MIDDLE = 1
    const val PART_END = 2
    /** The cleanest piece (the default). */
    const val PART_AUTO = 3

    private const val MIN_WIN = 300      // 6 s
    private const val MAX_WIN = 500      // 10 s
    private const val LONG_PAUSE = 20    // a pause over 0.4 s…
    private const val KEEP_EDGE = 7      // …keeps 0.14 s of each side: 0.28 s
    private const val FADE = 240         // 10 ms

    class Failure(message: String) : Exception(message)

    /** What is wrong with a recording that cleaning does not mend. [snrDb]: speech over the noise. */
    class Report(val clipped: Boolean, val muffled: Boolean, val snrDb: Float) {
        val noisy get() = snrDb < 25f
    }

    // ---- filters ----

    /** RBJ biquad high-pass (Butterworth, Q = 0.707). */
    fun highPass(x: FloatArray, fc: Double): FloatArray {
        val w0 = 2 * Math.PI * fc / RATE
        val alpha = sin(w0) / (2 * 0.7071067811865476)
        val c = cos(w0)
        val a0 = 1 + alpha
        val b0 = (1 + c) / 2 / a0; val b1 = -(1 + c) / a0; val b2 = b0
        val a1 = -2 * c / a0; val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(x.size) { i ->
            val v = x[i].toDouble()
            val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = y
            y.toFloat()
        }
    }

    private fun windows(x: FloatArray): FloatArray = FloatArray(x.size / WIN) { i ->
        var a = 0.0
        for (k in i * WIN until (i + 1) * WIN) a += x[k] * x[k].toDouble()
        sqrt(a / WIN).toFloat()
    }

    private fun percentile(v: FloatArray, p: Float): Float {
        if (v.isEmpty()) return 0f
        val s = v.sortedArray()
        return s[((s.size - 1) * p).toInt()]
    }

    /** Below it a window is a pause: well under the loudest speech, and above the noise floor. */
    private fun threshold(w: FloatArray): Float {
        val peak = w.maxOrNull() ?: 0f
        val floor = percentile(w, 0.1f)
        return maxOf(peak * 0.05f, floor * 2.5f)
    }

    // ---- what cannot be mended ----

    /**
     * Overload (the waveform cut flat), a muffled recording (made at 16 kHz or less, or with no highs
     * left: a messenger's voice note, a phone call), and the noise under the speech. [x]: 24 kHz,
     * as decoded; [sourceRate]: the recording's own rate.
     */
    fun check(x: FloatArray, sourceRate: Int): Report {
        val clippedSamples = x.count { abs(it) >= 0.985f }
        val clipped = x.isNotEmpty() && clippedSamples.toFloat() / x.size > 0.0005f
        val y = highPass(x, 70.0)
        val w = windows(y)
        val thr = threshold(w)
        val loud = w.withIndex().filter { it.value >= thr }.map { it.index }
        val floor = percentile(w, 0.1f)
        var speech = 0.0
        for (i in loud) speech += w[i].toDouble() * w[i]
        speech = if (loud.isEmpty()) 0.0 else speech / loud.size
        val snr = if (floor <= 1e-6f) 90f else (10 * log10(speech / (floor.toDouble() * floor))).toFloat()
        // the highs: above ~9.5 kHz of the speech (full-band speech: −25…−40 dB of it; a recording
        // made at 16 kHz: −70 dB, only the filter's leak)
        var hi = x
        repeat(4) { hi = highPass(hi, 9500.0) }
        var e = 0.0; var eh = 0.0
        for (i in loud) for (k in i * WIN until (i + 1) * WIN) { e += y[k] * y[k].toDouble(); eh += hi[k] * hi[k].toDouble() }
        val muffled = sourceRate <= 16000 || (e > 0 && 10 * log10(eh / e + 1e-12) < -50)
        return Report(clipped, muffled, snr)
    }

    // ---- cleaning ----

    /** The rumble cut, the edges' silence and long pauses shortened (joined with short cross-fades). */
    fun clean(x: FloatArray): FloatArray {
        val y = highPass(x, 70.0)
        val w = windows(y)
        if (w.isEmpty()) return y
        val thr = threshold(w)
        val first = w.indexOfFirst { it >= thr }
        val last = w.indexOfLast { it >= thr }
        if (first < 0) return y
        // the pieces to keep, in windows: [a, b)
        val keep = ArrayList<IntArray>()
        var a = maxOf(0, first - 5)
        var i = first
        while (i <= last) {
            if (w[i] >= thr) { i++; continue }
            var j = i
            while (j <= last && w[j] < thr) j++
            if (j - i > LONG_PAUSE) {
                keep.add(intArrayOf(a, i + KEEP_EDGE))
                a = j - KEEP_EDGE
            }
            i = j
        }
        keep.add(intArrayOf(a, minOf(w.size, last + 6)))
        // joined: each seam cross-faded over 10 ms (in a pause, so nothing is heard)
        val out = ArrayList<Float>(y.size)
        for ((k, r) in keep.withIndex()) {
            val from = r[0] * WIN; val to = minOf(y.size, r[1] * WIN)
            if (k == 0 || out.size < FADE) { for (n in from until to) out.add(y[n]); continue }
            val base = out.size - FADE
            for (n in 0 until FADE) {
                val g = (n + 0.5f) / FADE
                val b = if (from + n < to) y[from + n] else 0f
                out[base + n] = out[base + n] * (1 - g) + b * g
            }
            for (n in from + FADE until to) out.add(y[n])
        }
        return out.toFloatArray()
    }

    // ---- the piece ----

    /** Phrase beginnings: a window of speech after a pause (and the very first one). */
    private fun starts(w: FloatArray, thr: Float): List<Int> {
        val s = ArrayList<Int>()
        for (i in w.indices) if (w[i] >= thr && (i == 0 || w[i - 1] < thr)) s.add(maxOf(0, i - 2))
        return s
    }

    /** Where a piece from window [s] ends: the latest pause 6–10 s on (some of it kept), else the quietest moment. */
    private fun endOf(w: FloatArray, thr: Float, s: Int): Int {
        val from = s + MIN_WIN
        val to = minOf(w.size, s + MAX_WIN)
        if (from >= to) {
            // less than 6 s left: up to the last speech
            val last = w.indexOfLast { it >= thr }
            return minOf(w.size, maxOf(last + 5, s + 1))
        }
        for (e in to - 1 downTo from) {
            if (e >= 3 && (e - 3..e).all { w[it] < thr }) return e + 1
        }
        return (from until to).minByOrNull { w[it] }!! + 1
    }

    /** How good a piece is: its speech over its pauses (dB), less overload, more for its length. */
    private fun score(x: FloatArray, w: FloatArray, thr: Float, s: Int, e: Int, floor: Float): Float {
        var sp = 0.0; var ns = 0; var q = 0.0; var nq = 0; var clip = 0
        for (i in s until e) {
            val v = w[i].toDouble() * w[i]
            if (w[i] >= thr) { sp += v; ns++ } else { q += v; nq++ }
            var m = 0f
            for (k in i * WIN until (i + 1) * WIN) m = maxOf(m, abs(x[k]))
            if (m >= 0.985f) clip++
        }
        if (ns == 0) return -1000f
        val noise = if (nq > 0) q / nq else floor.toDouble() * floor
        val snr = 10 * log10((sp / ns) / maxOf(noise, 1e-12))
        return (snr - 3 * clip + 0.5 * (e - s) / 50.0).toFloat()
    }

    /**
     * The voice: 6–10 s of the cleaned recording [x] — from a phrase's beginning to a pause — by
     * [part], levelled (speech at −20 dBFS, peaks under −1 dB) and faded in and out.
     */
    fun pick(x: FloatArray, part: Int = PART_AUTO): FloatArray {
        val w = windows(x)
        val peak = w.maxOrNull() ?: 0f
        if (peak < 1e-3f) throw Failure("в записи тишина")
        val thr = threshold(w)
        val floor = percentile(w, 0.1f)
        val st = starts(w, thr).ifEmpty { listOf(0) }
        val first = st.first()
        val last = w.indexOfLast { it >= thr }
        fun near(want: Int) = st.filter { it <= maxOf(first, last - 100) }.ifEmpty { st }.minByOrNull { abs(it - want) }!!
        val s = when (part) {
            PART_START -> first
            PART_MIDDLE -> near((first + last) / 2 - MAX_WIN / 2)
            PART_END -> near(last - MAX_WIN + 20)
            else -> {
                // the cleanest piece among those that can be 6 s long (the first one if none can)
                val full = st.filter { it + MIN_WIN <= w.size }.ifEmpty { listOf(first) }
                full.maxByOrNull { b -> score(x, w, thr, b, endOf(w, thr, b), floor) }!!
            }
        }
        val e = endOf(w, thr, s)
        val clip = x.copyOfRange(s * WIN, minOf(x.size, e * WIN))
        if (clip.size < RATE * 3) throw Failure("нужно хотя бы 3 секунды чистой речи")
        return level(clip)
    }

    /** Speech at −20 dBFS (RMS of its loud windows), peaks no higher than −1 dB; 10 ms fades. */
    fun level(clip: FloatArray): FloatArray {
        val w = windows(clip)
        val thr = threshold(w)
        var ms = 0.0; var n = 0
        for (v in w) if (v >= thr) { ms += v.toDouble() * v; n++ }
        val rms = if (n > 0) sqrt(ms / n).toFloat() else 0f
        val pk = clip.maxOfOrNull { abs(it) } ?: 0f
        if (rms <= 0f || pk <= 0f) return clip
        val g = minOf(0.1f / rms, 0.89f / pk)
        val out = FloatArray(clip.size) { clip[it] * g }
        val f = minOf(FADE, out.size / 4)
        for (i in 0 until f) { val k = i.toFloat() / f; out[i] *= k; out[out.size - 1 - i] *= k }
        return out
    }

    /** Seconds of [x] at the voice's rate. */
    fun seconds(x: FloatArray) = x.size.toFloat() / RATE
}
