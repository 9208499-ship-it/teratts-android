package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Gender of the user's own voices ("m" / "f"), set by hand: the bundled ones carry it in their
 * names (ru_m5, ru_f1), own ones (my_Папа) do not — and without it they are not given to characters.
 */
object VoiceGender {
    private const val PREFS = "VoiceGender"
    private val map = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        for ((k, v) in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) (v as? String)?.let { map[k] = it }
        loaded = true
    }

    /** "m", "f" or null; works once [load] has run (the voice lists do it). */
    fun of(file: String): String? = map[file]

    fun get(context: Context, file: String): String { load(context); return map[file] ?: "" }

    fun set(context: Context, file: String, gender: String) {
        load(context)
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (gender == "m" || gender == "f") { map[file] = gender; e.putString(file, gender) }
        else { map.remove(file); e.remove(file) }
        e.apply()
    }
}
