package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * Voices, paces and merges of characters, kept PER BOOK ([scope] = the book's file
 * name, see BookSession.scopeFor; "" = text not from a book). A voice is assigned
 * once and remembered, so Пётр sounds the same in every chapter — and a Пётр in
 * another book gets his own. Who gets which voice is decided in [RoleCast].
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

    // bundled voices carry the gender in the name (ru_m5, ru_f1); own ones get it by hand (VoiceGender)
    private fun isMale(v: String) = VoiceGender.of(v)?.let { it == "m" }
        ?: (Regex("""(^|_)m\d""").containsMatchIn(v) || v.startsWith("st3_M"))
    private fun isFemale(v: String) = VoiceGender.of(v)?.let { it == "f" }
        ?: (Regex("""(^|_)f\d""").containsMatchIn(v) || v.startsWith("st3_F"))
    // the user's own voices first (with a gender set), then the Russian ones, then TeraTTS's
    // English ones (multilingual: they read Russian without an accent), then Supertonic's
    private fun rank(v: String) = when {
        !RolePrefs.isBundled(v) -> -1
        v.startsWith("ru_") -> 0
        v.startsWith("eng_") -> 1
        v.startsWith("st3_") -> 2
        else -> 3
    }

    /** The order of the voices within one rating: own ones, Russian, TeraTTS's English, Supertonic's. */
    fun order(v: String) = rank(v)

    /** A voice handed out automatically (see RoleCast): remembered for the book. */
    fun store(context: Context, name: String, voice: String, scope: String = GLOBAL) =
        prefs(context, PREFS, scope).edit().putString(name, voice).apply()

    /** An automatic voice that may not stay (wrong sex, taken by a common role, not for characters). */
    fun forget(context: Context, name: String, scope: String = GLOBAL) =
        prefs(context, PREFS, scope).edit().remove(name).apply()

    fun isFemaleVoice(v: String) = isFemale(v)
    fun isMaleVoice(v: String) = isMale(v)

    /** Another voice of the gender (Russian first), not [avoid]; null if there is none. */
    fun alternative(context: Context, avoid: String, female: Boolean): String? =
        RolePrefs.availableVoices(context)
            .filter { (if (female) isFemale(it) else isMale(it)) && !it.contains("whisper") && it != avoid }
            // the best rated; a voice not for characters only if there is no other
            .sortedWith(compareBy({ -RolePrefs.rating(context, it) }, { rank(it) }, { it }))
            .firstOrNull()

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
