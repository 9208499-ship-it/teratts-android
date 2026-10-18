package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/**
 * A whole chapter handed from one screen to another. It used to travel inside the Intent,
 * and a big chapter (400 000 letters = 800 KB) went through the system's binder, whose
 * limit is 1 MB for everything in flight: "Unreasonably large binder buffer", and on some
 * phones (MIUI, Android 10) the app was killed right after a chapter was chosen — "книга не
 * загружается". Now a long text stays in memory and in a file; the Intent carries a mark.
 */
object TextHandoff {
    private const val MARK = "\u0000teratts-handoff"
    private const val LIMIT = 20_000
    @Volatile private var held: String? = null

    private fun file(context: Context) = File(context.filesDir, "handoff_text.txt")

    /** What to put into the Intent instead of [text]. */
    fun wrap(context: Context, text: String): String {
        if (text.length < LIMIT) return text
        held = text
        // kept on disk too: the screen may be made anew after the process was killed
        try { file(context).writeText(text) } catch (_: Exception) { }
        return MARK
    }

    /** The text an Intent extra stands for (the extra itself if it is an ordinary text). */
    fun unwrap(context: Context, extra: String?): String? {
        if (extra != MARK) return extra
        held?.let { return it }
        return try { file(context).readText().also { held = it } } catch (_: Exception) { null }
    }
}
