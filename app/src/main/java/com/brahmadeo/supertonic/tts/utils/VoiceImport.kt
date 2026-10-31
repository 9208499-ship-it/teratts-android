package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your own voice (timbre) added to the voice list:
 *  - a Supertonic-style .json ({"style_ttl": {...}, "style_dp": {...}}) — the voices made or
 *    mixed for Supertonic fit as they are, TeraTTS reads the same layout;
 *  - or the two TeraTTS files of a voice, style_ttl.npy and style_dp.npy, chosen together.
 * The sizes are checked (style_ttl 1×50×256, style_dp 1×8×16): a file that does not fit
 * would only make the engine fail later. Saved as "my_<name>.json" next to the other voices.
 *  - or a recording (wav / m4a / mp3 / ogg …) — a voice for Shtorm: it speaks in the voice of
 *    a few seconds of clean speech. 4–9 s of it, cut at a pause, saved as "my_<name>.wav".
 */
object VoiceImport {
    private val TTL = intArrayOf(1, 50, 256)
    private val DP = intArrayOf(1, 8, 16)

    class Failure(message: String) : Exception(message)

    private fun nameOf(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "voice"
    }

    private fun dir(context: Context) = File(context.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")

    /** A free file name "my_<name>.json" (letters, digits, "_"). */
    private fun target(context: Context, base: String, ext: String = ".json"): File {
        val clean = base.substringBeforeLast('.').replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').take(40)
            .ifEmpty { "voice" }
        val stem = "my_" + clean.removePrefix("my_")
        var f = File(dir(context), "$stem$ext")
        var k = 2
        while (f.exists()) { f = File(dir(context), "${stem}_$k$ext"); k++ }
        return f
    }

    private val AUDIO = listOf(".wav", ".m4a", ".mp3", ".ogg", ".opus", ".flac", ".aac", ".3gp", ".amr", ".webm", ".mp4")
    private const val RATE = 24000

    /** A recording → mono 24 kHz (Android's own decoders: anything the phone can play). */
    private fun decode(context: Context, uri: Uri): FloatArray {
        val ex = android.media.MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { ex.setDataSource(it.fileDescriptor) }
            ?: throw Failure("файл не читается")
        try {
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Failure("в файле нет звука")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = java.io.ByteArrayOutputStream()
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var pcmFloat = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        out.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (out.size() > rate * channels * 4 * 40) break // 40 s is plenty
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            val raw = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val samples = if (pcmFloat) FloatArray(raw.remaining() / 4) { raw.float }
                else FloatArray(raw.remaining() / 2) { raw.short / 32768f }
            val ch = channels.coerceAtLeast(1)
            val mono = FloatArray(samples.size / ch) { i -> var a = 0f; for (c in 0 until ch) a += samples[i * ch + c]; a / ch }
            if (rate == RATE) return mono
            // linear resampling is enough for a voice sample
            val n = (mono.size.toLong() * RATE / rate).toInt()
            return FloatArray(n) { i ->
                val t = i.toDouble() * rate / RATE
                val k = t.toInt().coerceAtMost(mono.size - 1)
                val f = (t - k).toFloat()
                mono[k] * (1 - f) + mono[(k + 1).coerceAtMost(mono.size - 1)] * f
            }
        } finally {
            ex.release()
        }
    }

    /** 4–9 s of the recording from the first sound, cut at its quietest 50 ms there (a phrase's end). */
    private fun sample(x: FloatArray): FloatArray {
        val win = RATE / 20
        val rms = FloatArray(x.size / win) { i -> var a = 0f; for (k in i * win until (i + 1) * win) a += x[k] * x[k]; kotlin.math.sqrt(a / win) }
        val peak = rms.maxOrNull() ?: 0f
        if (peak < 1e-3f) throw Failure("в записи тишина")
        val start = ((rms.indexOfFirst { it > peak * 0.05f }.coerceAtLeast(0) - 2).coerceAtLeast(0)) * win
        val from = start / win + 80
        val to = minOf(rms.size, start / win + 180)
        val end = if (to > from) (from until to).minByOrNull { rms[it] }!! * win else x.size
        val clip = x.copyOfRange(start, end.coerceAtMost(x.size))
        if (clip.size < RATE * 3) throw Failure("нужно хотя бы 3 секунды чистой речи")
        // a normal level: peaks at −3 dB
        val m = clip.maxOf { kotlin.math.abs(it) }
        return if (m > 0f) FloatArray(clip.size) { clip[it] * (0.7f / m) } else clip
    }

    private fun writeWav(f: File, x: FloatArray) {
        val data = java.nio.ByteBuffer.allocate(44 + x.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()); data.putInt(36 + x.size * 2); data.put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()); data.putInt(16); data.putShort(1); data.putShort(1)
        data.putInt(RATE); data.putInt(RATE * 2); data.putShort(2); data.putShort(16)
        data.put("data".toByteArray()); data.putInt(x.size * 2)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        part.writeBytes(data.array())
        if (!part.renameTo(f)) throw Failure("не удалось сохранить")
    }

