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
 *  - or a recording (wav / m4a / mp3 / ogg …) — a voice for Pocket TTS: it speaks in the voice of
 *    a few seconds of clean speech. Cleaned (VoicePrep: rumble, long pauses, level) and its
 *    cleanest 6–10 s taken, saved as "my_<name>.wav"; what cannot be mended is told ([notes]).
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

    /** A decoded recording: 24 kHz mono, its own rate, the overloaded peaks repaired in it. */
    private class Decoded(val x: FloatArray, val rate: Int, val clipRuns: Int)

    /** A recording → mono 24 kHz (Android's own decoders: anything the phone can play). */
    private fun decode(context: Context, uri: Uri): Decoded {
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
                        if (out.size() > rate * channels * 4 * 120) break // 2 min: start, middle and end to choose from
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
            // peaks cut flat by overload drawn round again — at the recording's own rate
            val (fixed, runs) = VoicePrep.declip(mono)
            // a proper resampler: the straight-line one used before dulled the highs and let what
            // lies above 12 kHz fold back as hiss — and Shtorm copies the sample's sound as it is
            return Decoded(resample(fixed, rate, RATE), rate, runs)
        } finally {
            ex.release()
        }
    }

    /** Band-limited (windowed-sinc) resampling, as the engine does it. */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = to.toDouble() / from
        val cutoff = minOf(ratio, 1.0) * 0.95
        val half = 16
        val span = Math.ceil(half / cutoff).toInt()
        val nOut = Math.round(x.size * ratio).toInt()
        return FloatArray(nOut) { n ->
            val t = n / ratio
            val c = Math.floor(t).toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in (c - span + 1)..(c + span)) {
                val arg = (t - k) * cutoff
                if (Math.abs(arg) >= half) continue
                val sinc = if (Math.abs(arg) < 1e-9) 1.0 else Math.sin(Math.PI * arg) / (Math.PI * arg)
                val h = sinc * (0.5 + 0.5 * Math.cos(Math.PI * arg / half))
                norm += h
                if (k >= 0 && k < x.size) acc += x[k] * h
            }
            if (Math.abs(norm) > 1e-9) (acc / norm).toFloat() else 0f
        }
    }

    /** Which piece of the recording becomes the voice: the cleanest one, or from its start, middle or end. */
    const val PART_AUTO = VoicePrep.PART_AUTO
    const val PART_START = VoicePrep.PART_START
    const val PART_MIDDLE = VoicePrep.PART_MIDDLE
    const val PART_END = VoicePrep.PART_END

    private fun sample(x: FloatArray, part: Int = PART_AUTO): FloatArray =
        try { VoicePrep.pick(x, part) } catch (e: VoicePrep.Failure) { throw Failure(e.message ?: "?") }

    /** Each recording imported in this run: what was found in it, and how long its voice piece is. */
    private val found = HashMap<String, Pair<VoicePrep.Report, Float>>()

    /** What cannot be mended in the voice [name]'s recording (empty: all is well, or not known). */
    fun notes(context: Context, name: String): List<String> {
        val (r, secs) = synchronized(found) { found[name] } ?: return emptyList()
        val n = ArrayList<String>()
        if (r.clipped) n.add(context.getString(com.brahmadeo.supertonic.tts.R.string.voice_note_clipped))
        if (r.muffled) n.add(context.getString(com.brahmadeo.supertonic.tts.R.string.voice_note_muffled))
        if (r.noisy) n.add(context.getString(com.brahmadeo.supertonic.tts.R.string.voice_note_noisy, r.snrDb.toInt().coerceAtLeast(0)))
        if (secs < 5.5f) n.add(context.getString(com.brahmadeo.supertonic.tts.R.string.voice_note_short))
        return n
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

    /** The last imported recording (decoded) and its voice file: for taking another piece of it. */
    @Volatile private var last: Pair<FloatArray, File>? = null

    /** Is [name] the recording just imported (its other pieces can be taken)? */
    fun canRecut(name: String) = last?.second?.name == name

    /** The voice [name] made again from another [part] of the recording just imported. */
    fun recut(context: Context, name: String, part: Int) {
        val (x, f) = last?.takeIf { it.second.name == name } ?: throw Failure("запись уже закрыта, импортируйте её заново")
        val clip = sample(x, part)
        writeWav(f, clip)
        synchronized(found) { found[name]?.let { found[name] = it.first to VoicePrep.seconds(clip) } }
        VoiceLoudness.forget(context, f.name); VoiceTempo.forget(context, f.name)
    }

    /** Removes an imported voice (it did not come out well). */
    fun delete(context: Context, name: String) {
        File(dir(context), name).delete()
        VoiceLoudness.forget(context, name); VoiceTempo.forget(context, name); VoiceGender.set(context, name, "")
        if (last?.second?.name == name) last = null
    }

    /** Forgets the recording kept for [recut]. */
    fun done() { last = null }

    /** Adds the voice; returns its file name ("my_name.json"). Throws [Failure] with a reason. */
    fun import(context: Context, uris: List<Uri>): String {
        if (uris.isEmpty()) throw Failure("файлы не выбраны")
        val named = uris.map { nameOf(context, it) to it }
        // a recording: a voice for Shtorm
        val audio = named.firstOrNull { (n, _) -> AUDIO.any { n.endsWith(it, true) } }
        if (audio != null) {
            val d = try { decode(context, audio.second) } catch (e: Failure) { throw e }
                catch (e: Exception) { throw Failure("запись не читается: ${e.message}") }
            // what cannot be mended is told; overload, the rumble, long hesitations and the level are mended
            val report = VoicePrep.check(d.x, d.rate, d.clipRuns)
            val x = VoicePrep.clean(d.x)
            val clip = sample(x)
            val f = target(context, audio.first, ".wav")
            writeWav(f, clip)
            synchronized(found) { found[f.name] = report to VoicePrep.seconds(clip) }
            // a voice of the same name may have been deleted: what was measured for it does not apply
            VoiceLoudness.forget(context, f.name); VoiceTempo.forget(context, f.name); VoiceGender.set(context, f.name, "")
            last = x to f
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
