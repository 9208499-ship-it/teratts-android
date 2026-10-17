package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log

/**
 * English words inside Russian text, written in Russian letters ("Windows" → "У+индоуз").
 * The model switches language for a Latin word, and in a Russian sentence that switch
 * could throw the whole phrase off ("весь фрагмент читается странно"); in Russian letters
 * it stays one Russian phrase, with the English word said the way a Russian reader says it.
 *
 * Words: assets/en_ru.txt — 54 000 common English words from the CMU Pronouncing Dictionary
 * (BSD licence) turned into Russian letters with the stress ("+" before the stressed
 * vowel); other words by spelling rules. Abbreviations in capitals (NASA, CDs) are left
 * to the engine, which spells them. Only text that has Russian letters is touched:
 * a sentence wholly in English is still read in English. Off in the settings — as before.
 */
object EnglishTranscriber {
    private const val TAG = "EnTranscriber"
    private const val PREFS = "PunctuationPrefs"
    private const val KEY = "english_to_russian"

    @Volatile var enabled: Boolean = true
        private set
    @Volatile private var app: Context? = null
    @Volatile private var keys: Array<String>? = null
    @Volatile private var values: Array<String>? = null

    fun load(context: Context) {
        app = context.applicationContext
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    /** The table, read on first need (sorted by word: binary search, no HashMap of 54 000). */
    private fun table(): Pair<Array<String>, Array<String>>? {
        keys?.let { k -> values?.let { v -> return k to v } }
        synchronized(this) {
            keys?.let { k -> values?.let { v -> return k to v } }
            val ctx = app ?: return null
            return try {
                val ks = ArrayList<String>(56_000)
                val vs = ArrayList<String>(56_000)
                ctx.assets.open("en_ru.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val t = line.indexOf('\t')
                        if (t <= 0) continue
                        ks.add(line.substring(0, t)); vs.add(line.substring(t + 1))
                    }
                }
                val k = ks.toTypedArray(); val v = vs.toTypedArray()
                values = v; keys = k
                k to v
            } catch (e: Exception) {
                Log.w(TAG, "no table: ${e.message}"); null
            }
        }
    }

    private fun lookup(word: String): String? {
        val (k, v) = table() ?: return null
        val i = java.util.Arrays.binarySearch(k, word)
        return if (i >= 0) v[i] else null
    }

    // a Latin word not glued to digits or other letters ("MP3", "5G" stay as they are)
    private val LATIN = Regex("""(?<![\p{L}\p{N}])[A-Za-z][A-Za-z']*[A-Za-z](?![\p{L}\p{N}])""")
    private val CYR = Regex("[А-Яа-яЁё]")

    // "M&Ms", "M&M's", "AT&T", "R&D" — letters around "&" (also "&amp;" left from a book's markup)
    private val AMP = Regex("""(?<![\p{L}\p{N}])([A-Za-z]{1,4})\s?&(?:amp;)?\s?([A-Za-z]{1,4}?)('?s)?(?![\p{L}\p{N}])""")
    private val LETTERS = mapOf('a' to "эй", 'b' to "би", 'c' to "си", 'd' to "ди", 'e' to "и", 'f' to "эф",
        'g' to "джи", 'h' to "эйч", 'i' to "ай", 'j' to "джей", 'k' to "кей", 'l' to "эл", 'm' to "эм",
        'n' to "эн", 'o' to "оу", 'p' to "пи", 'q' to "кью", 'r' to "ар", 's' to "эс", 't' to "ти",
        'u' to "ю", 'v' to "ви", 'w' to "дабл-ю", 'x' to "экс", 'y' to "уай", 'z' to "зи")

    private fun spell(s: String) = s.lowercase().mapNotNull { LETTERS[it] }.joinToString("-")

    fun apply(text: String): String {
        if (!enabled || !CYR.containsMatchIn(text)) return text
        // the "&" pairs go first: as two words they were read as English and threw the phrase off
        val t0 = if (text.indexOf('&') < 0) text else AMP.replace(text) { m ->
            val (a, b, s) = m.destructured
            val side = { w: String -> if (w.length <= 3 || w.all { it.isUpperCase() }) spell(w) else (transcribe(w.lowercase()) ?: spell(w)) }
            side(a) + "-энд-" + side(b) + (if (s.isNotEmpty()) "с" else "")
        }
        if (!LATIN.containsMatchIn(t0)) return t0
        return LATIN.replace(t0) { m ->
            val tok = m.value
            val caps = tok.all { it.isUpperCase() || it == '\'' }
            // NASA, CDs, MMs: the engine spells abbreviations
            if (caps && tok.length <= 6) return@replace tok
            if (tok.length >= 3 && tok.endsWith("s") && tok.dropLast(1).all { it.isUpperCase() }) return@replace tok
            val ru = transcribe(tok.lowercase()) ?: return@replace tok
            if (tok[0].isUpperCase()) capitalize(ru) else ru
        }
    }

