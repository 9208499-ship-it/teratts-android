package com.brahmadeo.supertonic.tts.utils

/**
 * Russian words in CAPITALS ("СТОЙТЕ!", "ГЛАВА ПЕРВАЯ") sound broken: the model
 * learnt ordinary text. A capitalised word is read as a word — lower case, a
 * capital only at the start of a sentence — when it is long, stands next to
 * other capitalised words, is a common word or is in the accent dictionary.
 * A short one without vowels is an abbreviation and is spelled by letters
 * ("ФСБ" → "эф-эс-б+э"). Short ones with vowels that are none of the above
 * ("США", "МИД") are left as they are.
 */
object RussianCaps {
    private const val VOWELS = "АЕЁИОУЫЭЮЯ"
    private val WORD = Regex("""(?<![\p{L}\p{M}+])[А-ЯЁ][А-ЯЁ́+]*(?:-[А-ЯЁ][А-ЯЁ́+]*)*(?![\p{L}\p{M}])""")
    private val GAP = Regex("""[\s,;:—–\-«»"'“”„()]*""")
    private val LETTER = mapOf(
        'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Ж' to "жэ", 'З' to "зэ", 'К' to "ка",
        'Л' to "эл", 'М' to "эм", 'Н' to "эн", 'П' to "пэ", 'Р' to "эр", 'С' to "эс", 'Т' to "тэ",
        'Ф' to "эф", 'Х' to "ха", 'Ц' to "цэ", 'Ч' to "чэ", 'Ш' to "ша", 'Щ' to "ща")
    private val COMMON = setOf(
        "да", "нет", "не", "ни", "он", "она", "оно", "они", "мы", "вы", "ты", "я", "это", "эта", "этот", "эти",
        "так", "все", "всё", "что", "кто", "как", "где", "тут", "там", "вот", "ну", "но", "и", "а", "или", "же",
        "бы", "ли", "уже", "ещё", "еще", "его", "её", "ее", "их", "им", "ей", "мне", "меня", "тебя", "тебе",
        "себя", "вас", "нас", "нам", "вам", "был", "была", "было", "были", "есть", "стой", "стоп", "жди",
        "иди", "беги", "ура", "эй", "ой", "ах", "ох", "ага", "угу", "нее", "неа", "один", "два", "три", "раз",
        "мой", "моя", "моё", "твой", "твоя", "нам", "нет-нет", "да-да", "вон", "сам", "сама", "сюда", "туда",
        "ещё", "очень", "надо", "нужно", "можно", "нельзя", "конец", "глава", "часть", "том", "пролог")

    fun fix(text: String, known: (String) -> Boolean = { false }): String {
        val words = WORD.findAll(text).filter { m -> m.value.count { it.isLetter() } >= 2 }.toList()
        if (words.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var pos = 0
        for ((k, m) in words.withIndex()) {
            sb.append(text, pos, m.range.first)
            sb.append(fixWord(text, m, words.getOrNull(k - 1), words.getOrNull(k + 1), known))
            pos = m.range.last + 1
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    private fun adjacent(text: String, a: MatchResult?, b: MatchResult?): Boolean {
        if (a == null || b == null) return false
        return GAP.matches(text.substring(a.range.last + 1, b.range.first))
    }

    private fun sentenceStart(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in "«\"“„—–-(")) i--
        return i < 0 || text[i] in ".!?…:\n"
    }

    private fun fixWord(text: String, m: MatchResult, prev: MatchResult?, next: MatchResult?, known: (String) -> Boolean): String {
        val v = m.value
        val letters = v.filter { it.isLetter() }
        val plain = v.filter { it.isLetter() || it == '-' }.lowercase()
        val asWord = letters.length >= 5 || adjacent(text, prev, m) || adjacent(text, m, next) ||
            plain in COMMON || known(plain)
        if (asWord) {
            val low = v.lowercase()
            return if (sentenceStart(text, m.range.first)) low.replaceFirstChar { it.uppercaseChar() } else low
        }
        if (letters.none { it in VOWELS } && letters.all { it in LETTER } && letters.any { it != 'Х' }) {
            val names = letters.map { LETTER.getValue(it) }
            val last = names.last()
            val vi = last.indexOfFirst { it in "аэ" }
            val stressed = last.substring(0, vi) + "+" + last.substring(vi)
            return (names.dropLast(1) + stressed).joinToString("-")
        }
        return v
    }
}
