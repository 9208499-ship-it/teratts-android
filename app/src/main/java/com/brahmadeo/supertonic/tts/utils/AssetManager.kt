package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TeraTTS v2 assets (TeraSpace/TeraTTSv2, pinned revision).
 *
 * Local layout (unchanged from the Supertonic fork so the rest of the app works as is):
 *   files/<MODEL_VERSION>/onnx/          four ONNX graphs + unicode_indexer.json
 *   files/<MODEL_VERSION>/voice_styles/  <voice>.json (converted from style_*.npy)
 */
object AssetManager {
    private const val TAG = "AssetManager"
    const val MODEL_VERSION = "tera2"
    private const val REVISION = "f05ea799094571a3553904a555df3834fb0b963b"
    private const val BASE_URL = "https://huggingface.co/TeraSpace/TeraTTSv2/resolve/$REVISION"
    private const val INT8_SAMPLER_URL =
        "https://github.com/9208499-ship-it/teratts-android/releases/download/models-v1/sampler_distilled_int8.onnx"
    /** The original fp32 sampler (256 MB) is no longer used once the int8 one is present. */
    private const val OLD_SAMPLER = "onnx/sampler_distilled_cfg3_8step.onnx"

    /** Voices shipped with TeraTTS v2; ru_f1 and ru_m5 are the recommended Russian ones. */
    val VOICES = listOf(
        "ru_f1", "ru_m5", "ru_f2", "ru_m1",
        "eng_f3", "eng_f5", "eng_m3", "eng_m4", "eng_f4_whisper", "eng_m2_whisper"
    )
    /**
     * Supertonic 3 voice styles (Supertone, OpenRAIL-M). TeraTTS keeps the
     * parent model's voice space, so these ten work as-is — verified by ear.
     */
    private const val SUPERTONIC_URL = "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles"
    val SUPERTONIC_VOICES = listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")
    private fun supertonicLocal(name: String) = "voice_styles/st3_$name.json"

    const val DEFAULT_VOICE_FILE = "ru_f1.json"
    const val DEFAULT_VOICE_2_FILE = "ru_m5.json"

    /** remote path → local path */
    private val MODEL_FILES = listOf(
        "models/text_encoder.onnx" to "onnx/text_encoder.onnx",
        "models/duration_predictor.onnx" to "onnx/duration_predictor.onnx",
        // int8 sampler built from TeraTTSv2 by tools/quantize_tera2.py, hosted with the app's releases
        "$INT8_SAMPLER_URL" to "onnx/sampler_distilled_int8.onnx",
        "models/vocoder.onnx" to "onnx/vocoder.onnx",
        "unicode_indexer.json" to "onnx/unicode_indexer.json"
    )

    private fun localTargets(): List<String> =
        MODEL_FILES.map { it.second } + VOICES.map { "voice_styles/$it.json" } +
            SUPERTONIC_VOICES.map { supertonicLocal(it) }

    fun isReady(context: Context): Boolean {
        val baseDir = File(context.filesDir, MODEL_VERSION)
        if (!baseDir.exists()) return false
        val ready = localTargets().all { File(baseDir, it).exists() }
        if (ready) removeOldSampler(context)
        return ready
    }

    /** Free 256 MB: delete the fp32 sampler once the int8 one is in place. */
    private fun removeOldSampler(context: Context) {
        val baseDir = File(context.filesDir, MODEL_VERSION)
        val old = File(baseDir, OLD_SAMPLER)
        if (old.exists() && File(baseDir, "onnx/sampler_distilled_int8.onnx").exists()) {
            if (old.delete()) Log.i(TAG, "Removed the fp32 sampler (int8 in use)")
        }
    }

