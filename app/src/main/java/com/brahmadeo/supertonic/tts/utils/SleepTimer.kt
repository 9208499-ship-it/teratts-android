package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * Sleep timer: reading stops after N minutes (at the end of the phrase being heard)
 * or at the end of the chapter. Kept in preferences, so the reading service (which
 * enforces it) and the player screen (which shows it) see the same thing.
 */
object SleepTimer {
    private const val PREFS = "TeraSleepTimer"
    private const val KEY_AT = "at"            // wall-clock ms when reading stops; 0 = no time limit
    private const val KEY_CHAPTER = "chapter"  // stop at the end of the chapter

    const val CHAPTER_END = -1

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [minutes] > 0: in so many minutes; [CHAPTER_END]: at the end of the chapter; 0: off. */
    fun set(context: Context, minutes: Int) {
        val e = p(context).edit().clear()
        if (minutes > 0) e.putLong(KEY_AT, System.currentTimeMillis() + minutes * 60_000L)
        if (minutes == CHAPTER_END) e.putBoolean(KEY_CHAPTER, true)
        e.apply()
    }

    fun off(context: Context) = p(context).edit().clear().apply()

    /** The time is up (reading should stop at the end of the current phrase). */
    fun expired(context: Context): Boolean {
        val at = p(context).getLong(KEY_AT, 0L)
        return at > 0L && System.currentTimeMillis() >= at
    }

    fun atChapterEnd(context: Context): Boolean = p(context).getBoolean(KEY_CHAPTER, false)

    /** For the player: minutes left (rounded up), [CHAPTER_END], or null when off. */
    fun left(context: Context): Int? {
        if (atChapterEnd(context)) return CHAPTER_END
        val at = p(context).getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        val ms = at - System.currentTimeMillis()
        return if (ms <= 0) 0 else ((ms + 59_999) / 60_000).toInt()
    }
}
