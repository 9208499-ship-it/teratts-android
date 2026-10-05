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

    private class Npy(val shape: IntArray, val data: FloatArray)

    /** Minimal .npy reader: little-endian float32/float64, C order. */
    private fun readNpy(bytes: ByteArray): Npy {
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
    private fun writeStyleJson(ttl: Npy, dp: Npy, target: File) {
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