    suspend fun download(context: Context, onProgress: (String, Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val baseDir = File(context.filesDir, MODEL_VERSION)
            baseDir.mkdirs()
            val total = (MODEL_FILES.size + VOICES.size + SUPERTONIC_VOICES.size).toFloat()
            var step = 0

            for ((remote, local) in MODEL_FILES) {
                val target = File(baseDir, local)
                onProgress("Downloading ${target.name}...", step++ / total)
                if (!target.exists()) fetch(if (remote.startsWith("https://")) remote else "$BASE_URL/$remote", target)
            }
            removeOldSampler(context)

            // Voice styles: two small .npy files per voice → one Supertonic-style JSON.
            for (voice in VOICES) {
                val target = File(baseDir, "voice_styles/$voice.json")
                onProgress("Voice $voice...", step++ / total)
                if (target.exists()) continue
                val tmpDir = File(context.cacheDir, "styles/$voice").apply { mkdirs() }
                val ttl = File(tmpDir, "style_ttl.npy")
                val dp = File(tmpDir, "style_dp.npy")
                fetch("$BASE_URL/styles/$voice/style_ttl.npy", ttl)
                fetch("$BASE_URL/styles/$voice/style_dp.npy", dp)
                writeStyleJson(readNpy(ttl.readBytes()), readNpy(dp.readBytes()), target)
                tmpDir.deleteRecursively()
            }
            // Supertonic 3 styles are already in the JSON layout the engine reads.
            for (name in SUPERTONIC_VOICES) {
                val target = File(baseDir, supertonicLocal(name))
                onProgress("Voice st3_$name...", step++ / total)
                if (!target.exists()) fetch("$SUPERTONIC_URL/$name.json", target)
            }
            onProgress("Ready", 1.0f)
        }
    }

