package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONObject

/**
 * The last place in each book, modelled on Readium's Locator: the chapter (an item
 * of the reading order), the progression inside it (0..1) and a short text quote at
 * the place. It is found again by the quote — robust to how the chapter text was
 * split or prepared — and by the progression if the quote is not there.
 */
object BookPositions {
    private const val PREFS = "TeraBookPositions"

    data class Pos(val chapter: Int, val progression: Float, val quote: String)

    fun save(context: Context, book: String, chapter: Int, progression: Float, quote: String) {
        if (book.isEmpty() || chapter < 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(book,
            JSONObject().put("chapter", chapter).put("progression", progression.toDouble()).put("quote", quote).toString()
        ).apply()
    }

    fun get(context: Context, book: String): Pos? {
        if (book.isEmpty()) return null
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(book, null) ?: return null
        return try {
            val o = JSONObject(s)
            Pos(o.getInt("chapter"), o.getDouble("progression").toFloat(), o.optString("quote", ""))
        } catch (e: Exception) { null }
    }

    /** Character offset of [pos] in [text]: the quote (letters and digits only) nearest to the progression. */
    fun offsetIn(text: String, pos: Pos): Int {
        val guess = (pos.progression * text.length).toInt().coerceIn(0, text.length)
        val q = buildString { for (c in pos.quote) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }.take(40)
        if (q.length < 12) return guess
        val map = IntArray(text.length)
        val sb = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) if (c.isLetterOrDigit()) { map[sb.length] = i; sb.append(c.lowercaseChar()) }
        val norm = sb.toString()
        var best = -1
        var at = norm.indexOf(q)
        while (at >= 0) {
            val off = map[at]
            if (best < 0 || Math.abs(off - guess) < Math.abs(best - guess)) best = off
            at = norm.indexOf(q, at + 1)
        }
        return if (best >= 0) best else guess
    }
}
