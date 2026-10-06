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