    /** Download to a .part file first, so an interrupted download never looks complete. */
    private fun fetch(url: String, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            Log.d(TAG, "Downloading $url")
            URL(url).openStream().use { input ->
                FileOutputStream(part).use { output -> input.copyTo(output) }
            }
            if (!part.renameTo(target)) throw java.io.IOException("rename failed: $part")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $url", e)
            part.delete()
            throw e
        }
    }

    internal class Npy(val shape: IntArray, val data: FloatArray)

    /** Minimal .npy reader: little-endian float32/float64, C order. */
    internal fun readNpy(bytes: ByteArray): Npy {
        require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") { "not a .npy file" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[6].toInt()
        val (headerLen, headerStart) = if (major == 1) (bb.getShort(8).toInt() and 0xFFFF) to 10 else bb.getInt(8) to 12
        val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
        require(!header.contains("'fortran_order': True")) { "Fortran-ordered .npy" }
        val descr = Regex("'descr':\\s*'([^']+)'").find(header)?.groupValues?.get(1) ?: error("no descr")
        val shape = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.toInt() }?.toIntArray()
            ?: error("no shape")
        val n = shape.fold(1) { a, b -> a * b }
        bb.position(headerStart + headerLen)
        val data = FloatArray(n)
        when (descr) {
            "<f4" -> for (i in 0 until n) data[i] = bb.float
            "<f8" -> for (i in 0 until n) data[i] = bb.double.toFloat()
            else -> error("unsupported dtype $descr")
        }
        return Npy(shape, data)
    }

    /** Same JSON layout as Supertonic voice styles: {style_ttl:{data,dims,type}, style_dp:{...}}. */
    internal fun writeStyleJson(ttl: Npy, dp: Npy, target: File) {
        fun component(a: Npy): String {
            require(a.shape.size == 3) { "expected a 3-D style tensor" }
            val (d0, d1, d2) = Triple(a.shape[0], a.shape[1], a.shape[2])
            val sb = StringBuilder(a.data.size * 12)
            sb.append("{\"data\":[")
            for (i in 0 until d0) {
                if (i > 0) sb.append(',')
                sb.append('[')
                for (j in 0 until d1) {
                    if (j > 0) sb.append(',')
                    sb.append('[')
                    for (k in 0 until d2) {
                        if (k > 0) sb.append(',')
                        sb.append(a.data[(i * d1 + j) * d2 + k])
                    }
                    sb.append(']')
                }
                sb.append(']')
            }
            sb.append("],\"dims\":[$d0,$d1,$d2],\"type\":\"float32\"}")
            return sb.toString()
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        part.writeText("{\"style_ttl\":${component(ttl)},\"style_dp\":${component(dp)}}")
        if (!part.renameTo(target)) throw java.io.IOException("rename failed: $part")
    }

    // ---- Homograph resolver (silero-stress, MIT), bundled in APK assets ----

    private const val HOMO_ASSET_DIR = "homosolver"
    private const val HOMO_VERSION = "silero-stress-1.5-a"
    private val HOMO_FILES = listOf(
        "homo_encoder.onnx", "homo_head.onnx", "vocab.json", "homodict.json", "phrases.json", "meta.json"
    )

    /**
     * Copy the bundled homograph resolver next to the TTS models (files/<v>/onnx/homo),
     * where the Rust engine picks it up on init. Idempotent and cheap after the first run.
     * Must run before SupertonicTTS.initialize().
     */
    @Synchronized
    fun ensureHomosolver(context: Context) {
        // called right before every engine start: the cores setting goes with it
        CpuCores.applyPref(context)
        val dir = File(context.filesDir, "$MODEL_VERSION/onnx/homo")
        val stamp = File(dir, ".version")
        if (stamp.exists() && stamp.readText() == HOMO_VERSION && HOMO_FILES.all { File(dir, it).exists() }) return
        try {
            val bundled = context.assets.list(HOMO_ASSET_DIR)?.toSet() ?: emptySet()
            if (!bundled.containsAll(HOMO_FILES)) {
                Log.w(TAG, "Homograph resolver not bundled in this build")
                return
            }
            dir.mkdirs()
            for (name in HOMO_FILES) {
                val part = File(dir, "$name.part")
                context.assets.open("$HOMO_ASSET_DIR/$name").use { input ->
                    FileOutputStream(part).use { output -> input.copyTo(output) }
                }
                if (!part.renameTo(File(dir, name))) throw java.io.IOException("rename failed: $part")
            }
            stamp.writeText(HOMO_VERSION)
            Log.i(TAG, "Homograph resolver installed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install homograph resolver", e)
        }
    }

    fun delete(context: Context) {
        File(context.filesDir, MODEL_VERSION).takeIf { it.exists() }?.deleteRecursively()
    }

    // ---- Shtorm PocketTTS RU (ArtShtorm, CC BY 4.0; Kyutai Pocket TTS): the second model ----
    // Speaks in the voice of a short recording (.wav next to the voices); downloaded on request.

    private const val PREFS = "SupertonicPrefs"
    private const val KEY_ENGINE = "engine_model"
    const val ENGINE_TERA = "tera"
    const val ENGINE_SHTORM = "shtorm"
    /** The model files (the same export MyTTS uses), hosted with the app's releases. */
    private const val SHTORM_URL = "https://github.com/9208499-ship-it/teratts-android/releases/download/models-shtorm-v1"
    private class Remote(val name: String, val size: Long, val sha: String)
    private val SHTORM_FILES = listOf(
        Remote("flow_lm_flow.onnx", 39081890L, "5b47a1593b8bc43941f539497b729bb995335b3077e863f315275287d2a2edba"),
        // the main graph in 8 bits (1.10): 76 MB instead of 302, faster; not told apart by ear
        Remote("flow_lm_main_int8.onnx", 76147677L, "1a5cbf7eae70613a032eb5423513c4906203470f0dadee40d74bc6531b87d52a"),
        Remote("mimi_decoder.onnx", 41410972L, "c5aa9130f66b6100d57044094cc08665fb129944e77f92fe4a1a601992194331"),
        Remote("mimi_encoder.onnx", 39282087L, "9a40beeac20e2d4d1ee9d1af2007c02960fb4b01bec6a46cd9b94cfb396e2c5a"),
        Remote("text_conditioner.onnx", 16388309L, "48f14f835e891798a29abd76838e512b2453b0cb2dc2892779e47e2b4a3e5cf6"),
        Remote("tokenizer.model", 316834L, "95d0b8e61951408c7744e09fb5b87ff8e318612b0c8168cc593ea0a812f34f81")
    )
    /** Recordings of TeraTTS's own voices for Shtorm to speak in (made by TeraTTS itself). */
    val SHTORM_VOICES = listOf("ru_m5", "ru_f1", "ru_m1", "ru_f2")
    const val SHTORM_DEFAULT_VOICE = "ru_m5.wav"

    /** Prefix for what is measured per voice (tempo, loudness): the same voice differs between the models. */
    @Volatile var engineKey: String = ""
        private set

    private fun shtormDir(context: Context) = File(context.filesDir, "$MODEL_VERSION/shtorm")
    private fun voiceDir(context: Context) = File(context.filesDir, "$MODEL_VERSION/voice_styles")

    /** The full main graph (32 bits) of 1.8–1.9.x: still works until the 8-bit one is downloaded. */
    private val SHTORM_MAIN_FP32 = Remote("flow_lm_main.onnx", 302364167L, "1c26f5046b68a30d6f4894e486ea5f4619c3b8fd961114da4d0faaf1a3fcbaac")

    private fun present(context: Context, f: Remote) = File(shtormDir(context), f.name).length() == f.size

    fun shtormReady(context: Context) = SHTORM_FILES.all { present(context, it) } ||
        (SHTORM_FILES.filter { it.name != "flow_lm_main_int8.onnx" }.all { present(context, it) } && present(context, SHTORM_MAIN_FP32))

    /** Downloaded before 1.10: the full main graph, the 8-bit one (76 MB) is to be fetched. */
    fun shtormNeedsInt8(context: Context) = shtormReady(context) && !SHTORM_FILES.all { present(context, it) }

    fun shtormSizeMb(context: Context): Long =
        (shtormDir(context).listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)

    /** The model chosen in Settings (TeraTTS unless Shtorm is chosen and downloaded). */
    fun engine(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.getString(KEY_ENGINE, ENGINE_TERA) ?: ENGINE_TERA
        if (e == "st3") dropSupertonic3(context)
        return if (e == ENGINE_SHTORM && shtormReady(context)) ENGINE_SHTORM else ENGINE_TERA
    }

    fun setEngine(context: Context, engine: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine).apply()

    /** Supertonic 3 was the second model in 1.6.21–1.7.x: its files (≈400 MB) go, TeraTTS takes over. */
    private fun dropSupertonic3(context: Context) {
        File(context.filesDir, "$MODEL_VERSION/st3").takeIf { it.exists() }?.deleteRecursively()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, ENGINE_TERA).apply()
    }

    /** The folder of the model to start the engine with — every engine start goes through here. */
    fun modelDir(context: Context): File {
        VoiceRhythm.load(context)
        loadCommaQuotes(context)
        val shtorm = engine(context) == ENGINE_SHTORM
        // the teacher has its own loudness and tempo: what was learned for the fast sampler must not
        // be applied to it (ka3ak78: «звук идёт волной» with the teacher on)
        engineKey = if (shtorm) "shtorm:" else if (teacherOn(context)) "tt:" else ""
        return if (shtorm) shtormDir(context) else File(context.filesDir, "$MODEL_VERSION/onnx")
    }

    /** Is the engine (as last started) Shtorm? */
    val shtormActive: Boolean get() = engineKey.startsWith("shtorm")

    /** Voice files the current model speaks with: recordings (.wav) for Shtorm, voice files (.json) for TeraTTS. */
    fun voiceExtension(context: Context) = if (engine(context) == ENGINE_SHTORM) ".wav" else ".json"

    /**
     * The voice for the model that runs: the same name in its form (ru_m5.json ↔ ru_m5.wav) if there
     * is one, else the default voice of that model. A mix ("a;b;0.5") is TeraTTS's only.
     */
    fun voiceForEngine(path: String): String {
        if (path.isEmpty()) return path
        val first = path.substringBefore(';')
        val f = File(first)
        val dir = f.parentFile ?: return path
        val stem = f.name.substringBeforeLast('.')
        return if (shtormActive) {
            listOf(f.takeIf { it.name.endsWith(".wav") }, File(dir, "$stem.wav"), File(dir, SHTORM_DEFAULT_VOICE))
                .firstOrNull { it != null && it.isFile }?.path ?: first
        } else {
            if (first.endsWith(".json")) return path
            listOf(File(dir, "$stem.json"), File(dir, DEFAULT_VOICE_2_FILE)).firstOrNull { it.isFile }?.path ?: path
        }
    }

    /**
     * Downloads Shtorm (≈215 MB, checked) and the recordings of TeraTTS's voices for it; for a
     * Shtorm of 1.8–1.9.x only the 8-bit main graph (76 MB), then the full one (302 MB) goes.
     */
    suspend fun downloadShtorm(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = shtormDir(context).apply { mkdirs() }
        val missing = SHTORM_FILES.filter { File(dir, it.name).length() != it.size }
        val total = missing.sumOf { it.size }.coerceAtLeast(1L)
        var done = 0L
        for (f in missing) {
            val target = File(dir, f.name)
            fetchCounting("$SHTORM_URL/${f.name}", target) { n -> onProgress(((done + n).toFloat() / total).coerceIn(0f, 0.97f)) }
            if (target.length() != f.size || sha256(target) != f.sha) {
                target.delete()
                throw java.io.IOException("файл ${f.name} повреждён, попробуйте ещё раз")
            }
            done += f.size
        }
        // the 8-bit main graph is there: the full one only takes space
        File(dir, SHTORM_MAIN_FP32.name).takeIf { it.exists() }?.delete()
        val voices = voiceDir(context).apply { mkdirs() }
        for (v in SHTORM_VOICES) {
            val target = File(voices, "$v.wav")
            if (target.length() > 1000) continue
            fetchCounting("$SHTORM_URL/$v.wav", target) { }
        }
        onProgress(1f)
    }

    /** Frees the space Shtorm takes (back to TeraTTS); recorded voices stay. */
    fun deleteShtorm(context: Context) {
        setEngine(context, ENGINE_TERA)
        shtormDir(context).deleteRecursively()
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchCounting(url: String, target: File, onBytes: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            URL(url).openStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var n = 0L
                    var lastReport = 0L
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        output.write(buf, 0, r); n += r
                        if (n - lastReport > (1 shl 20)) { lastReport = n; onBytes(n) }
                    }
                }
            }
            if (!part.renameTo(target)) throw java.io.IOException("rename failed: $part")
        } catch (e: Exception) {
            part.delete(); throw e
        }
    }

    // ---- TeraTTS: «учитель» (teacher sampler) and comma replacement ----
    private const val TEACHER_FILE = "sampler_teacher_8step.onnx"
    private const val TEACHER_MARK = "teacher.on"
    private const val TEACHER_SIZE = 256528347L
    private const val TEACHER_SHA = "b38b6334cdc5fa228f260710a41082d52a581b29c1a474f87e2a0f70887258fb"
    private const val TEACHER_URL =
        "https://github.com/9208499-ship-it/teratts-android/releases/download/models-v1/$TEACHER_FILE"
    private const val KEY_COMMA_QUOTES = "comma_quotes"

    private fun teraDir(context: Context) = File(context.filesDir, "$MODEL_VERSION/onnx")

    /** «Замена запятых» for TeraTTS (azimutspb's TeraTTS-GUI): on by default. */
    @Volatile var commaQuotes: Boolean = true
        private set

    fun loadCommaQuotes(context: Context) {
        commaQuotes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMMA_QUOTES, true)
    }

    fun setCommaQuotes(context: Context, on: Boolean) {
        commaQuotes = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMMA_QUOTES, on).apply()
    }

    fun teacherDownloaded(context: Context) = File(teraDir(context), TEACHER_FILE).length() == TEACHER_SIZE
    fun teacherOn(context: Context) = teacherDownloaded(context) && File(teraDir(context), TEACHER_MARK).exists()

    /** Switches the teacher on (downloading it once, 256 MB, checked) or off; the engine restarts after. */
    suspend fun setTeacher(context: Context, on: Boolean, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = teraDir(context)
        val mark = File(dir, TEACHER_MARK)
        if (!on) { mark.delete(); return@withContext }
        val file = File(dir, TEACHER_FILE)
        if (file.length() != TEACHER_SIZE) {
            fetchCounting(TEACHER_URL, file) { n -> onProgress((n.toFloat() / TEACHER_SIZE).coerceIn(0f, 0.99f)) }
            val sha = sha256(file)
            if (file.length() != TEACHER_SIZE || sha != TEACHER_SHA) {
                file.delete(); throw java.io.IOException("файл «учителя» повреждён, попробуйте ещё раз")
            }
        }
        mark.writeText("1")
        onProgress(1f)
    }

    /** Frees the 256 MB of the teacher. */
    fun deleteTeacher(context: Context) {
        File(teraDir(context), TEACHER_MARK).delete()
        File(teraDir(context), TEACHER_FILE).delete()
    }

    /** Remove Supertonic model directories left by earlier installs. */
    fun cleanupOldVersions(context: Context) {
        engine(context) // drops Supertonic 3's files if it was the chosen model
        listOf("v1", "v2", "v3").forEach { old ->
            val dir = File(context.filesDir, old)
            if (dir.exists()) {
                Log.i(TAG, "Cleaning up legacy model dir: $old")
                dir.deleteRecursively()
            }
        }
    }
}
