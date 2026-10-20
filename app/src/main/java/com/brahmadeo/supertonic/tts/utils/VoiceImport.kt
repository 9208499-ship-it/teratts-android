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
    private fun target(context: Context, base: String): File {
        val clean = base.substringBeforeLast('.').replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').take(40)
            .ifEmpty { "voice" }
        val stem = "my_" + clean.removePrefix("my_")
        var f = File(dir(context), "$stem.json")
        var k = 2
        while (f.exists()) { f = File(dir(context), "${stem}_$k.json"); k++ }
        return f
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
