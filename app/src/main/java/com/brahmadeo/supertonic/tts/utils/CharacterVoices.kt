package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * Voices, paces and merges of characters, kept PER BOOK ([scope] = the book's file
 * name, see BookSession.scopeFor; "" = text not from a book). A voice is assigned
 * once and remembered, so Пётр sounds the same in every chapter — and a Пётр in
 * another book gets his own. Main characters (most lines) pick first; Russian
 * voices, then TeraTTS's multilingual English ones, then Supertonic's; whispers
 * and the narrator's voice are not handed out automatically.
 */
object CharacterVoices {
    const val GLOBAL = ""
    private const val PREFS = "TeraCharacterVoices"
    private const val ALIASES = "TeraCharacterAliases"
    private const val SPEEDS = "TeraCharacterSpeeds"
    private const val MANUAL = "TeraCharacterManual"

    private fun name(base: String, scope: String) =
        if (scope.isEmpty()) base else base + "@" + Integer.toHexString(scope.hashCode())

    private fun prefs(context: Context, base: String, scope: String) =
        context.getSharedPreferences(name(base, scope), Context.MODE_PRIVATE)

    private fun isMale(v: String) = Regex("""(^|_)m\d""").containsMatchIn(v) || v.startsWith("st3_M")
    private fun isFemale(v: String) = Regex("""(^|_)f\d""").containsMatchIn(v) || v.startsWith("st3_F")
    // Russian voices first, then TeraTTS's English ones (multilingual: they read Russian
    // without an accent), then Supertonic's; own voices (no m/f in the name) are not handed out
    private fun rank(v: String) = when {
        v.startsWith("ru_") -> 0
        v.startsWith("eng_") -> 1
        v.startsWith("st3_") -> 2
        else -> 3
    }

    /** Voice file for [name] (assigning a free one the first time), or null if the gender is unknown. */
    fun voiceFor(
        context: Context, name: String, role: DialogueAnalyzer2.Role, available: List<String>,
        genderDefault: String, narrator: String, scope: String = GLOBAL
    ): String? {
        val p = prefs(context, PREFS, scope)
        p.getString(name, null)?.let { stored ->
            // an automatic voice of the wrong gender (the gender became known later) is reassigned
            val wrongGender = (role == DialogueAnalyzer2.Role.FEMALE && isMale(stored)) ||
                (role == DialogueAnalyzer2.Role.MALE && isFemale(stored))
            if (stored in available && (!wrongGender || isManual(context, name, scope))) return stored
            p.edit().remove(name).apply()
        }
        val pool = available.filter {
            when (role) {
                DialogueAnalyzer2.Role.MALE -> isMale(it)
                DialogueAnalyzer2.Role.FEMALE -> isFemale(it)
                else -> false
            } && !it.contains("whisper") && it != narrator
        }.sortedWith(compareBy({ rank(it) }, { it }))
        if (pool.isEmpty()) return null
        val ordered = (listOf(genderDefault).filter { it in pool } + pool).distinct()
        val used = p.all.values.filterIsInstance<String>().toSet()
        val pick = ordered.firstOrNull { it !in used } ?: ordered[Math.floorMod(name.hashCode(), ordered.size)]
        p.edit().putString(name, pick).apply()
        return pick
    }

    /** The voice set for [name] (by hand or assigned earlier), or null for "automatic". */
    fun explicit(context: Context, name: String, scope: String = GLOBAL): String? =
        prefs(context, PREFS, scope).getString(name, null)

    /** A voice chosen by hand (the Characters window): kept even for minor characters. */
    fun set(context: Context, name: String, voice: String, scope: String = GLOBAL) {
        prefs(context, PREFS, scope).edit().putString(name, voice).apply()
        prefs(context, MANUAL, scope).edit().putBoolean(name, true).apply()
    }

    /** Forget the automatically assigned voices of a book (voices set by hand stay). */
    fun resetAuto(context: Context, scope: String = GLOBAL) {
        val p = prefs(context, PREFS, scope)
        val manual = prefs(context, MANUAL, scope)
        val e = p.edit()
        for (k in p.all.keys) if (!manual.getBoolean(k, false)) e.remove(k)
        e.apply()
    }

    fun isManual(context: Context, name: String, scope: String = GLOBAL): Boolean =
        prefs(context, MANUAL, scope).getBoolean(name, false)

    /** Back to automatic (for the hero: back to the narrator's voice). */
    fun clear(context: Context, name: String, scope: String = GLOBAL) {
        prefs(context, PREFS, scope).edit().remove(name).apply()
        prefs(context, MANUAL, scope).edit().remove(name).apply()
    }

    /** Merges made by the user: "искин" → "896‑й". */
    fun aliases(context: Context, scope: String = GLOBAL): Map<String, String> =
        prefs(context, ALIASES, scope).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun merge(context: Context, from: String, into: String, scope: String = GLOBAL) {
        if (from == into) return
        prefs(context, ALIASES, scope).edit().putString(from, into).apply()
    }

    fun unmerge(context: Context, from: String, scope: String = GLOBAL) =
        prefs(context, ALIASES, scope).edit().remove(from).apply()

    /** Pace set by hand (×0.7…×1.3), or null for the one worked out from the text. */
    fun speedOverride(context: Context, name: String, scope: String = GLOBAL): Float? {
        val p = prefs(context, SPEEDS, scope)
        return if (p.contains(name)) p.getFloat(name, 1f) else null
    }

    fun setSpeed(context: Context, name: String, speed: Float, scope: String = GLOBAL) =
        prefs(context, SPEEDS, scope).edit().putFloat(name, speed).apply()

    fun all(context: Context, scope: String = GLOBAL): Map<String, String> =
        prefs(context, PREFS, scope).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}
