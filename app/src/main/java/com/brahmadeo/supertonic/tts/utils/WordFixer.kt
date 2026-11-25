package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/**
 * Obvious mistakes in a book's words put right before the text is shown and read (4PDA, 11.2026),
 * by the Silero stress dictionary (millions of word forms) as the list of right words:
 *
 *  1. a word broken by a hyphen: «хлес- тнут», «хлес-тнут» → «хлестнут» — only when the joined word
 *     is in the dictionary and a part of it is not («кто-то», «северо- и юго-» stay);
 *  2. digits for letters after scanning: «3десь» → «здесь», «0н» → «он», «6ыл» → «был» — only when
 *     the word comes out a dictionary word;
 *  3. a typo of one letter (a letter missed, one too many, two swapped, a wrong one): only a word in
 *     small letters (a capital — most likely a name), five letters or more, of two syllables or more
 *     (the dictionary has no words of one: «вновь», «сквозь» are not typos), not in the dictionary,
 *     with exactly one dictionary word one letter away; not a word of the personal dictionary
 *     (a rule for a word keeps it as it is). It can be turned off (settings: «Исправлять опечатки»);
 *  4. «не» run into a verb: «незнаю» → «не знаю», «небыло» → «не было» — only when the joined word is
 *     not in the dictionary, the rest is and ends as a verb does; with the typos, it can be turned off.
 *
 * The same text always comes out the same (the prepared chapters and the places in a book are
 * found by it): the dictionary is opened here, at once, not waited for.
 */
object WordFixer {
    /** [from] put right to [to]; [around] — the words about it as they were (for the list of fixes). */
    data class Fix(val from: String, val to: String, val around: String = "")

    private const val ALPHABET = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
    private val CYR = Regex("[А-Яа-яЁё]")
    private val HYPHEN_BREAK = Regex("""([А-Яа-яЁё]+)-([ \t]+|[ \t]*\n[ \t]*)?([а-яё]+)""")
    private val WITH_DIGITS = Regex("""(?<![\p{L}\d])[А-Яа-яЁё0-9]*[0-9][А-Яа-яЁё0-9]*(?![\p{L}\d])""")
    private val LOWER_WORD = Regex("""(?<![\p{L}\d\-])[а-яё]{5,}(?![\p{L}\d\-])""")
    private const val VOWELS = "аеёиоуыэюя"
    private val DIGIT_LETTER = mapOf('0' to 'о', '3' to 'з', '6' to 'б', '4' to 'ч')
    private val NE_JOINED = Regex("""(?<![\p{L}\d\-])([Нн]е)([а-яё]{3,})(?![\p{L}\d\-])""")
    // a verb's endings (знаю, было, будет, хочу, мог, трогай…); not an adjective's (-ый, -ий, -ой)
    private val VERB_END = Regex("""(ть|ться|тся|ти|чь|л|ла|ло|ли|лся|лась|лось|лись|ог|ю|у|ешь|ет|ем|ете|ют|ут|ёшь|ёт|ём|ёте|ишь|ит|им|ите|ят|ат|ся|сь|ай|яй|ей|уй|йте)$""")

    /** The words about [range] of [text], on one line. */
    private fun around(text: String, range: IntRange): String {
        var a = (range.first - 40).coerceAtLeast(0); var b = (range.last + 41).coerceAtMost(text.length)
        while (a > 0 && !text[a - 1].isWhitespace() && range.first - a < 60) a--
        while (b < text.length && !text[b].isWhitespace() && b - range.last < 60) b++
        return (if (a > 0) "…" else "") + text.substring(a, b).replace(Regex("\\s+"), " ").trim() + (if (b < text.length) "…" else "")
    }

