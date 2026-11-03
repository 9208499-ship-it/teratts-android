package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Quotes: phrases the listener kept (★ while the voice reads, or a long press on the text) — per
 * book, with the chapter they are from. One small JSON file in the app.
 */
object Quotes {
    class Quote(val book: String, val chapter: String, val text: String, val at: Long)

    private fun file(c: Context) = File(c.filesDir, "quotes.json")

    @Synchronized
    private fun all(c: Context): MutableList<Quote> = try {
        val a = JSONArray(file(c).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until a.length()).mapTo(ArrayList()) { i ->
            val o = a.getJSONObject(i)
            Quote(o.optString("b"), o.optString("c"), o.optString("t"), o.optLong("at"))
        }
    } catch (_: Exception) { ArrayList() }

    @Synchronized
    private fun save(c: Context, list: List<Quote>) {
        val a = JSONArray()
        for (q in list) a.put(JSONObject().put("b", q.book).put("c", q.chapter).put("t", q.text).put("at", q.at))
        val part = File(file(c).path + ".part")
        part.writeText(a.toString())
        if (!part.renameTo(file(c))) { part.copyTo(file(c), overwrite = true); part.delete() }
    }

    /** Keeps [text] (once: the same phrase of the same book is not kept twice). False if it was there. */
    fun add(c: Context, book: String, chapter: String, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val list = all(c)
        if (list.any { it.book == book && it.text == t }) return false
        list.add(Quote(book, chapter.trim().take(80), t, System.currentTimeMillis()))
        save(c, list)
        return true
    }

    /** The quotes of [book], the latest first. */
    fun of(c: Context, book: String): List<Quote> = all(c).filter { it.book == book }.sortedByDescending { it.at }

    fun remove(c: Context, q: Quote) = save(c, all(c).filterNot { it.book == q.book && it.text == q.text && it.at == q.at })
}
