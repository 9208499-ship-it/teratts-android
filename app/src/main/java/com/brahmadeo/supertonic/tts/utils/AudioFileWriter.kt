package com.brahmadeo.supertonic.tts.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Where the engine's sound (PCM 16-bit mono) goes when a chapter is saved: phrase by phrase,
 * straight into the file. [finish] closes a complete file; [abort] closes it as it is (the
 * caller deletes it).
 */
interface AudioFileWriter {
    fun write(pcm: ByteArray)
    /** true if the file is complete and has sound in it */
    fun finish(): Boolean
    fun abort()

    companion object {
        /**
         * .m4a (AAC, 64 kbit/s: about 30 MB an hour of speech — WAV takes 150+), made by the
         * phone's own encoder. A phone without one (very rare) gets a .wav next to [m4a].
         */
        fun open(m4a: File, sampleRate: Int): Pair<AudioFileWriter, File> = try {
            AacWriter(m4a, sampleRate) to m4a
        } catch (e: Throwable) {
            Log.w("TeraExport", "no AAC encoder, saving WAV: ${e.message}")
            val wav = File(m4a.parentFile, m4a.name.substringBeforeLast('.') + ".wav")
            WavWriter(wav, sampleRate) to wav
        }
    }
}

/** WAV: the header is filled in at the end, when the size is known. */
class WavWriter(private val file: File, private val sampleRate: Int) : AudioFileWriter {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16).also { it.write(ByteArray(44)) }
    private var written = 0L
    override fun write(pcm: ByteArray) { out.write(pcm); written += pcm.size }
    override fun finish(): Boolean {
        out.close()
        if (written == 0L) return false
        WavUtils.writeHeader(file, written, sampleRate)
        return true
    }
    override fun abort() { try { out.close() } catch (_: Exception) { } }
}

/** PCM 16-bit mono → AAC-LC in an MP4 container (.m4a): Android's MediaCodec + MediaMuxer. */
class AacWriter(file: File, private val sampleRate: Int, bitRate: Int = 64_000) : AudioFileWriter {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var track = -1
    private var muxing = false
    private var samples = 0L
    private var released = false
    private val info = MediaCodec.BufferInfo()

    init {
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 15)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Throwable) {
            try { codec.release() } catch (_: Throwable) { }
            throw e
        }
    }

    private val muxer: MediaMuxer = try {
        MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (e: Throwable) {
        try { codec.release() } catch (_: Throwable) { }
        throw e
    }

    init {
        try {
            codec.start()
        } catch (e: Throwable) {
            try { codec.release() } catch (_: Throwable) { }
            try { muxer.release() } catch (_: Throwable) { }
            file.delete()
            throw e
        }
    }

    private fun timeUs() = samples * 1_000_000L / sampleRate

    override fun write(pcm: ByteArray) {
        val size = pcm.size and 1.inv()   // whole 16-bit samples
        var off = 0
        while (off < size) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx) ?: continue
                buf.clear()
                val n = minOf(buf.remaining() and 1.inv(), size - off)
                buf.put(pcm, off, n)
                codec.queueInputBuffer(idx, 0, n, timeUs(), 0)
                samples += n / 2
                off += n
            }
            drain(false)
        }
    }

    /** Takes what the encoder has made into the file; [end]: until the end of the stream. */
    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++idle > 300) return }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxing) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                }
                i >= 0 -> {
                    val out = codec.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (out != null && info.size > 0 && muxing) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun finish(): Boolean {
        var ok = false
        try {
            var idx = -1
            for (t in 0 until 100) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) break
                drain(false)
            }
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, timeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            ok = muxing && samples > 0
        } catch (e: Throwable) {
            Log.e("TeraExport", "AAC finish failed", e)
        } finally {
            ok = release() && ok
        }
        return ok
    }

    override fun abort() { release() }

    private fun release(): Boolean {
        if (released) return false
        released = true
        var ok = true
        try { codec.stop() } catch (_: Throwable) { }
        try { codec.release() } catch (_: Throwable) { }
        try { if (muxing) muxer.stop() } catch (e: Throwable) { ok = false }
        try { muxer.release() } catch (_: Throwable) { }
        return ok
    }
}