    /** The text with the obvious mistakes put right; [isWord] answers for a word in small letters. */
    fun fix(text: String, isWord: (String) -> Boolean, typos: Boolean,
            keep: Set<String> = emptySet(), fixes: MutableList<Fix>? = null): String {
        if (!CYR.containsMatchIn(text)) return text
        fun known(w: String): Boolean { val l = w.lowercase(); return isWord(l) || isWord(l.replace('ё', 'е')) }

        // 1. hyphen breaks
        var t = HYPHEN_BREAK.replace(text) { m ->
            val a = m.groupValues[1]; val gap = m.groupValues[2]; val b = m.groupValues[3]
            val joined = a + b
            val ok = known(joined) && (!known(a) || !known(b)) && (gap.isNotEmpty() || !known("$a-$b")) &&
                a.lowercase() !in keep && b.lowercase() !in keep
            if (ok) { fixes?.add(Fix(m.value, joined, around(text, m.range))); joined } else m.value
        }

        // 2. digits for letters
        val src = t
        t = WITH_DIGITS.replace(t) { m ->
            val w = m.value
            if (!CYR.containsMatchIn(w) || w.lowercase() in keep) return@replace w
            val caps = w.filter { it.isLetter() }.all { it.isUpperCase() } && w.count { it.isLetter() } > 1
            var c = buildString { for (ch in w) append(DIGIT_LETTER[ch]?.let { if (caps) it.uppercaseChar() else it } ?: ch) }
            // «3десь» opening a sentence: «Здесь»
            if (w[0].isDigit() && sentenceStart(src, m.range.first)) c = c.replaceFirstChar { it.uppercaseChar() }
            if (c.none { it.isDigit() } && known(c)) { fixes?.add(Fix(w, c, around(src, m.range))); c } else w
        }

        // 4. «не» run into a verb
        if (typos) {
            val before = t
            t = NE_JOINED.replace(t) { m ->
                val w = m.value; val ne = m.groupValues[1]; val rest = m.groupValues[2]
                if (w.lowercase() in keep || known(w) || !known(rest) || !VERB_END.containsMatchIn(rest) ||
                    rest.none { it in VOWELS }) return@replace w
                "$ne $rest".also { fixes?.add(Fix(w, it, around(before, m.range))) }
            }
        }

        // 3. one-letter typos
        if (typos) {
            val memo = HashMap<String, String>()
            val before = t
            t = LOWER_WORD.replace(t) { m ->
                val w = m.value
                if (w in keep) return@replace w
                memo.getOrPut(w) {
                    // one syllable: the dictionary has no such words at all, so «not in it» says nothing —
                    // «вновь» was «put right» to «внове» (tolyos)
                    if (known(w) || w.count { it in VOWELS } <= 1) w else {
                        val found = HashSet<String>()
                        for (c in edits1(w)) if (c != w && known(c)) { found.add(c.replace('ё', 'е')); if (found.size > 1) break }
                        // ё / е are one word: the dictionary's spelling of it, if it has one
                        if (found.size == 1) {
                            val one = found.first()
                            edits1(w).firstOrNull { it.replace('ё', 'е') == one && isWord(it) } ?: one
                        } else w
                    }
                }.also { if (it != w) fixes?.add(Fix(w, it, around(before, m.range))) }
            }
        }
        return t
    }

    /** Does a sentence begin at [at] in [text] (only spaces, quotes, dashes, brackets after . ! ? … or the start)? */
    private fun sentenceStart(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == '…') return true
            if (!(c.isWhitespace() || c in "«»\"„“”'(-—–")) return false
            i--
        }
        return true
    }

    /** Every word one letter away from [w] (missed, one too many, two swapped, a wrong one). */
    private fun edits1(w: String): Sequence<String> = sequence {
        for (i in w.indices) yield(w.removeRange(i, i + 1))
        for (i in 0 until w.length - 1) yield(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        for (i in w.indices) for (a in ALPHABET) if (a != w[i]) yield(w.substring(0, i) + a + w.substring(i + 1))
        for (i in w.indices) if (w[i] == 'е') yield(w.substring(0, i) + 'ё' + w.substring(i + 1))
        for (i in 0..w.length) for (a in ALPHABET) yield(w.substring(0, i) + a + w.substring(i))
    }

    // ---------------------------------------------------------------- the app's side

    private const val PREFS = "SupertonicPrefs"
    private const val KEY_TYPOS = "fix_typos"
    private const val SACC = "accent_dictionary.sacc"   // AccentDictionaryManager's file

    fun typos(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TYPOS, true)

    fun setTypos(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TYPOS, on).apply()
    }

    private var dict: BinaryAccentDictionary? = null
    private var dictStamp = 0L
    private val wordMemo = HashMap<String, Boolean>()
    private val logged = HashSet<Int>()

    @Synchronized
    private fun dictionary(context: Context): BinaryAccentDictionary? {
        val f = File(context.filesDir, SACC)
        if (!f.isFile || !AccentDictionaryManager.isSilero(context)) { dict?.close(); dict = null; return null }
        val stamp = f.lastModified() xor f.length()
        if (dict == null || stamp != dictStamp) {
            dict?.close()
            dict = BinaryAccentDictionary.open(f)
            dictStamp = stamp
            wordMemo.clear()
        }
        return dict
    }

    private fun isWord(d: BinaryAccentDictionary, w: String): Boolean = synchronized(wordMemo) {
        wordMemo[w] ?: (d.lookup(w.toByteArray(Charsets.UTF_8)) != null).also {
            if (wordMemo.size > 300_000) wordMemo.clear()
            wordMemo[w] = it
        }
    }

    /** Can the words be put right: the Silero stress dictionary is there. */
    fun available(context: Context): Boolean = dictionary(context) != null

    /** A chapter's (or pages') text with the obvious mistakes put right; as it is with no Silero dictionary.
     *  [out] gets what was put right («Сохранить исправленную книгу»). */
    fun apply(context: Context, text: String, out: MutableList<Fix>? = null): String = try {
        val d = dictionary(context)
        if (d == null) text else {
            val keep = LexiconManager.load(context).filter { !it.isRegex }.map { it.term.trim().lowercase() }.toHashSet()
            val fixes = ArrayList<Fix>()
            val fixed = fix(text, { isWord(d, it) }, typos(context), keep, fixes)
            out?.addAll(fixes)
            // the book's journal: what was put right, once for each text (it is taken out again and again)
            if (fixes.isNotEmpty() && synchronized(logged) { logged.add(text.hashCode()) })
                BookSession.log(context, "words put right: ${fixes.size} — " +
                    fixes.map { it.from to it.to }.distinct().take(25).joinToString(", ") { "«${it.first}» → «${it.second}»" })
            fixed
        }
    } catch (e: Exception) { text }
}
