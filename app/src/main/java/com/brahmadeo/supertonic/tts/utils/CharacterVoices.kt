package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * A voice for every character, assigned once and remembered (by name), so that
 * Пётр sounds the same in every chapter. Main characters (most lines) pick first;
 * Russian voices before Supertonic ones, English-recorded voices last (accent);
 * whispers and the narrator's voice are not handed out automatically.
 */
object CharacterVoices {
    private const val PREFS = "TeraCharacterVoices"

    private fun isMale(v: String) = Regex("""(^|_)m\d""").containsMatchIn(v) || v.startsWith("st3_M")
    private fun isFemale(v: String) = Regex("""(^|_)f\d""").containsMatchIn(v) || v.startsWith("st3_F")
    private fun rank(v: String) = when {
        v.startsWith("ru_") -> 0
        v.startsWith("st3_") -> 1
        else -> 2
    }

    /** Voice file for [name] (assigning a free one the first time), or null if the gender is unknown. */
    fun voiceFor(
        context: Context, name: String, role: DialogueAnalyzer2.Role, available: List<String>,
        genderDefault: String, narrator: String
    ): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(name, null)?.let { if (it in available) return it }
        val pool = available.filter {
            when (role) {
                DialogueAnalyzer2.Role.MALE -> isMale(it)
                DialogueAnalyzer2.Role.FEMALE -> isFemale(it)
                else -> false
            } && !it.contains("whisper") && it != narrator
        }.sortedWith(compareBy({ rank(it) }, { it }))
        if (pool.isEmpty()) return null
        val ordered = (listOf(genderDefault).filter { it in pool } + pool).distinct()
        val used = prefs.all.values.filterIsInstance<String>().toSet()
        val pick = ordered.firstOrNull { it !in used } ?: ordered[Math.floorMod(name.hashCode(), ordered.size)]
        prefs.edit().putString(name, pick).apply()
        return pick
    }

    private const val ALIASES = "TeraCharacterAliases"
    private const val SPEEDS = "TeraCharacterSpeeds"

    /** The voice set for [name] (by hand or assigned earlier), or null for "automatic". */
    fun explicit(context: Context, name: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null)

    /** Back to automatic (for the hero: back to the narrator's voice). */
    fun clear(context: Context, name: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()

    /** Merges made by the user: "искин" → "896‑й". */
    fun aliases(context: Context): Map<String, String> =
        context.getSharedPreferences(ALIASES, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun merge(context: Context, from: String, into: String) {
        if (from == into) return
        context.getSharedPreferences(ALIASES, Context.MODE_PRIVATE).edit().putString(from, into).apply()
    }

    fun unmerge(context: Context, from: String) =
        context.getSharedPreferences(ALIASES, Context.MODE_PRIVATE).edit().remove(from).apply()

    /** Pace set by hand (×0.7…×1.3), or null for the one worked out from the text. */
    fun speedOverride(context: Context, name: String): Float? {
        val p = context.getSharedPreferences(SPEEDS, Context.MODE_PRIVATE)
        return if (p.contains(name)) p.getFloat(name, 1f) else null
    }

    fun setSpeed(context: Context, name: String, speed: Float) =
        context.getSharedPreferences(SPEEDS, Context.MODE_PRIVATE).edit().putFloat(name, speed).apply()

    fun set(context: Context, name: String, voice: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, voice).apply()

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}
