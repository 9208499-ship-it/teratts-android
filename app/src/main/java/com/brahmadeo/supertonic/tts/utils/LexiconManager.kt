package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern

data class LexiconItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    var term: String,
    var replacement: String,
    var ignoreCase: Boolean = true,
    var isRegex: Boolean = false
)

object LexiconManager {
    private const val FILE_NAME = "user_lexicon.json"
    private const val PREFS_NAME = "LexiconPrefs"
    private const val PREFS_KEY_ENABLED = "lexicon_enabled"

    private var cachedRules: List<LexiconItem> = emptyList()
    // Pre-compiled Patterns paired with their replacement, in the same order as
    // cachedRules. Building these on every apply() call (once per sentence)
    // was burning measurable CPU on long texts — at 30 rules × 1000 sentences
    // we were re-parsing the same regexes 30,000 times per book. Now we build
    // them once in load/save and reuse for the lifetime of the rule set.
    // null entries correspond to rules that failed to compile (malformed
    // regex from the user); apply() skips those.
    private var compiledRules: List<Pair<java.util.regex.Pattern, String>?> = emptyList()
    @Volatile private var isLoaded = false
    // Master switch: when false, apply() is a no-op even if rules exist.
    // Default ON so existing users keep their behavior; persisted per-device.
    @Volatile private var enabled: Boolean = true

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREFS_KEY_ENABLED, value)
            .apply()
    }

    fun load(context: Context): List<LexiconItem> {
        // Always refresh the enabled flag from prefs — cheap and keeps the
        // switch behavior coherent if the user toggles it from elsewhere.
        enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFS_KEY_ENABLED, true)

        // Always reload from file if not loaded or if requested,
        // but for performance we cache.
        if (isLoaded) return cachedRules

        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            cachedRules = emptyList()
            isLoaded = true
            return cachedRules
        }

        val items = mutableListOf<LexiconItem>()
        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(LexiconItem(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    term = obj.getString("term"),
                    replacement = obj.getString("replacement"),
                    ignoreCase = obj.optBoolean("ignoreCase", true),
                    isRegex = obj.optBoolean("isRegex", false)
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedRules = items
        compiledRules = items.map { compileRule(it) }
        isLoaded = true
        return items
    }

    fun save(context: Context, items: List<LexiconItem>) {
        cachedRules = items.toList() // Update cache immediately
        compiledRules = cachedRules.map { compileRule(it) }
        isLoaded = true
        
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("term", item.term)
                obj.put("replacement", item.replacement)
                obj.put("ignoreCase", item.ignoreCase)
                obj.put("isRegex", item.isRegex)
                jsonArray.put(obj)
            }
            
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun apply(text: String): String {
        // Master switch — user disabled the lexicon entirely.
        if (!enabled) return text
        // If not loaded, we can't apply rules safely without context to load them.
        // Consumers must ensure load(context) is called at app start.
        if (compiledRules.isEmpty()) return text

        var processed = text
        for (compiled in compiledRules) {
            val (pattern, replacement) = compiled ?: continue
            try {
                processed = pattern.matcher(processed).replaceAll(replacement)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return processed
    }

    /**
     * Compile one lexicon rule into a (Pattern, escaped-replacement) pair so
     * apply() can reuse it without re-parsing the regex every sentence. Null
     * means the rule was malformed (user typed an invalid regex); the apply
     * loop quietly skips those instead of failing the whole batch.
     *
     * Android's regex engine is ICU: \b and \w are Unicode-aware there by default, and
     * Pattern.UNICODE_CHARACTER_CLASS is NOT supported — Pattern.compile throws
     * "Unsupported flags" for it. With that flag every rule failed to compile and the
     * user's dictionary silently did nothing (the "test" button worked: it says the
     * replacement itself). So: the flag only where it is accepted (the desktop JVM, for
     * tests), and whole words found by explicit letter boundaries instead of \b.
     */
    private fun compileRule(item: LexiconItem): Pair<java.util.regex.Pattern, String>? {
        if (item.term.isBlank()) return null
        var flags = 0
        if (item.ignoreCase) {
            flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        }
        val regex = if (item.isRegex) item.term
                    else "(?<![\\p{L}\\p{N}_])" + Pattern.quote(item.term) + "(?![\\p{L}\\p{N}_])"
        val replacement = Matcher.quoteReplacement(item.replacement)
        for (extra in intArrayOf(Pattern.UNICODE_CHARACTER_CLASS, 0)) {
            try {
                return Pattern.compile(regex, flags or extra) to replacement
            } catch (e: Exception) {
                if (extra == 0) android.util.Log.w("TeraLexicon", "rule not compiled: ${item.term}: ${e.message}")
            }
        }
        return null
    }

    /**
     * Where the user's dictionary puts the stress in [word] (index of the vowel in the word),
     * or -1 if it has no plain rule for it. "гот+ов" → 3.
     */
    fun stressOf(context: Context, word: String): Int {
        val item = load(context).firstOrNull { !it.isRegex && it.term.equals(word, ignoreCase = true) } ?: return -1
        val r = item.replacement
        val plus = r.indexOf('+')
        if (plus >= 0 && r.replace("+", "").equals(word, ignoreCase = true)) return plus
        val acute = r.indexOf('\u0301')
        if (acute > 0 && r.replace("\u0301", "").equals(word, ignoreCase = true)) return acute - 1
        return -1
    }

    /** The word's stress from the player ("гот+ов"): one rule per word, the old one replaced. */
    fun putStress(context: Context, word: String, stressed: String) {
        val items = load(context).filterNot { !it.isRegex && it.term.equals(word, ignoreCase = true) }.toMutableList()
        items.add(LexiconItem(term = word.lowercase(), replacement = stressed, ignoreCase = true, isRegex = false))
        save(context, items)
    }

    // Force reload (useful when returning from LexiconActivity)
    fun reload(context: Context) {
        isLoaded = false
        load(context)
    }
}