    private fun capitalize(s: String): String {
        val i = s.indexOfFirst { it != '+' }
        return if (i < 0) s else s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
    }

    /** One lower-case word in Russian letters, or null to leave it as it is. */
    fun transcribe(w: String): String? {
        lookup(w)?.let { return it }
        // John's, dragons, played: the stem from the table and the ending
        if (w.endsWith("'s")) lookup(w.dropLast(2))?.let { return it + "с" }
        if (w.endsWith("s'")) lookup(w.dropLast(2))?.let { return it + "с" }
        if (w.endsWith("es")) lookup(w.dropLast(2))?.let { return it + "ис" }
        if (w.endsWith("s")) lookup(w.dropLast(1))?.let { return it + "с" }
        if (w.endsWith("ed")) lookup(w.dropLast(2))?.let { return it + "д" }
        if (w.endsWith("ing")) lookup(w.dropLast(3))?.let { return it + "инг" }
        val clean = w.replace("'", "")
        return if (clean.length >= 2) byRules(clean) else null
    }

    /** Spelling rules for words not in the table (names, made-up words). */
    fun byRules(w: String): String {
        val out = StringBuilder()
        var i = 0
        fun at(k: Int) = if (k in w.indices) w[k] else ' '
        fun vowel(c: Char) = c in "aeiouy"
        while (i < w.length) {
            val c = w[i]
            val rest = w.substring(i)
            val last = i == w.length - 1
            when {
                rest.startsWith("tion") -> { out.append("шн"); i += 4 }
                rest.startsWith("sch") -> { out.append("ш"); i += 3 }
                rest.startsWith("igh") -> { out.append("ай"); i += 3 }
                rest.startsWith("tch") -> { out.append("ч"); i += 3 }
                rest.startsWith("sh") -> { out.append("ш"); i += 2 }
                rest.startsWith("ch") -> { out.append("ч"); i += 2 }
                rest.startsWith("th") -> { out.append("т"); i += 2 }
                rest.startsWith("ph") -> { out.append("ф"); i += 2 }
                rest.startsWith("ck") -> { out.append("к"); i += 2 }
                rest.startsWith("qu") -> { out.append("кв"); i += 2 }
                rest.startsWith("wh") -> { out.append("у"); i += 2 }
                rest.startsWith("kn") && i == 0 -> { out.append("н"); i += 2 }
                rest.startsWith("wr") && i == 0 -> { out.append("р"); i += 2 }
                rest.startsWith("ee") || rest.startsWith("ea") -> { out.append("и"); i += 2 }
                rest.startsWith("oo") -> { out.append("у"); i += 2 }
                rest.startsWith("ou") -> { out.append("ау"); i += 2 }
                rest.startsWith("ow") -> { out.append("оу"); i += 2 }
                rest.startsWith("ai") || rest.startsWith("ay") -> { out.append("эй"); i += 2 }
                rest.startsWith("oa") -> { out.append("оу"); i += 2 }
                rest.startsWith("ey") -> { out.append("эй"); i += 2 }
                rest.startsWith("ie") -> { out.append("и"); i += 2 }
                rest.startsWith("ng") && (i + 2 == w.length) -> { out.append("нг"); i += 2 }
                // a final silent e ("make", "Jake"); "be", "he" keep it
                c == 'e' && last && w.length > 3 && !vowel(at(i - 1)) -> { i += 1 }
                c == 'c' -> { out.append(if (at(i + 1) in "eiy") "с" else "к"); i += 1 }
                c == 'g' -> { out.append(if (at(i + 1) in "ei" && i > 0) "дж" else "г"); i += 1 }
                c == 'y' -> { out.append(if (i == 0 || !vowel(at(i + 1)) && i > 0) (if (i == 0) "й" else "и") else "й"); i += 1 }
                c == 'e' -> { out.append(if (i == 0 || vowel(at(i - 1))) "э" else "е"); i += 1 }
                c == 'x' -> { out.append(if (i == 0) "з" else "кс"); i += 1 }
                c == 'w' -> { out.append("у"); i += 1 }
                else -> {
                    out.append(when (c) {
                        'a' -> "а"; 'b' -> "б"; 'd' -> "д"; 'f' -> "ф"; 'h' -> "х"; 'i' -> "и"
                        'j' -> "дж"; 'k' -> "к"; 'l' -> "л"; 'm' -> "м"; 'n' -> "н"; 'o' -> "о"
                        'p' -> "п"; 'q' -> "к"; 'r' -> "р"; 's' -> "с"; 't' -> "т"; 'u' -> "у"
                        'v' -> "в"; 'z' -> "з"; else -> ""
                    })
                    i += 1
                }
            }
            // a doubled consonant is said once ("Mill" → "мил")
            if (out.length >= 2 && out[out.length - 1] == out[out.length - 2] && out[out.length - 1] !in "аеиоуэ") {
                out.setLength(out.length - 1)
            }
        }
        return out.toString()
    }
}
