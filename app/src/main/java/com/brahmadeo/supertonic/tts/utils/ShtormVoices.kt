package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * The bundled voices for Shtorm (ru_m5.wav and co.) are recordings made by TeraTTS: Shtorm copies
 * their flat, synthetic manner, and the user's own recordings sound much better. So they are off
 * (grey in the voice list) until ticked there; an unticked one is not given to characters and is
 * replaced by an own voice of the same gender where a role would have used it.
 */
object ShtormVoices {
    private const val PREFS = "ShtormVoices"
    private const val KEY_ON = "on"

    /** One of the bundled recordings (ru_m5.wav …), not the user's own. */
    fun bundled(file: String) = file.endsWith(".wav") && AssetManager.SHTORM_VOICES.any { "$it.wav" == file }

    private fun onSet(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_ON, null)?.toSet() ?: emptySet()

    /** A bundled recording that is not ticked. */
    fun isOff(context: Context, file: String) = bundled(file) && file !in onSet(context)

    fun setOn(context: Context, file: String, on: Boolean) {
        val s = onSet(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ON, if (on) s + file else s - file).apply()
    }
}
