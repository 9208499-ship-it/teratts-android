package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * The user's corrections "this line is said by …", kept per book (see BookSession.scopeFor).
 * Key: DialogueAnalyzer2.speechKey(line); value: a character name, or
 * DialogueAnalyzer2.FORCE_NARRATOR / FORCE_UNKNOWN.
 */
object SpeakerOverrides {
    private fun prefs(context: Context, scope: String) = context.getSharedPreferences(
        if (scope.isEmpty()) "TeraSpeakerOverrides" else "TeraSpeakerOverrides@" + Integer.toHexString(scope.hashCode()),
        Context.MODE_PRIVATE)

    fun all(context: Context, scope: String): Map<String, String> =
        prefs(context, scope).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun set(context: Context, scope: String, line: String, speaker: String) =
        prefs(context, scope).edit().putString(DialogueAnalyzer2.speechKey(line), speaker).apply()

    fun clear(context: Context, scope: String, line: String) =
        prefs(context, scope).edit().remove(DialogueAnalyzer2.speechKey(line)).apply()
}
