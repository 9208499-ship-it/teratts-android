package com.brahmadeo.supertonic.tts.utils

/**
 * Russian words in CAPITALS ("СТОЙТЕ!", "ГЛАВА ПЕРВАЯ") sound broken: the model
 * learnt ordinary text. A capitalised word is read as a word — lower case, a
 * capital only at the start of a sentence — when it is long, stands next to
 * other capitalised words, is a common word or is in the accent dictionary.
 * An abbreviation is spelled by letters: no vowels ("ФСБ" → "эф-эс-б+э"), or
 * one vowel at an end ("ФСО", "АКБ"), also with an ending ("БТРом" →
 * "бэ-тэ-+эром", "СБшники"). Other short ones ("США", "МИД", "ОМОН") are left
 * as they are. (Abbreviation patterns: Lecron, 4PDA.)
 */
object RussianCaps {
    private const val VOWELS = "АЕЁИОУЫЭЮЯ"
    private val WORD = Regex("""(?<![\p{L}\p{M}+])[А-ЯЁ][А-ЯЁ́+]*(?:-[А-ЯЁ][А-ЯЁ́+]*)*(?![\p{L}\p{M}])""")
    /** An abbreviation with a lower-case ending: "БТРом", "СБшники", "ТВ-шоу". */
    private val WITH_TAIL = Regex("""(?<![\p{L}\p{M}+])([А-ЯЁ]{2,5})(-?)([а-яё]+)(?![\p{L}\p{M}])""")
    private val CONS = "БВГДЖЗКЛМНПРСТФХЦЧШЩ"
    private val GAP = Regex("""[\s,;:—–\-«»"'“”„()]*""")
    private val LETTER = mapOf(
        'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Ж' to "жэ", 'З' to "зэ", 'К' to "ка",
        'Л' to "эл", 'М' to "эм", 'Н' to "эн", 'П' to "пэ", 'Р' to "эр", 'С' to "эс", 'Т' to "тэ",
        'Ф' to "эф", 'Х' to "ха", 'Ц' to "цэ", 'Ч' to "чэ", 'Ш' to "ша", 'Щ' to "ща",
        'А' to "а", 'Е' to "е", 'И' to "и", 'О' to "о", 'У' to "у", 'Ы' to "ы", 'Э' to "э", 'Ю' to "ю", 'Я' to "я")
    private val COMMON = setOf(
        "да", "нет", "не", "ни", "он", "она", "оно", "они", "мы", "вы", "ты", "я", "это", "эта", "этот", "эти",
        "так", "все", "всё", "что", "кто", "как", "где", "тут", "там", "вот", "ну", "но", "и", "а", "или", "же",
        "бы", "ли", "уже", "ещё", "еще", "его", "её", "ее", "их", "им", "ей", "мне", "меня", "тебя", "тебе",
        "себя", "вас", "нас", "нам", "вам", "был", "была", "было", "были", "есть", "стой", "стоп", "жди",
        "иди", "беги", "ура", "эй", "ой", "ах", "ох", "ага", "угу", "нее", "неа", "один", "два", "три", "раз",
        "мой", "моя", "моё", "твой", "твоя", "нам", "нет-нет", "да-да", "вон", "сам", "сама", "сюда", "туда",
        "ещё", "очень", "надо", "нужно", "можно", "нельзя", "конец", "глава", "часть", "том", "пролог")

    fun fix(text0: String, known: (String) -> Boolean = { false }): String {
        // "БТРом" → "бэ-тэ-+эром" first: the capitals of such a word are an abbreviation
        val text = WITH_TAIL.replace(text0) { m ->
            val caps = m.groupValues[1]
            if (isAbbreviation(caps) && !known(m.value.lowercase())) spell(caps) + m.groupValues[2] + m.groupValues[3] else m.value
        }
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
        // no vowels at all: cannot be read as a word ("ВЧК ОГПУ" side by side are still letters)
        if (letters.none { it in VOWELS } && letters.all { it in CONS } && letters.any { it != 'Х' }) return spell(letters)
        val asWord = letters.length >= 5 || adjacent(text, prev, m) || adjacent(text, m, next) ||
            plain in COMMON || known(plain)
        if (asWord) {
            val low = v.lowercase()
            return if (sentenceStart(text, m.range.first)) low.replaceFirstChar { it.uppercaseChar() } else low
        }
        if (isAbbreviation(letters)) return spell(letters)
        return v
    }

    /** Consonants only, or with one vowel at the start or the end: "ФСБ", "ФСО", "АКБ". */
    private fun isAbbreviation(caps: String): Boolean {
        if (caps.length < 2 || caps.any { it !in LETTER }) return false
        val inner = when {
            caps.first() in VOWELS -> caps.drop(1)
            caps.last() in VOWELS -> caps.dropLast(1)
            else -> caps
        }
        return inner.length >= 2 && inner.all { it in CONS } && caps.any { it != 'Х' }
    }

    /** Letter names, stress on the last one: "БТР" → "бэ-тэ-+эр". */
    private fun spell(caps: String): String {
        val names = caps.map { LETTER.getValue(it) }
        val last = names.last()
        val vi = last.indexOfFirst { it.lowercaseChar() in "аеиоуыэюя" }.coerceAtLeast(0)
        val stressed = last.substring(0, vi) + "+" + last.substring(vi)
        return (names.dropLast(1) + stressed).joinToString("-")
    }
}
