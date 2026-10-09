package com.brahmadeo.supertonic.tts.utils

/**
 * Which word of a phrase is being spoken, from the share of the phrase's audio
 * already played. In Russian a word lasts roughly in proportion to its syllables
 * (= vowels); numbers are read as several words; punctuation adds the pauses the
 * engine inserts. Good enough for karaoke-style highlighting.
 */
object SpokenProgress {
    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}\-‑'’+]*""")
    private const val VOWELS = "аеёиоуыэюяaeiouy"

    private fun weightOf(word: String): Float {
        val digits = word.count { it.isDigit() }
        if (digits > 0) return 2.5f * digits                      // "1812" → тысяча восемьсот двенадцать
        val v = word.lowercase().count { it in VOWELS }
        return maxOf(1, v).toFloat()
    }

    private fun pauseAfter(text: String, from: Int): Float {
        var i = from
        var p = 0f
        while (i < text.length && !text[i].isLetterOrDigit()) {
            p += when (text[i]) {
                ',' -> 1.0f
                ';', ':' -> 1.5f
                '.', '!', '?', '…' -> 2.5f
                '—', '–' -> 1.0f
                else -> 0f
            }
            i++
        }
        return minOf(p, 4f)
    }

    /**
     * A sentence ends at . ! ? … — unless the author's words go on after it:
     * "— Держи себя в руках! — сказала Гусеница." / "«Это всё?» — спросила Алиса."
     */
    private fun endsSentence(text: String, i: Int): Boolean {
        if (text[i] !in ".!?…") return false
        var j = i + 1
        if (j < text.length && !text[j].isWhitespace() && text[j] !in "»”\"'’)") return false
        while (j < text.length && text[j] in "»”\"'’)") j++
        while (j < text.length && text[j] == ' ') j++
        if (j < text.length && text[j] in "—–-") {
            j++
            while (j < text.length && text[j] == ' ') j++
        }
        return !(j < text.length && text[j].isLowerCase())
    }

    /**
     * Character range of the SENTENCE being spoken at [fraction] of [text] — a calmer
     * highlight than a jumping word: the reader's chunks hold several sentences.
     */
    fun sentenceAt(text: String, fraction: Float): IntRange? {
        val w = wordAt(text, fraction) ?: return null
        var start = w.first
        while (start > 0 && !endsSentence(text, start - 1) && text[start - 1] != '\n') start--
        while (start < w.first && (text[start].isWhitespace() || text[start] in "—–-»”")) start++
        var end = w.last
        while (end < text.length - 1 && !endsSentence(text, end) && text[end + 1] != '\n') end++
        while (end + 1 < text.length && text[end + 1] in "»”\"'’)") end++
        return start..end
    }

    /** Where the sentences of [text] start (their first letter or digit) — the same cut as [sentenceAt]. */
    fun sentenceStarts(text: String): List<Int> {
        val res = ArrayList<Int>()
        var expect = true
        for (i in text.indices) {
            val c = text[i]
            if (expect && c.isLetterOrDigit()) { res.add(i); expect = false }
            if (c == '\n' || endsSentence(text, i)) expect = true
        }
        return res
    }

    /** [text] cut into sentences (punctuation before the first one stays with it). */
    fun splitSentences(text: String): List<String> {
        val starts = sentenceStarts(text)
        if (starts.size <= 1) return listOf(text)
        // a cut goes before the sentence's opening dash or quote: "…сказала она. | — Как дела?"
        val cuts = IntArray(starts.size) { k ->
            if (k == 0) 0 else {
                var c = starts[k]
                while (c > starts[k - 1] + 1 && (text[c - 1].isWhitespace() || text[c - 1] in "«„“\"—–-(")) c--
                c
            }
        }
        val out = ArrayList<String>(starts.size)
        for (k in starts.indices) {
            val a = cuts[k]
            val b = if (k + 1 < starts.size) cuts[k + 1] else text.length
            val part = text.substring(a, b).trim()
            if (part.isNotEmpty()) out.add(part)
        }
        return out
    }

    /** A share of [text] at which [sentenceAt] gives the sentence that starts at [pos]. */
    fun fractionAt(text: String, pos: Int): Float {
        val words = WORD.findAll(text).toList()
        if (words.isEmpty()) return 0f
        val w = FloatArray(words.size) { weightOf(words[it].value) }
        val p = FloatArray(words.size) { pauseAfter(text, words[it].range.last + 1) }
        val total = w.sum() + p.sum()
        var acc = 0f
        for (i in words.indices) {
            if (words[i].range.first >= pos) return ((acc + w[i] * 0.5f) / total).coerceIn(0f, 1f)
            acc += w[i] + p[i]
        }
        return 1f
    }

    /** Character range of the word being spoken at [fraction] (0..1) of [text], or null. */
    fun wordAt(text: String, fraction: Float): IntRange? {
        val words = WORD.findAll(text).toList()
        if (words.isEmpty()) return null
        val w = FloatArray(words.size) { weightOf(words[it].value) }
        val p = FloatArray(words.size) { pauseAfter(text, words[it].range.last + 1) }
        val total = w.sum() + p.sum()
        val target = fraction.coerceIn(0f, 1f) * total
        var acc = 0f
        for (i in words.indices) {
            // the word itself, then its pause (during the pause the word stays lit)
            if (target < acc + w[i] + p[i]) return words[i].range
            acc += w[i] + p[i]
        }
        return words.last().range
    }
}
