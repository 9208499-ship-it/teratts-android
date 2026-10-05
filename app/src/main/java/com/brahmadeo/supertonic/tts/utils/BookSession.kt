package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/**
 * The book being read and the chapter it is on, so that the next chapter starts by
 * itself when one ends. A chapter here is one file of the book's reading order (EPUB;
 * FB2/TXT are converted to EPUB). Text that did not come from the book (pasted,
 * history) is recognised by its beginning and does not continue into the book.
 */
object BookSession {
    private const val PREFS = "TeraBookSession"
    private const val KEY_PATH = "path"
    private const val KEY_INDEX = "index"
    private const val KEY_PREFIX = "prefix"
    private const val PREFIX_LEN = 160
    /** Shorter than this is a cover, an image page or a title page: skipped. */
    private const val MIN_CHAPTER_CHARS = 40

    private fun prefixOf(text: String) = text.trim().take(PREFIX_LEN)

    /** Diagnostics: every step of moving between chapters goes to files/book.log. */
    fun log(context: Context, msg: String) {
        try {
            File(context.filesDir, "book.log").appendText(
                String.format(java.util.Locale.US, "%tT  %s%n", java.util.Date(), msg))
        } catch (e: Exception) { }
    }

    /** The user opened chapter [index] of [path] from the table of contents. */
    fun start(context: Context, path: String, index: Int, chapterText: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATH, path).putInt(KEY_INDEX, index).putString(KEY_PREFIX, prefixOf(chapterText))
            .apply()
        log(context, "start: chapter $index of $path, ${chapterText.length} chars, begins «${chapterText.trim().take(40)}»")
    }

    /** The book [text] belongs to (its file name) — character settings are kept per book; "" = not a book. */
    fun scopeFor(context: Context, text: String): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = p.getString(KEY_PREFIX, null)
        val path = p.getString(KEY_PATH, null) ?: return ""
        return if (!prefix.isNullOrEmpty() && text.trim().startsWith(prefix)) File(path).name else ""
    }

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    /** Is [text] (just finished) the session's current chapter? */
    fun matches(context: Context, text: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = p.getString(KEY_PREFIX, null)
        val ok = prefix != null && p.getString(KEY_PATH, null) != null && prefix.isNotEmpty() && text.trim().startsWith(prefix)
        log(context, "matches=$ok; session=${prefix != null}; text begins «${text.trim().take(40)}», session begins «${prefix?.take(40)}»")
        return ok
    }

    /** Text of the next chapter with real text (the session moves on to it), or null at the end. */
    suspend fun nextChapter(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = p.getString(KEY_PATH, null) ?: return null
        val index = p.getInt(KEY_INDEX, -1)
        val file = File(path)
        if (!file.exists()) { log(context, "next: book file is gone: $path"); return null }
        val parser = EbookParser(context)
        val opened = parser.openPublication(file)
        val publication = opened.getOrNull()
        if (publication == null) { log(context, "next: cannot open book: ${opened.exceptionOrNull()?.message}"); return null }
        val order = publication.readingOrder
        log(context, "next: current $index, reading order has ${order.size} files")
        for (i in index + 1 until order.size) {
            val res = parser.extractText(publication, order[i])
            val text = res.getOrNull()?.trim()
            log(context, "  file $i: ${text?.length ?: -1} chars ${res.exceptionOrNull()?.message ?: ""}")
            if (text == null || text.length < MIN_CHAPTER_CHARS) continue
            p.edit().putInt(KEY_INDEX, i).putString(KEY_PREFIX, prefixOf(text)).apply()
            return text
        }
        clear(context)   // end of the book
        return null
    }
}
