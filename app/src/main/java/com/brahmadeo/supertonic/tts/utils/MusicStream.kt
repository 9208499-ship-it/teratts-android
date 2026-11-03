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
 * The user's list (a folder, several files): its songs one after another, round and round.
 */
class MusicStream private constructor(private val sources: List<(MediaExtractor) -> Unit>,
                                      private var ex: MediaExtractor, private var codec: MediaCodec,
                                      private var srcIdx: Int,
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
                    val rate = srcRate
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val samples = if (info.size > 0) {
                        val b = codec.getOutputBuffer(outIdx)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        toMono(b.slice().order(ByteOrder.LITTLE_ENDIAN))
                    } else null
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) {
                        if (++emptyLoops > 3 * sources.size && samples == null) return null
                        if (sources.size > 1) {
                            // the end of a song of the list: the next one
                            if (!nextSource()) return if (samples != null && samples.isNotEmpty()) samples to rate else null
                        } else {
                            // the end of the track: round again from the start
                            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            inputDone = false
                        }
                    }
                    if (samples != null && samples.isNotEmpty()) { emptyLoops = 0; return samples to rate }
                }
            }
        }
        return null
    }

    /** Opens the next song of the list that can be read; false if none can. */
    private fun nextSource(): Boolean {
        for (k in 1..sources.size) {
            val i = (srcIdx + k) % sources.size
            val o = openSource(sources[i]) ?: continue
            try { codec.stop() } catch (_: Exception) { }
            try { codec.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
            ex = o.first; codec = o.second; srcIdx = i
            inputDone = false
            readFormat(o.third)
            return true
        }
        return false
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
            val sources = BackgroundMusic.sources(c, t)
            for ((i, s) in sources.withIndex()) {
                val o = openSource(s) ?: continue
                return MusicStream(sources, o.first, o.second, i, targetRate, gain).also { it.readFormat(o.third) }
            }
            return null
        }

        /** A song opened for decoding: its extractor, decoder and format; null if it cannot be read. */
        private fun openSource(set: (MediaExtractor) -> Unit): Triple<MediaExtractor, MediaCodec, MediaFormat>? {
            val ex = MediaExtractor()
            return try {
                set(ex)
                val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ex.selectTrack(track)
                val format = ex.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
                Triple(ex, codec, format)
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
