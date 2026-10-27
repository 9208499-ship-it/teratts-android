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

    // ---- Supertonic 3 (Supertone, OpenRAIL-M): the second model to choose, downloaded on request ----

    private const val PREFS = "SupertonicPrefs"
    private const val KEY_ENGINE = "engine_model"
    const val ENGINE_TERA = "tera"
    const val ENGINE_ST3 = "st3"
    /**
     * Sped up for phones (teratts-st3-optimize.sh: 1×1 convolutions as MatMul — they then use every
     * core — and the vector estimator in int8), hosted with the app's releases; Supertone's own
     * files are the fallback (same inputs and outputs, so any mix works).
     */
    private const val ST3_FAST_URL = "https://github.com/9208499-ship-it/teratts-android/releases/download/models-st3-v1"
    private val ST3_URLS = listOf(
        ST3_FAST_URL,
        "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/aafc6e32416a594460b32413efc49d7fe4ce6d46/onnx"
    )
    private const val ST3_FAST_MARK = ".fast"

    private val ST3_FILES = listOf("tts.json", "unicode_indexer.json", "duration_predictor.onnx", "text_encoder.onnx",
        "vocoder.onnx", "vector_estimator.onnx")

    /** Prefix for what is measured per voice (tempo, loudness): the same voice differs between the models. */
    @Volatile var engineKey: String = ""
        private set

    private fun st3Dir(context: Context) = File(context.filesDir, "$MODEL_VERSION/st3")

    fun st3Ready(context: Context) = ST3_FILES.all { File(st3Dir(context), it).let { f -> f.exists() && f.length() > 0 } }

    /** Downloaded from the sped-up set (every model file); false — Supertone's original files, slower. */
    fun st3Fast(context: Context) = File(st3Dir(context), ST3_FAST_MARK).exists()

    /** Replaces Supertonic 3's original files with the sped-up ones (deleted, downloaded again). */
    suspend fun upgradeSt3(context: Context, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            st3Dir(context).listFiles()?.forEach { if (it.name.endsWith(".onnx") || it.name.endsWith(".opt")) it.delete() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ST3_INT8, false).apply()
        }
        downloadSt3(context, onProgress)
    }

    /** The model chosen in Settings (TeraTTS unless Supertonic 3 is chosen and downloaded). */
    fun engine(context: Context): String {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, ENGINE_TERA) ?: ENGINE_TERA
        return if (e == ENGINE_ST3 && st3Ready(context)) ENGINE_ST3 else ENGINE_TERA
    }

    fun setEngine(context: Context, engine: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine).apply()

    /** Supertonic 3's quality: denoising steps 5 / 8 (Supertone's default) / 12. Read on every phrase. */
    @Volatile var st3Steps: Int = 8
        private set
    private const val KEY_ST3_STEPS = "st3_steps"

    /** Supertonic 3 stress: 0 — plain text, it places stress itself; 1 — marks; 2 — marks + as it sounds. */
    @Volatile var st3Stress: Int = 0
        private set
    private const val KEY_ST3_STRESS = "st3_stress"

    fun setSt3Stress(context: Context, mode: Int) {
        st3Stress = mode.coerceIn(0, 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ST3_STRESS, st3Stress).apply()
    }

    fun setSt3Steps(context: Context, steps: Int) {
        st3Steps = steps.coerceIn(5, 16)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ST3_STEPS, st3Steps).apply()
    }

    /** The folder of the model to start the engine with — every engine start goes through here. */
    fun modelDir(context: Context): File {
        st3Steps = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ST3_STEPS, 8).coerceIn(5, 16)
        st3Stress = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ST3_STRESS, 0).coerceIn(0, 2)
        VoiceRhythm.load(context)
        val st3 = engine(context) == ENGINE_ST3
        engineKey = if (st3) "st3:" else ""
        return if (st3) st3Dir(context) else File(context.filesDir, "$MODEL_VERSION/onnx")
    }

    /** Downloads Supertonic 3 (~400 MB; what is there already is not downloaded again). */
    suspend fun downloadSt3(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = st3Dir(context).apply { mkdirs() }
        val lengths = HashMap<String, Long>()
        for (f in ST3_FILES) if (!File(dir, f).exists()) lengths[f] = contentLength(f)
        val total = lengths.values.sum().coerceAtLeast(1L)
        var done = 0L
        // the sped-up set only if every model file came from it (the marker tells the settings)
        var allFast = File(dir, ST3_FAST_MARK).exists() || ST3_FILES.none { it.endsWith(".onnx") && File(dir, it).exists() }
        for (f in ST3_FILES) {
            val target = File(dir, f)
            if (target.exists() && target.length() > 0) continue
            var last: Exception? = null
            for (base in ST3_URLS) {
                try {
                    fetchCounting("$base/$f", target) { n -> onProgress(((done + n).toFloat() / total).coerceIn(0f, 1f)) }
                    if (base != ST3_FAST_URL && f.endsWith(".onnx")) allFast = false
                    last = null
                    break
                } catch (e: Exception) { last = e }
            }
            last?.let { throw it }
            done += target.length()
        }
        val mark = File(dir, ST3_FAST_MARK)
        if (allFast) mark.writeText("models-st3-v1") else mark.delete()
        onProgress(1f)
    }

    private fun contentLength(f: String): Long {
        for (base in ST3_URLS) {
            try {
                val c = URL("$base/$f").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"; c.instanceFollowRedirects = true
                val n = c.contentLengthLong; c.disconnect()
                if (n > 0) return n
            } catch (_: Exception) { }
        }
        return 0L
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

    /** Frees the space Supertonic 3 takes (back to TeraTTS). */
    fun deleteSt3(context: Context) {
        setEngine(context, ENGINE_TERA)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ST3_INT8, false).apply()
        st3Dir(context).deleteRecursively()
    }

    // ---- Supertonic 3's precision: the main part (vector estimator) in 32 bits or in 8 ----
    // The engine loads vector_estimator.onnx; the other variant waits beside it under its own
    // name, so switching back and forth is two renames (the 8-bit one is downloaded once, 66 MB).

    private const val KEY_ST3_INT8 = "st3_int8"
    private const val VE = "vector_estimator.onnx"
    private const val VE_FP32 = "vector_estimator_fp32.onnx"
    private const val VE_INT8 = "vector_estimator_int8.onnx"

    /** 8 bits chosen (faster on weak phones, slightly less exact sound). */
    fun st3Int8(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ST3_INT8, false)

    /** Switches the precision; downloads the 8-bit part the first time. The engine must be restarted after. */
    suspend fun setSt3Int8(context: Context, on: Boolean, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (st3Int8(context) == on) return@withContext
        val dir = st3Dir(context)
        val active = File(dir, VE)
        val wanted = File(dir, if (on) VE_INT8 else VE_FP32)
        if (!wanted.exists() || wanted.length() == 0L) {
            // only the sped-up release has it (Supertone has no 8-bit model; the 32-bit one is in both)
            val urls = if (on) listOf("$ST3_FAST_URL/$VE_INT8") else ST3_URLS.map { "$it/$VE" }
            var last: Exception? = null
            for (u in urls) {
                try { fetchCounting(u, wanted) { n -> onProgress((n / 70e6f).coerceIn(0f, 0.99f)) }; last = null; break }
                catch (e: Exception) { last = e }
            }
            last?.let { throw it }
        }
        val aside = File(dir, if (on) VE_FP32 else VE_INT8)
        aside.delete()
        if (active.exists() && !active.renameTo(aside)) throw java.io.IOException("rename failed: $active")
        if (!wanted.renameTo(active)) throw java.io.IOException("rename failed: $wanted")
        // the graph the engine optimised for the other variant must not be taken for this one
        File(dir, "$VE.opt").delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ST3_INT8, on).apply()
        onProgress(1f)
    }

    fun st3SizeMb(context: Context): Long =
        (st3Dir(context).listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)

    /** Remove Supertonic model directories left by earlier installs. */
    fun cleanupOldVersions(context: Context) {
        listOf("v1", "v2", "v3").forEach { old ->
            val dir = File(context.filesDir, old)
            if (dir.exists()) {
                Log.i(TAG, "Cleaning up legacy model dir: $old")
                dir.deleteRecursively()
            }
        }
    }
}
