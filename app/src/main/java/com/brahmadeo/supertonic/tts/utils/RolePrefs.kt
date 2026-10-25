package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/** Reading by roles (built-in reader): on/off and the voices for male and female lines. */
object RolePrefs {
    private const val PREFS = "SupertonicPrefs"
    private const val KEY_ENABLED = "roles_enabled"
    private const val KEY_MALE = "roles_male_voice"
    private const val KEY_FEMALE = "roles_female_voice"
    private const val KEY_NARRATOR = "roles_narrator_voice"
    private const val KEY_CHARACTERS = "roles_per_character"
    private const val KEY_UNKNOWN = "roles_unknown_voice"
    private const val KEY_MAIN_COUNT = "roles_main_count"
    private const val KEY_HIDDEN = "hidden_voices"

    /**
     * Only this many main characters (most lines in the book) get their own voices. With the
     * 40 library voices there are some 25 per sex to hand out (1.6.15: up to 40, by default 24;
     * it was 10 of about 8 per sex).
     */
    fun mainCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAIN_COUNT, DEFAULT_MAIN_COUNT)

    const val DEFAULT_MAIN_COUNT = 24
    const val MAX_MAIN_COUNT = 40

    fun setMainCount(context: Context, n: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MAIN_COUNT, n.coerceIn(1, MAX_MAIN_COUNT)).apply()

    /** Voice for lines whose speaker is not known; "" = the male lines' voice. Never the narrator. */
    fun unknownVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNKNOWN, "") ?: ""

    fun setUnknownVoice(context: Context, file: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_UNKNOWN, file).apply()

    /** Each character gets their own voice (default on); off = voices by gender only. */
    fun charactersEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHARACTERS, true)

    fun setCharactersEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CHARACTERS, on).apply()

    /** Narrator voice file; "" = the main voice chosen on the main screen. */
    fun narratorVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NARRATOR, "") ?: ""

    fun setNarratorVoice(context: Context, file: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NARRATOR, file).apply()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    /** Voice file name, e.g. "ru_m5.json". */
    fun maleVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MALE, "ru_m5.json") ?: "ru_m5.json"

    fun femaleVoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FEMALE, "ru_f1.json") ?: "ru_f1.json"

    fun setMaleVoice(context: Context, file: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MALE, file).apply()

    fun setFemaleVoice(context: Context, file: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FEMALE, file).apply()

    fun voicesDir(context: Context) = File(context.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")

    /** All installed voice files, sorted — without the ones the user removed from the list. */
    fun availableVoices(context: Context): List<String> {
        val hidden = hiddenVoices(context)
        return voicesDir(context).listFiles { _, n -> n.endsWith(".json") }?.map { it.name }
            ?.filter { it !in hidden }?.sorted() ?: emptyList()
    }

    /** Bundled voices the user removed from the list (✕): they are not offered and not given to characters. */
    fun hiddenVoices(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()

    private fun bundled(file: String) =
        file.startsWith("ru_") || file.startsWith("eng_") || file.startsWith("st3_") || file.startsWith("lib_")

    /**
     * Removes a voice from the lists. One of the user's own (imported) voices is deleted; a bundled
     * one is only hidden — its file would be put back on the next start — and can be returned.
     */
    fun removeVoice(context: Context, file: String) {
        if (!bundled(file)) { File(voicesDir(context), file).delete(); return }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_HIDDEN, hiddenVoices(context) + file).apply()
    }

    /** Brings back all hidden bundled voices. */
    fun restoreVoices(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HIDDEN).apply()

    /** Absolute style path for a voice file, or null if it is not installed. */
    fun pathOf(context: Context, file: String): String? =
        File(voicesDir(context), file).takeIf { it.exists() }?.absolutePath
}
