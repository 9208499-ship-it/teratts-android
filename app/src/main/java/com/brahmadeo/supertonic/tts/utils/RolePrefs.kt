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
    private const val KEY_HERO_M = "roles_hero_voice"
    private const val KEY_HERO_F = "roles_heroine_voice"
    private const val KEY_TELLER = "roles_teller_voice"
    private const val RATING_PREFS = "TeraVoiceRating"

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

    /** A book told in the first person: its narration in the teller's voice and sex (default on), see RoleCast.teller. */
    fun tellerVoice(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TELLER, true)

    fun setTellerVoice(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TELLER, on).apply()

    /**
     * The voice for the main hero / heroine (the man / the woman with the most lines in the book;
     * in a book told in the first person it may be «Я»); "" = by the voices' rating, as any other.
     */
    fun heroVoice(context: Context, female: Boolean): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(if (female) KEY_HERO_F else KEY_HERO_M, "") ?: ""

    fun setHeroVoice(context: Context, female: Boolean, file: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(if (female) KEY_HERO_F else KEY_HERO_M, file).apply()

    /**
     * How a voice is handed to characters: [FAVOURITE] first (the more lines a character has, the
     * higher the voice), [NORMAL], or [NOT_FOR_CHARACTERS] — never given automatically (it can still
     * be the narrator's or chosen by hand). Shtorm's bundled recordings (made by TeraTTS, flat and
     * synthetic in Shtorm) are not for characters until rated otherwise.
     */
    const val FAVOURITE = 1
    const val NORMAL = 0
    const val NOT_FOR_CHARACTERS = -1

    fun rating(context: Context, file: String): Int {
        val p = context.getSharedPreferences(RATING_PREFS, Context.MODE_PRIVATE)
        return if (p.contains(file)) p.getInt(file, NORMAL) else if (ShtormVoices.bundled(file)) NOT_FOR_CHARACTERS else NORMAL
    }

    fun setRating(context: Context, file: String, r: Int) =
        context.getSharedPreferences(RATING_PREFS, Context.MODE_PRIVATE).edit().putInt(file, r.coerceIn(-1, 1)).apply()

    /** All ratings at once (for handing out many voices). */
    fun ratings(context: Context, files: Collection<String>): Map<String, Int> = files.associateWith { rating(context, it) }

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
        VoiceGender.load(context)
        val hidden = hiddenVoices(context)
        val ext = AssetManager.voiceExtension(context)
        // Shtorm: the bundled recordings that are not ticked are not offered either
        return voicesDir(context).listFiles { _, n -> n.endsWith(ext) }?.map { it.name }
            ?.filter { it !in hidden && !ShtormVoices.isOff(context, it) }?.sorted() ?: emptyList()
    }

    /** Bundled voices the user removed from the list (✕): they are not offered and not given to characters. */
    fun hiddenVoices(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()

    private fun bundled(file: String) =
        file.startsWith("ru_") || file.startsWith("eng_") || file.startsWith("st3_") || file.startsWith("lib_")

    /** One of the voices that come with the app (not imported by the user). */
    fun isBundled(file: String) = bundled(file)

    /**
     * Removes a voice from the lists. One of the user's own (imported) voices is deleted; a bundled
     * one is only hidden — its file would be put back on the next start — and can be returned.
     */
    fun removeVoice(context: Context, file: String) {
        if (!bundled(file)) { File(voicesDir(context), file).delete(); VoiceGender.set(context, file, ""); return }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_HIDDEN, hiddenVoices(context) + file).apply()
    }

    /** Brings back all hidden bundled voices. */
    fun restoreVoices(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HIDDEN).apply()

    /** Absolute style path for a voice file (in the running model's form, see [forEngine]), or null if it is not installed. */
    fun pathOf(context: Context, file: String): String? =
        File(voicesDir(context), forEngine(context, file)).takeIf { it.exists() }?.absolutePath

    /**
     * A voice for a common role (male, female, unknown lines, the hero): [file] as the model has it
     * ([forEngine]) — but if it was set for the other model (or is the default) and here it is a voice
     * not for characters (Shtorm's ru_m5.wav made by TeraTTS), another of its sex, the best rated.
     */
    fun forRole(context: Context, file: String): String {
        if (file.isEmpty()) return file
        val v = forEngine(context, file)
        if (v == file || rating(context, v) != NOT_FOR_CHARACTERS) return v
        VoiceGender.load(context)
        val female = CharacterVoices.isFemaleVoice(v)
        val male = CharacterVoices.isMaleVoice(v)
        return availableVoices(context)
            .filter { !it.contains("whisper") && rating(context, it) != NOT_FOR_CHARACTERS &&
                ((female && CharacterVoices.isFemaleVoice(it)) || (male && CharacterVoices.isMaleVoice(it))) }
            .sortedWith(compareBy({ -rating(context, it) }, { CharacterVoices.order(it) }, { it }))
            .firstOrNull() ?: v
    }

    /**
     * [file] as the chosen model has it (ru_m5.json ↔ ru_m5.wav). A voice the model has not got, or
     * a bundled Shtorm recording that is not ticked, gives way to an available voice of the same
     * gender (own voices first) — so roles set up for TeraTTS keep their gender with Shtorm.
     */
    fun forEngine(context: Context, file: String): String {
        if (file.isEmpty()) return file
        val dir = voicesDir(context)
        val ext = AssetManager.voiceExtension(context)
        val same = if (file.endsWith(ext)) file else file.substringBeforeLast('.') + ext
        if (File(dir, same).exists() && !ShtormVoices.isOff(context, same)) return same
        VoiceGender.load(context)
        val female = CharacterVoices.isFemaleVoice(file)
        val male = CharacterVoices.isMaleVoice(file)
        val pool = availableVoices(context).filter { !it.contains("whisper") }
        val pick = pool.filter { (!female && !male) || (female && CharacterVoices.isFemaleVoice(it)) || (male && CharacterVoices.isMaleVoice(it)) }
            .sortedBy { if (bundled(it)) 1 else 0 }.firstOrNull() ?: pool.firstOrNull()
        return pick ?: if (File(dir, same).exists()) same else file
    }
}
