package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/**
 * "Voice repair" for TeraTTS. A voice made for Supertonic 3 (Superclonica, imported .json) keeps
 * its timbre in TeraTTS, but its rhythm part (style_dp) does not suit TeraTTS's duration predictor:
 * words get skipped. The user can give such a voice the rhythm of a native TeraTTS voice — the
 * timbre stays its own (azimutspb's fix in TeraTTS-GUI). Chosen per voice file; Supertonic 3 is not
 * affected (it reads the voice as it is).
 */
object VoiceRhythm {
    private const val PREFS = "VoiceRhythm"
    /** Native Russian TeraTTS voices to borrow the rhythm from. */
    val DONORS = listOf("ru_m1", "ru_m5", "ru_f1", "ru_f2")

    /** "file\tdonor path" lines for the engine. */
    @Volatile var table: String = ""
        private set

    /** Only voices that are not TeraTTS's own. */
    fun applies(file: String) = file.endsWith(".json") && !file.startsWith("ru_") && !file.startsWith("eng_")

    fun donor(context: Context, file: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(file, "") ?: ""

    fun setDonor(context: Context, file: String, donor: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (donor.isEmpty()) p.remove(file) else p.putString(file, donor)
        p.commit()
        load(context)
    }

    fun load(context: Context) {
        val dir = File(context.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        table = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (file, v) ->
            val d = (v as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            File(dir, "$d.json").takeIf { it.isFile }?.let { "$file\t${it.absolutePath}" }
        }.sorted().joinToString("\n")
    }
}

/**
 * Voices made for TeraTTS only: the voice-cloning notebook fits the timbre (style_ttl) to TeraTTS's
 * decoder, and in Supertonic 3 the same numbers sound metallic. Such a file says so in its metadata
 * ("model": "teratts2", or an older "source": "... synthesized by TeraTTS v2"). Voices without
 * metadata (Superclonica, Supertonic) and the bundled ones are taken as fitting both.
 */
object VoiceCompat {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun teraOnly(context: Context, file: String): Boolean {
        if (!file.endsWith(".json") || file.startsWith("st3_") || file.startsWith("lib_")) return false
        val f = File(context.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles/$file")
        val stamp = f.lastModified()
        cache[file]?.let { (t, v) -> if (t == stamp) return v }
        val tail = try {
            java.io.RandomAccessFile(f, "r").use { r ->
                val n = minOf(r.length(), 4096L).toInt()
                r.seek(r.length() - n)
                ByteArray(n).also { r.readFully(it) }.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) { "" }
        val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(tail)?.groupValues?.get(1)
        val v = when (model) {
            null -> "synthesized by TeraTTS" in tail
            else -> model.startsWith("teratts")
        }
        cache[file] = stamp to v
        return v
    }
}
