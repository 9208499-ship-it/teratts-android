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
    private var compiledRules: List<Rule?> = emptyList()

    /**
     * A rule ready to apply: a regular expression ([pattern]) as the user wrote it, or a plain word /
     * phrase ([words]) found by hand — letter by letter, whole words only, any spaces between the
     * words of a phrase. Plain rules used to be regular expressions too, and on Android those with a
     * hyphen («кто-то», «какие-то») never matched (z--v, 4PDA); found by hand, nothing depends on the
     * phone's regex engine.
     */
    private class Rule(val pattern: Pattern?, val words: List<String>, val ignoreCase: Boolean,
                       val quoted: String, val replacement: String)
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
        compiledRules = phrasesFirst(items).map { compileRule(it) }
        isLoaded = true
        return items
    }

    fun save(context: Context, items: List<LexiconItem>) {
        cachedRules = items.toList() // Update cache immediately
        compiledRules = phrasesFirst(cachedRules).map { compileRule(it) }
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

    /**
     * Rules from a file the user loads: the app's own JSON (its export), or a plain text dictionary as
     * others keep them — one rule a line, «#» a comment:
     *   «замок зам+ок» (RuAccent / silero-stress exceptions; a «+» in the left word is dropped),
     *   «замок=зам+ок», «небо между=н+ебо м+ежду» (phrases, ruRoberta-GUI and the like; «==» too),
     *   «$Шекспир=Шексп+ир» — «$» first: only in exactly that case.
     */
    fun parseImport(text: String): List<LexiconItem> {
        val body = text.removePrefix("﻿")
        val out = mutableListOf<LexiconItem>()
        if (body.trimStart().startsWith("[")) {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.has("term") && obj.has("replacement")) out.add(LexiconItem(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    term = obj.getString("term"),
                    replacement = obj.getString("replacement"),
                    ignoreCase = obj.optBoolean("ignoreCase", true),
                    isRegex = obj.optBoolean("isRegex", false)
                ))
            }
            return out
        }
        for (raw in body.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val strict = line.startsWith("$")
            if (strict) line = line.substring(1).trim()
            val eq = line.indexOf('=')
            val (left, right) = if (eq > 0) {
                line.substring(0, eq) to line.substring(eq + 1).trimStart('=')
            } else {
                val f = line.split(Regex("\\s+"))
                if (f.size != 2) continue
                f[0] to f[1]
            }
            val term = left.replace("+", "").replace("́", "").trim()
            val rep = right.trim()
            if (term.isEmpty() || rep.isEmpty() || term == rep) continue
            out.add(LexiconItem(term = term, replacement = rep, ignoreCase = !strict))
        }
        return out
    }

    private fun folded(s: String) = s.filter { it.isLetterOrDigit() }.lowercase().replace('ё', 'е')

    /**
     * A stress rule («замок» → «зам+ок») keeps the case of the text it replaces: «Замок» → «Зам+ок»,
     * «ЗАМОК» → «ЗАМ+ОК» (as a dictionary of exceptions does). Only when the replacement is the same
     * letters with stress marks (and ё); any other replacement goes as written.
     */
    private fun keepCase(found: String, with: String): String {
        if (folded(found) != folded(with)) return with
        val letters = found.filter { it.isLetterOrDigit() }
        val sb = StringBuilder(with.length)
        var i = 0
        for (c in with) {
            if (!c.isLetterOrDigit()) { sb.append(c); continue }
            val o = letters.getOrNull(i++)
            sb.append(if (o != null && o.isUpperCase()) c.uppercaseChar() else if (o != null) c.lowercaseChar() else c)
        }
        return sb.toString()
    }

    fun apply(text: String): String {
        // Master switch — user disabled the lexicon entirely.
        if (!enabled) return text
        // If not loaded, we can't apply rules safely without context to load them.
        // Consumers must ensure load(context) is called at app start.
        if (compiledRules.isEmpty()) return text

        var processed = text
        for (rule in compiledRules) {
            if (rule == null) continue
            try {
                processed = if (rule.pattern != null) rule.pattern.matcher(processed).replaceAll(rule.quoted)
                else replacePlain(processed, rule.words, rule.ignoreCase, rule.replacement)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return processed
    }

    private fun wordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '\u0301' || c == '+'

    /** Every whole [words] (a word, or a phrase with any spaces between its words) in [text] → [with]. */
    private fun replacePlain(text: String, words: List<String>, ignoreCase: Boolean, with: String): String {
        if (words.isEmpty() || words[0].isEmpty()) return text
        val first = words[0]
        var from = 0
        var last = 0
        var out: StringBuilder? = null
        while (true) {
            val at = text.indexOf(first, from, ignoreCase)
            if (at < 0) break
            var end = at + first.length
            var ok = at == 0 || !wordChar(text[at - 1])
            var k = 1
            while (ok && k < words.size) {
                var j = end
                while (j < text.length && (text[j].isWhitespace() || text[j] == '\u00A0')) j++
                if (j == end || !text.regionMatches(j, words[k], 0, words[k].length, ignoreCase)) ok = false
                else { end = j + words[k].length; k++ }
            }
            if (ok && end < text.length && wordChar(text[end])) ok = false
            if (ok) {
                val sb = out ?: StringBuilder(text.length + 16).also { out = it }
                sb.append(text, last, at).append(if (ignoreCase) keepCase(text.substring(at, end), with) else with)
                last = end
                from = end
            } else from = at + 1
        }
        val sb = out ?: return text
        return sb.append(text, last, text.length).toString()
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
    /**
     * Phrases ("у ворот") before single words: a word rule ("ворота" → "вор+ота") applied first
     * would change the text, and the phrase would no longer be found in it.
     */
    private fun phrasesFirst(items: List<LexiconItem>): List<LexiconItem> =
        items.sortedBy { if (!it.isRegex && it.term.trim().contains(' ')) 0 else 1 }

    /**
     * The word or phrase of a plain rule as it is matched: invisible characters out, a dash of any
     * kind between letters a hyphen, spaces around a hyphen gone ("какие - то", "какие‑то": "какие-то").
     */
    private fun plainTerm(term: String): String =
        term.replace(Regex("[\u00AD\u200B-\u200F\u2060-\u2064\uFEFF\u034F]"), "")
            .replace(Regex("(?<=\\p{L})\\s*[-\u2010\u2011\u2012\u2013\u2014\u2212\uFE63\uFF0D]\\s*(?=\\p{L})"), "-")
            .trim()
            .let { fixLookalikes(it) }

    private val mixedWord = Regex("[\\p{L}]+")
    private fun cyr(c: Char) = c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё'
    private fun lat(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * Russian words with Latin look-alike letters in them ("кaкие" with a Latin "a": books made by
     * recognising scans, a switched keyboard) written all in Russian letters — so rules find them and
     * the voice reads them as Russian. Words of Latin letters only are left as they are.
     */
    fun fixLookalikes(text: String): String {
        if (text.none { cyr(it) } || text.none { lat(it) }) return text
        return mixedWord.replace(text) { m ->
            val w = m.value
            if (w.any { cyr(it) } && w.any { lat(it) }) w.map { LOOKALIKE[it] ?: it }.joinToString("") else w
        }
    }

    /** How many words of [text] mix Russian and Latin letters (for the log). */
    fun mixedWords(text: String): Int =
        if (text.none { cyr(it) } || text.none { lat(it) }) 0
        else mixedWord.findAll(text).count { m -> m.value.any { cyr(it) } && m.value.any { lat(it) } }

    private val LOOKALIKE = mapOf('a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у',
        'A' to 'А', 'E' to 'Е', 'O' to 'О', 'P' to 'Р', 'C' to 'С', 'X' to 'Х', 'H' to 'Н', 'K' to 'К', 'M' to 'М',
        'T' to 'Т', 'B' to 'В')

    private fun compileRule(item: LexiconItem): Rule? {
        if (item.term.isBlank()) return null
        // what the rule looks for, character by character, for the log (a rule that never matched from
        // Moon+ — z--v, 4PDA)
        if (!item.isRegex) {
            val russian = item.term.any { it in 'а'..'я' || it in 'А'..'Я' }
            val odd = item.term.filter { (it.code > 127 && !it.isLetter()) || (russian && (it in 'a'..'z' || it in 'A'..'Z')) }
                .map { "U+%04X".format(it.code) }.distinct()
            android.util.Log.i("TeraLexicon", "rule «${item.term}» → «${item.replacement}»" +
                (if (odd.isNotEmpty()) " (characters: ${odd.joinToString(" ")})" else "") + (if (item.ignoreCase) "" else ", case as written"))
            // a plain word or phrase: found by hand (the text it meets is cleaned the same way:
            // TextNormalizer makes every dash between letters a hyphen, Russian letters Russian)
            val words = plainTerm(item.term).split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (words.isEmpty()) null else Rule(null, words, item.ignoreCase, "", item.replacement)
        }
        var flags = 0
        if (item.ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val quoted = Matcher.quoteReplacement(item.replacement)
        for (extra in intArrayOf(Pattern.UNICODE_CHARACTER_CLASS, 0)) {
            try {
                return Rule(Pattern.compile(item.term, flags or extra), emptyList(), item.ignoreCase, quoted, item.replacement)
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
        // "все" → "вс+ё": the same word, only with «ё»
        fun same(a: String) = a.replace('ё', 'е').replace('Ё', 'Е').equals(word.replace('ё', 'е').replace('Ё', 'Е'), ignoreCase = true)
        val plus = r.indexOf('+')
        if (plus >= 0 && same(r.replace("+", ""))) return plus
        val acute = r.indexOf('\u0301')
        if (acute > 0 && same(r.replace("\u0301", ""))) return acute - 1
        return -1
    }

    /**
     * The stress from the player ("гот+ов", or a phrase "у в+орот", or «ё» "вс+ё"):
     * one rule per word or phrase, the old one replaced.
     */
    fun putStress(context: Context, word: String, stressed: String) {
        val term = word.trim().replace(Regex("\\s+"), " ")
        val items = load(context).filterNot { !it.isRegex && it.term.trim().replace(Regex("\\s+"), " ").equals(term, ignoreCase = true) }.toMutableList()
        items.add(LexiconItem(term = term.lowercase(), replacement = stressed, ignoreCase = true, isRegex = false))
        save(context, items)
    }

    // Force reload (useful when returning from LexiconActivity)
    fun reload(context: Context) {
        isLoaded = false
        load(context)
    }
}
