package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * User-tunable punctuation behavior. All four toggles are OFF by default —
 * default behavior matches the legacy "stabilize audio glitches" pipeline so
 * existing setups don't change unless the user explicitly opts in.
 *
 * Plumbed through [TextNormalizer] so both the system TTS path
 * (Moon+ Reader, MacroDroid, etc.) and the in-app playback path see the
 * same rules. The singleton pattern mirrors LexiconManager so we don't have
 * to thread these flags through every normalize() call site.
 *
 * - [tightQuestionExclamation] — don't insert a space before `?`/`!` at the
 *   end of a chunk. Keeps the punctuation glued to the preceding word so the
 *   model gets a cleaner intonation hint, at the cost of occasional clicks
 *   on some voices.
 * - [strengthenIntonation] — duplicate `?`/`!` at end of chunk (`Куда?` →
 *   `Куда??`). Some TTS models react to repeated marks with a stronger
 *   prosodic contour; others ignore them. Worth A/B-testing.
 * - [tightEllipsis] — normalize `…` to `...`, collapse `. . .` and strip a
 *   space before `...`, so the model treats it as one expressive pause
 *   instead of three independent periods.
 * - [tightCommasAndPeriods] — don't insert spaces before `,` or `;` at end
 *   of chunk, and don't separate `."` / `!"`-style closing-quote sequences.
 *   Cleaner phrasing but more risk of model stumbling on rare punctuation.
 */
object PunctuationPrefs {
    private const val PREFS_NAME = "PunctuationPrefs"
    private const val KEY_TIGHT_QUESTION = "tight_question_exclamation"
    private const val KEY_DOUBLE_MARKS = "double_marks"
    private const val KEY_TIGHT_ELLIPSIS = "tight_ellipsis"
    private const val KEY_TIGHT_COMMAS_PERIODS = "tight_commas_periods"
    private const val KEY_FORCE_SPACE_BEFORE_PUNCT = "force_space_before_punct"

    @Volatile var tightQuestionExclamation: Boolean = false
        private set
    @Volatile var strengthenIntonation: Boolean = false
        private set
    @Volatile var tightEllipsis: Boolean = false
        private set
    @Volatile var tightCommasAndPeriods: Boolean = false
        private set
    // When ON, [TextNormalizer] inserts a space between a letter and an
    // immediately-following `.,;:!?`. Belt-and-braces hint for the engine's
    // text tokenizer: dictionary lookup already strips punctuation thanks to
    // [\\p{L}\\p{M}]+ matching, but the model's phonemizer may treat
    // "удивлён," and "удивлён ," as different tokens. Excludes repeated
    // punctuation (`...`, `?!`) so the existing tight-ellipsis / strengthen
    // pipeline isn't subverted.
    @Volatile var forceSpaceBeforePunctuation: Boolean = false
        private set

    /** Pull flags from disk into memory. Cheap; safe to call repeatedly. */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tightQuestionExclamation = p.getBoolean(KEY_TIGHT_QUESTION, false)
        tightCommasAndPeriods = p.getBoolean(KEY_TIGHT_COMMAS_PERIODS, false)
        // TeraTTS: these three tweaks were tuned for Supertonic and hurt here
        // ("..." → ". . .", "Куда??" → "Куда? ?", "слово ," is unusual input), so they stay off.
        strengthenIntonation = false
        tightEllipsis = false
        forceSpaceBeforePunctuation = false
    }

    fun setTightQuestionExclamation(context: Context, value: Boolean) {
        tightQuestionExclamation = value
        save(context, KEY_TIGHT_QUESTION, value)
    }

    fun setStrengthenIntonation(context: Context, value: Boolean) {
        strengthenIntonation = value
        save(context, KEY_DOUBLE_MARKS, value)
    }

    fun setTightEllipsis(context: Context, value: Boolean) {
        tightEllipsis = value
        save(context, KEY_TIGHT_ELLIPSIS, value)
    }

    fun setTightCommasAndPeriods(context: Context, value: Boolean) {
        tightCommasAndPeriods = value
        save(context, KEY_TIGHT_COMMAS_PERIODS, value)
    }

    fun setForceSpaceBeforePunctuation(context: Context, value: Boolean) {
        forceSpaceBeforePunctuation = value
        save(context, KEY_FORCE_SPACE_BEFORE_PUNCT, value)
    }

    private fun save(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(key, value)
            .apply()
    }
}