    private fun checkComponent(o: JSONObject?, want: IntArray, what: String) {
        if (o == null) throw Failure("нет «$what»")
        val dims = o.optJSONArray("dims") ?: throw Failure("у «$what» нет dims")
        val got = IntArray(dims.length()) { dims.getInt(it) }
        if (!got.contentEquals(want)) throw Failure("«$what» размером ${got.joinToString("×")}, нужен ${want.joinToString("×")}")
        // the data must hold as many numbers as the dims say
        var n = 0
        fun count(a: JSONArray) { for (i in 0 until a.length()) { val x = a.get(i); if (x is JSONArray) count(x) else n++ } }
        count(o.optJSONArray("data") ?: throw Failure("у «$what» нет data"))
        if (n != want.fold(1) { a, b -> a * b }) throw Failure("в «$what» $n чисел вместо ${want.fold(1) { a, b -> a * b }}")
    }

    /** Adds the voice; returns its file name ("my_name.json"). Throws [Failure] with a reason. */
    fun import(context: Context, uris: List<Uri>): String {
        if (uris.isEmpty()) throw Failure("файлы не выбраны")
        val named = uris.map { nameOf(context, it) to it }
        // a recording: a voice for Shtorm
        val audio = named.firstOrNull { (n, _) -> AUDIO.any { n.endsWith(it, true) } }
        if (audio != null) {
            val clip = sample(try { decode(context, audio.second) } catch (e: Failure) { throw e }
                catch (e: Exception) { throw Failure("запись не читается: ${e.message}") })
            val f = target(context, audio.first, ".wav")
            writeWav(f, clip)
            return f.name
        }
        val json = named.firstOrNull { it.first.endsWith(".json", true) }
        if (json != null) {
            val text = context.contentResolver.openInputStream(json.second)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw Failure("файл не читается")
            val o = try { JSONObject(text) } catch (e: Exception) { throw Failure("это не JSON голоса") }
            checkComponent(o.optJSONObject("style_ttl"), TTL, "style_ttl")
            checkComponent(o.optJSONObject("style_dp"), DP, "style_dp")
            val f = target(context, json.first)
            f.parentFile?.mkdirs()
            f.writeText(text)
            return f.name
        }
        val ttl = named.firstOrNull { it.first.contains("ttl", true) && it.first.endsWith(".npy", true) }
        val dp = named.firstOrNull { it.first.contains("dp", true) && it.first.endsWith(".npy", true) }
        if (ttl == null || dp == null) throw Failure("выберите .json голоса или оба файла: style_ttl.npy и style_dp.npy")
        fun read(u: Uri) = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw Failure("файл не читается")
        val a = try { AssetManager.readNpy(read(ttl.second)) } catch (e: Exception) { throw Failure("style_ttl.npy: ${e.message}") }
        val b = try { AssetManager.readNpy(read(dp.second)) } catch (e: Exception) { throw Failure("style_dp.npy: ${e.message}") }
        if (!a.shape.contentEquals(TTL)) throw Failure("style_ttl размером ${a.shape.joinToString("×")}, нужен 1×50×256")
        if (!b.shape.contentEquals(DP)) throw Failure("style_dp размером ${b.shape.joinToString("×")}, нужен 1×8×16")
        // the voice's folder name is a better name than "style_ttl"
        val base = uris.first().pathSegments.dropLast(1).lastOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() && ':' !in it } ?: "voice"
        val f = target(context, base)
        AssetManager.writeStyleJson(a, b, f)
        return f.name
    }
}
