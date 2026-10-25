package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteOrder

/**
 * The background music for a saved file: the track decoded piece by piece (never whole —
 * an hour of music would not fit in memory), made mono at the speech's rate, looped, and
 * mixed under the speech at the volume set for the player. It fades in over the first seconds.
 */
class MusicStream private constructor(private val ex: MediaExtractor, private val codec: MediaCodec,
                                      targetRate: Int, private val gain: Float) : Closeable {
    private val resampler = Resampler(targetRate) { decodeMore() }
    private val fadeFrames = targetRate * 3L
    private var mixed = 0L
    private var srcRate = 44100
    private var channels = 1
    private var floatPcm = false
    private var inputDone = false
    private var emptyLoops = 0

    /** Mixes the music into [pcm] (16-bit mono, little-endian) in place. */
    fun mixInto(pcm: ByteArray) {
        val n = pcm.size / 2
        for (i in 0 until n) {
            val m = resampler.next()
            val fade = if (mixed < fadeFrames) mixed.toFloat() / fadeFrames else 1f
            mixed++
            val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort()
            val v = (s + m * gain * fade).toInt().coerceIn(-32768, 32767)
            pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte()
        }
    }

    /** The next decoded piece of the track (mono), going round to the start at its end; null if it cannot be read. */
    private fun decodeMore(): Pair<ShortArray, Int>? {
        val info = MediaCodec.BufferInfo()
        var guard = 0
        while (guard++ < 2000) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime.coerceAtLeast(0), 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(codec.outputFormat)
                outIdx >= 0 -> {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val samples = if (info.size > 0) {
                        val b = codec.getOutputBuffer(outIdx)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        toMono(b.slice().order(ByteOrder.LITTLE_ENDIAN))
                    } else null
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) {
                        // the end of the track: round again from the start
                        if (++emptyLoops > 3 && samples == null) return null
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        inputDone = false
                    }
                    if (samples != null && samples.isNotEmpty()) { emptyLoops = 0; return samples to srcRate }
                }
            }
        }
        return null
    }

    private fun readFormat(f: MediaFormat) {
        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    }

    private fun toMono(b: java.nio.ByteBuffer): ShortArray {
        if (floatPcm) {
            val fb = b.asFloatBuffer(); val frames = fb.remaining() / channels
            return ShortArray(frames) { i ->
                var s = 0f; for (ch in 0 until channels) s += fb.get(i * channels + ch)
                (s / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        val sb = b.asShortBuffer(); val frames = sb.remaining() / channels
        return ShortArray(frames) { i ->
            var s = 0; for (ch in 0 until channels) s += sb.get(i * channels + ch)
            (s / channels).toShort()
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { ex.release() } catch (_: Exception) { }
    }

    companion object {
        /** The chosen track at the player's volume, or null (music off, nothing chosen, unreadable). */
        fun open(c: Context, targetRate: Int): MusicStream? {
            val t = BackgroundMusic.track(c) ?: return null
            val gain = BackgroundMusic.volume(c) / 100f
            if (gain <= 0f) return null
            val ex = MediaExtractor()
            return try {
                BackgroundMusic.setSource(c, t, ex)
                val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ex.selectTrack(track)
                val format = ex.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
                MusicStream(ex, codec, targetRate, gain).also { it.readFormat(format) }
            } catch (e: Exception) {
                android.util.Log.w("BackgroundMusic", "music not mixed: ${e.message}")
                try { ex.release() } catch (_: Exception) { }
                null
            }
        }
    }
}

/**
 * Turns pieces of mono audio at their own rate (from [more]: samples and their rate) into a
 * steady stream at [targetRate] — linear interpolation, enough for quiet background music.
 * Silence if [more] gives nothing.
 */
class Resampler(private val targetRate: Int, private val more: () -> Pair<ShortArray, Int>?) {
    private var cur = ShortArray(0)
    private var idx = 0.0
    private var step = 1.0
    private var dead = false

    fun next(): Float {
        while (idx + 1 >= cur.size) {
            if (dead) return 0f
            val p = more()
            if (p == null) { dead = true; return 0f }
            val last: Short = if (cur.isNotEmpty()) cur[cur.size - 1] else 0
            idx -= (cur.size - 1).coerceAtLeast(0)
            if (idx < 0) idx = 0.0
            cur = ShortArray(p.first.size + 1).also { it[0] = last; System.arraycopy(p.first, 0, it, 1, p.first.size) }
            step = p.second.toDouble() / targetRate
        }
        val i = idx.toInt()
        val f = idx - i
        val s = cur[i] * (1 - f) + cur[i + 1] * f
        idx += step
        return s.toFloat()
    }
}
