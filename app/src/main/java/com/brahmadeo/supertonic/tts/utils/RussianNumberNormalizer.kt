package com.brahmadeo.supertonic.tts.utils

import java.util.regex.Pattern

/**
 * Russian number-to-words converter for TTS pre-processing.
 *
 * Goal: stop the model from spelling digits out one-by-one
 * ("два ноль два пять") when it sees "2025" inside Russian text.
 *
 * Scope:
 * - Integers up to 10^12 with correct тысяч/миллион/миллиард agreement.
 * - Decimal numbers with a comma: "3,14" -> "три целых четырнадцать сотых"
 *   for short decimals, otherwise digit-by-digit after the comma.
 * - "N%" -> "N процентов" (genitive plural form covers most cases).
 * - "N°C" -> "N градусов Цельсия".
 * - Ordinal-looking suffixes ("1-й", "2-я") are left alone — they're
 *   genuinely ambiguous without morphological context.
 *
 * Not in scope (intentionally — would need a morphological analyzer):
 * - Gender agreement of "один/одна/одно" with the following noun.
 * - Case agreement (always emits nominative).
 * - Phone numbers, IBANs, ranges with hyphens beyond simple "10-15".
 *
 * Practical tradeoff: in 95% of book/article text the nominative reading
 * sounds natural; in legal/technical text with heavy case agreement the
 * model already gets some words wrong regardless.
 */
class RussianNumberNormalizer {

    // All regexes used by normalize() compiled once per instance — they used
    // to be inlined inside the function, costing ~7 fresh compiles per call.
    // The TTS service hits normalize() once per Russian sentence, so on a
    // 1000-sentence audiobook we were burning ~7000 redundant compiles.
    private val rangeRegex = Regex("\\b(\\d+)\\s*[-–—]\\s*(\\d+)\\b")
    private val percentRegex = Regex("\\b(\\d+(?:[,.]\\d+)?)\\s*%")
    private val celsiusRegex = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°\\s*[CС]\\b")
    private val degreesRegex = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°")
    private val decimalRegex = Regex("(?<![\\p{L}\\d])(-?\\d+),(\\d+)(?!\\d)")
    // An integer may touch '.'/',' unless that makes it part of a decimal ("30." at
    // the end of a sentence is an integer; "3.5" is not).
    private val integerRegex = Regex("(?<![\\p{L}\\d]|\\d[.,])(-?\\d{1,12})(?![\\p{L}\\d]|[.,]\\d)")
    // Clock time "11:05" / "9:30" -> "одиннадцать ноль пять" / "девять тридцать"
    private val timeRegex = Regex("(?<![\\p{L}\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\p{L}\\d:])")
    private val whitespaceRegex = Regex("\\s+")
    // Typeset books join a number and its ending with a non-breaking hyphen or a
    // dash ("896‑й", "90–х"); treat every such dash as a plain hyphen.
    // (only when glued on both sides: "Глава 5 — начало" is punctuation, not an ending)
    private val numberDashRegex = Regex("(?<=\\d)[‐‑‒–—―](?=[а-яёА-ЯЁ])")
    // Ordinal with a case ending: "896-й", "в 1990-м", "90-е", "в 90-х", "1-го", "5-я".
    // Longer endings first so the alternation never stops at a shorter prefix.
    // A range of ordinals with one ending: "1-5-й уровень", "3–4-го класса", "1-5-х классах".
    private val ordinalRangeRegex = Regex(
        "(?<![\\p{L}\\d])(\\d{1,12})\\s?[-‐‑‒–—―]\\s?(\\d{1,12})\\s?-\\s?" +
        "(ыми|ими|ого|его|ому|ему|ый|ий|ой|го|му|ым|им|ом|ем|ая|яя|ую|юю|ое|ее|ых|их|ми|ей|й|м|я|ю|е|х)" +
        "(?![\\p{L}\\d])(\\s+\\p{L}+)?")
    private val ordinalRegex = Regex(
        "(?<![\\p{L}\\d])(\\d{1,12})\\s?-\\s?" +
        "(ыми|ими|ого|его|ому|ему|ый|ий|ой|го|му|ым|им|ом|ем|ая|яя|ую|юю|ое|ее|ых|их|ми|мя|ти|ей|й|м|я|ю|е|х)" +
        "(?![\\p{L}\\d])(\\s+\\p{L}+)?"
    )
    // "в 1812 году", "XIX века" — a number governed by год/век is an ordinal in that case
    private val yearNounRegex = Regex(
        "(?<![\\p{L}\\d])(?:(к|ко|по|К|Ко|По)\\s+)?(\\d{1,4})\\s+(году|года|год|годом|веке|века|век|веком|столетии|столетия|столетие)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )
    private val romanNounRegex = Regex(
        "(?<![\\p{L}])([IVXLCDM]{1,7})\\s+(век|века|веке|веком|веков|столетие|столетия|столетии)(?![\\p{L}])"
    )
    // "100-летие", "5-летний", "2-комнатная" → "столетие", "пятилетний", "двухкомнатная"
    private val compoundRegex = Regex("(?<![\\p{L}\\d])(\\d{1,3})-([а-яё]{3,})")
    private val compoundPrefix = mapOf(1L to "одно", 2L to "двух", 3L to "трёх", 4L to "четырёх", 5L to "пяти",
        6L to "шести", 7L to "семи", 8L to "восьми", 9L to "девяти", 10L to "десяти", 11L to "одиннадцати",
        12L to "двенадцати", 13L to "тринадцати", 14L to "четырнадцати", 15L to "пятнадцати", 16L to "шестнадцати",
        17L to "семнадцати", 18L to "восемнадцати", 19L to "девятнадцати", 20L to "двадцати", 25L to "двадцатипяти",
        30L to "тридцати", 40L to "сорока", 50L to "пятидесяти", 60L to "шестидесяти", 70L to "семидесяти",
        80L to "восьмидесяти", 90L to "девяноста", 100L to "сто")
    // cardinal numbers with a case ending: "2-х", "2-мя", "5-ти", "7-ми"
    private val cardinalGen = mapOf(2L to "двух", 3L to "трёх", 4L to "четырёх")
    private val cardinalIns = mapOf(2L to "двумя", 3L to "тремя", 4L to "четырьмя")
    private val cardinalTi = mapOf(5L to "пяти", 6L to "шести", 7L to "семи", 8L to "восьми", 9L to "девяти",
        10L to "десяти", 11L to "одиннадцати", 12L to "двенадцати", 13L to "тринадцати", 14L to "четырнадцати",
        15L to "пятнадцати", 16L to "шестнадцати", 17L to "семнадцати", 18L to "восемнадцати", 19L to "девятнадцати",
        20L to "двадцати", 30L to "тридцати")

    // ---- cardinals in the oblique cases ---------------------------------------
    // "из 2 вариантов" → "из двух вариантов", "к 5 часам" → "к пяти часам", "с 15 друзьями" →
    // "с пятнадцатью друзьями", "о 2 вариантах" → "о двух вариантах" (ollejeek, 4PDA: numbers were
    // always read in the nominative). The case comes from the noun after the number (its plural
    // ending: -ах / -ам / -ами) or, failing that, from the preposition before it.
    private val CASE_GEN = 0; private val CASE_DAT = 1; private val CASE_INS = 2; private val CASE_PREP = 3
    private val unitsCase = arrayOf(
        arrayOf("нуля", "нулю", "нулём", "нуле"), arrayOf("одного", "одному", "одним", "одном"),
        arrayOf("двух", "двум", "двумя", "двух"), arrayOf("трёх", "трём", "тремя", "трёх"),
        arrayOf("четырёх", "четырём", "четырьмя", "четырёх"), arrayOf("пяти", "пяти", "пятью", "пяти"),
        arrayOf("шести", "шести", "шестью", "шести"), arrayOf("семи", "семи", "семью", "семи"),
        arrayOf("восьми", "восьми", "восемью", "восьми"), arrayOf("девяти", "девяти", "девятью", "девяти"),
        arrayOf("десяти", "десяти", "десятью", "десяти"), arrayOf("одиннадцати", "одиннадцати", "одиннадцатью", "одиннадцати"),
        arrayOf("двенадцати", "двенадцати", "двенадцатью", "двенадцати"), arrayOf("тринадцати", "тринадцати", "тринадцатью", "тринадцати"),
        arrayOf("четырнадцати", "четырнадцати", "четырнадцатью", "четырнадцати"), arrayOf("пятнадцати", "пятнадцати", "пятнадцатью", "пятнадцати"),
        arrayOf("шестнадцати", "шестнадцати", "шестнадцатью", "шестнадцати"), arrayOf("семнадцати", "семнадцати", "семнадцатью", "семнадцати"),
        arrayOf("восемнадцати", "восемнадцати", "восемнадцатью", "восемнадцати"), arrayOf("девятнадцати", "девятнадцати", "девятнадцатью", "девятнадцати"))
    private val tensCase = arrayOf(arrayOf(), arrayOf(),
        arrayOf("двадцати", "двадцати", "двадцатью", "двадцати"), arrayOf("тридцати", "тридцати", "тридцатью", "тридцати"),
        arrayOf("сорока", "сорока", "сорока", "сорока"), arrayOf("пятидесяти", "пятидесяти", "пятьюдесятью", "пятидесяти"),
        arrayOf("шестидесяти", "шестидесяти", "шестьюдесятью", "шестидесяти"), arrayOf("семидесяти", "семидесяти", "семьюдесятью", "семидесяти"),
        arrayOf("восьмидесяти", "восьмидесяти", "восемьюдесятью", "восьмидесяти"), arrayOf("девяноста", "девяноста", "девяноста", "девяноста"))
    private val hundredsCase = arrayOf(arrayOf(),
        arrayOf("ста", "ста", "ста", "ста"), arrayOf("двухсот", "двумстам", "двумястами", "двухстах"),
        arrayOf("трёхсот", "трёмстам", "тремястами", "трёхстах"), arrayOf("четырёхсот", "четырёмстам", "четырьмястами", "четырёхстах"),
        arrayOf("пятисот", "пятистам", "пятьюстами", "пятистах"), arrayOf("шестисот", "шестистам", "шестьюстами", "шестистах"),
        arrayOf("семисот", "семистам", "семьюстами", "семистах"), arrayOf("восьмисот", "восьмистам", "восемьюстами", "восьмистах"),
        arrayOf("девятисот", "девятистам", "девятьюстами", "девятистах"))
    private val thousandOne = arrayOf("тысячи", "тысяче", "тысячей", "тысяче")
    private val thousandMany = arrayOf("тысяч", "тысячам", "тысячами", "тысячах")

    private fun triadCase(n: Int, c: Int, feminine: Boolean): List<String> {
        val out = ArrayList<String>()
        if (n / 100 > 0) out.add(hundredsCase[n / 100][c])
        val r = n % 100
        fun unit(u: Int) = if (u == 1 && feminine) "одной" else unitsCase[u][c]
        if (r in 1..19) out.add(unit(r))
        else if (r >= 20) { out.add(tensCase[r / 10][c]); if (r % 10 > 0) out.add(unit(r % 10)) }
        return out
    }

    /** [n] (under a million) in an oblique case; [feminine]: "одной" for a final 1. */
    fun spellCardinalCase(n: Long, c: Int, feminine: Boolean = false): String? {
        if (n < 0 || n >= 1_000_000) return null
        if (n == 0L) return unitsCase[0][c]
        val th = (n / 1000).toInt()
        val rest = (n % 1000).toInt()
        val words = ArrayList<String>()
        if (th > 0) {
            if (th > 1) words.addAll(triadCase(th, c, feminine = true))
            words.add(if (th % 10 == 1 && th % 100 != 11) thousandOne[c] else thousandMany[c])
        }
        words.addAll(triadCase(rest, c, feminine))
        return words.joinToString(" ")
    }

    private val genPreps = setOf("из", "изо", "от", "ото", "до", "около", "свыше", "без", "безо", "для", "после", "кроме",
        "вместо", "среди", "возле", "вокруг", "у", "более", "менее", "больше", "меньше", "против", "мимо", "вне",
        "старше", "младше", "выше", "ниже", "нет", "накануне", "посреди", "сверх", "ради", "вдоль", "внутри", "позади",
        "впереди", "вроде", "насчёт", "насчет", "порядка", "с", "со")
    private val datPreps = setOf("к", "ко", "благодаря", "согласно", "вопреки", "навстречу", "подобно")
    private val insPreps = setOf("между", "над", "надо", "под", "подо", "перед", "передо")
    private val prepPreps = setOf("о", "об", "обо", "при")
    // a date: "1 мая" → "первое мая", "с 1 января" → "с первого января", "к 9 мая" → "к девятому мая"
    private val dateRegex = Regex("(?<![\\p{L}\\d])(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)
    private val prevWordRegex = Regex("(\\p{L}+)[\\s\\u00A0]*$")
    private val nextWordRegex = Regex("^[\\s\\u00A0]*(\\p{L}+)")

    /** The case a number stands in, from the word before it and the noun after it; -1: the nominative. */
    /** [case] (-1: the nominative), "одной" wanted, the case read from the noun (not only the preposition). */
    private class CaseAt(val case: Int, val feminine: Boolean, val byNoun: Boolean)

    private fun caseAround(before: String, after: String, n: Long): CaseAt {
        val p = prevWordRegex.find(before)?.groupValues?.get(1)?.lowercase() ?: ""
        val w = nextWordRegex.find(after)?.groupValues?.get(1)?.lowercase() ?: ""
        val single = n % 10 == 1L && n % 100 != 11L
        // the noun's ending says it best (plural: -ами, -ах, -ам; after "с" and a final 1, the
        // singular instrumental: "с 1 книгой")
        val byNoun = when {
            w.length > 3 && (w.endsWith("ами") || w.endsWith("ями") || w.endsWith("ьми") ||
                w.endsWith("ыми") || w.endsWith("ими")) -> CASE_INS
            w.length > 3 && (w.endsWith("ах") || w.endsWith("ях")) -> CASE_PREP
            w.length > 3 && (w.endsWith("ам") || w.endsWith("ям")) -> CASE_DAT
            single && (p == "с" || p == "со") && w.length > 2 &&
                listOf("ом", "ем", "ём", "ой", "ей", "ью").any { w.endsWith(it) } -> CASE_INS
            else -> -1
        }
        val c = if (byNoun >= 0) byNoun else when (p) {
            in genPreps -> CASE_GEN
            in datPreps -> CASE_DAT
            in insPreps -> CASE_INS
            in prepPreps -> CASE_PREP
            else -> -1
        }
        // "одной": a feminine noun after a final 1 ("из 1 книги", "с 1 книгой")
        val fem = when (c) {
            CASE_GEN -> w.endsWith("ы") || (w.endsWith("и") && !w.endsWith("ии"))
            CASE_INS -> w.endsWith("ой") || w.endsWith("ей") || w.endsWith("ью")
            else -> false
        }
        return CaseAt(c, fem, byNoun >= 0)
    }

    // ---- units of measurement ------------------------------------------------
    /** one / few / many, gender of the unit noun (for 1 and 2), after a fraction = few form. */
    private class MeasureUnit(val one: String, val few: String, val many: String, val feminine: Boolean = false)

    private val measureUnits = linkedMapOf(
        // speed / area / volume first (longest abbreviations win)
        "км/ч" to MeasureUnit("километр в час", "километра в час", "километров в час"),
        "м/с" to MeasureUnit("метр в секунду", "метра в секунду", "метров в секунду"),
        "км²" to MeasureUnit("квадратный километр", "квадратных километра", "квадратных километров"),
        "м²" to MeasureUnit("квадратный метр", "квадратных метра", "квадратных метров"),
        "кв. м" to MeasureUnit("квадратный метр", "квадратных метра", "квадратных метров"),
        "кв.м" to MeasureUnit("квадратный метр", "квадратных метра", "квадратных метров"),
        "м³" to MeasureUnit("кубический метр", "кубических метра", "кубических метров"),
        "куб. м" to MeasureUnit("кубический метр", "кубических метра", "кубических метров"),
        // length
        "мкм" to MeasureUnit("микрометр", "микрометра", "микрометров"),
        "мм" to MeasureUnit("миллиметр", "миллиметра", "миллиметров"),
        "см" to MeasureUnit("сантиметр", "сантиметра", "сантиметров"),
        "дм" to MeasureUnit("дециметр", "дециметра", "дециметров"),
        "км" to MeasureUnit("километр", "километра", "километров"),
        "м" to MeasureUnit("метр", "метра", "метров"),
        // mass
        "мг" to MeasureUnit("миллиграмм", "миллиграмма", "миллиграммов"),
        "кг" to MeasureUnit("килограмм", "килограмма", "килограммов"),
        "г" to MeasureUnit("грамм", "грамма", "граммов"),
        "т" to MeasureUnit("тонна", "тонны", "тонн", feminine = true),
        "ц" to MeasureUnit("центнер", "центнера", "центнеров"),
        // volume
        "мл" to MeasureUnit("миллилитр", "миллилитра", "миллилитров"),
        "л" to MeasureUnit("литр", "литра", "литров"),
        // time
        "мс" to MeasureUnit("миллисекунда", "миллисекунды", "миллисекунд", feminine = true),
        "сек" to MeasureUnit("секунда", "секунды", "секунд", feminine = true),
        "мин" to MeasureUnit("минута", "минуты", "минут", feminine = true),
        "ч" to MeasureUnit("час", "часа", "часов"),
        "сут" to MeasureUnit("сутки", "суток", "суток"),
        "нед" to MeasureUnit("неделя", "недели", "недель", feminine = true),
        "мес" to MeasureUnit("месяц", "месяца", "месяцев"),
        // electricity, frequency, data
        "кВт·ч" to MeasureUnit("киловатт-час", "киловатт-часа", "киловатт-часов"),
        "кВт" to MeasureUnit("киловатт", "киловатта", "киловатт"),
        "МВт" to MeasureUnit("мегаватт", "мегаватта", "мегаватт"),
        "Вт" to MeasureUnit("ватт", "ватта", "ватт"),
        "кВ" to MeasureUnit("киловольт", "киловольта", "киловольт"),
        "В" to MeasureUnit("вольт", "вольта", "вольт"),
        "мАч" to MeasureUnit("миллиампер-час", "миллиампер-часа", "миллиампер-часов"),
        "мА" to MeasureUnit("миллиампер", "миллиампера", "миллиампер"),
        "ГГц" to MeasureUnit("гигагерц", "гигагерца", "гигагерц"),
        "МГц" to MeasureUnit("мегагерц", "мегагерца", "мегагерц"),
        "кГц" to MeasureUnit("килогерц", "килогерца", "килогерц"),
        "Гц" to MeasureUnit("герц", "герца", "герц"),
        "дБ" to MeasureUnit("децибел", "децибела", "децибел"),
        "ТБ" to MeasureUnit("терабайт", "терабайта", "терабайт"), "Тб" to MeasureUnit("терабайт", "терабайта", "терабайт"),
        "ГБ" to MeasureUnit("гигабайт", "гигабайта", "гигабайт"), "Гб" to MeasureUnit("гигабайт", "гигабайта", "гигабайт"),
        "МБ" to MeasureUnit("мегабайт", "мегабайта", "мегабайт"), "Мб" to MeasureUnit("мегабайт", "мегабайта", "мегабайт"),
        "КБ" to MeasureUnit("килобайт", "килобайта", "килобайт"), "Кб" to MeasureUnit("килобайт", "килобайта", "килобайт"),
        "мп" to MeasureUnit("мегапиксель", "мегапикселя", "мегапикселей"), "Мп" to MeasureUnit("мегапиксель", "мегапикселя", "мегапикселей"),
        "л.с." to MeasureUnit("лошадиная сила", "лошадиные силы", "лошадиных сил", feminine = true),
        // money, counts
        "руб" to MeasureUnit("рубль", "рубля", "рублей"), "р" to MeasureUnit("рубль", "рубля", "рублей"),
        "₽" to MeasureUnit("рубль", "рубля", "рублей"),
        "коп" to MeasureUnit("копейка", "копейки", "копеек", feminine = true),
        "долл" to MeasureUnit("доллар", "доллара", "долларов"), "$" to MeasureUnit("доллар", "доллара", "долларов"),
        "€" to MeasureUnit("евро", "евро", "евро"),
        "тыс" to MeasureUnit("тысяча", "тысячи", "тысяч", feminine = true),
        "млн" to MeasureUnit("миллион", "миллиона", "миллионов"),
        "млрд" to MeasureUnit("миллиард", "миллиарда", "миллиардов"),
        "трлн" to MeasureUnit("триллион", "триллиона", "триллионов"),
        "шт" to MeasureUnit("штука", "штуки", "штук", feminine = true),
        "ед" to MeasureUnit("единица", "единицы", "единиц", feminine = true),
        "экз" to MeasureUnit("экземпляр", "экземпляра", "экземпляров"),
        "га" to MeasureUnit("гектар", "гектара", "гектаров"),
        "чел" to MeasureUnit("человек", "человека", "человек"),
        "стр" to MeasureUnit("страница", "страницы", "страниц", feminine = true),
        "%" to MeasureUnit("процент", "процента", "процентов")
    )

    private val unitRegex: Regex = run {
        val alts = measureUnits.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        // number, optional space, unit; the unit must not run into a longer word
        // ("5 мин" yes, "5 минут" no); an abbreviation dot before a lowercase
        // word is swallowed ("5 руб. за штуку"), a sentence-final dot is kept.
        Regex("(?<![\\p{L}\\d])(-?\\d+(?:[.,]\\d+)?)\\s?(?:$alts)(?![\\p{L}\\d])(\\.(?=\\s+[а-яё]))?")
    }
    private val unitAt = Regex("(?:" + measureUnits.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")")

    // "1999 г." — a year, not grams: "в 1999 г." → "в … девятом году", "1999 г." → "… девятого года"
    private val yearAbbrRegex = Regex("(?<![\\p{L}\\d])(?:(в|В)\\s+)?(\\d{3,4})\\s?гг?\\.")
    private val celsiusUnitRegex = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°\\s*([CС])?(?![\\p{L}])")

    /** "одна тысяча пятьсот" → "тысяча пятьсот": the everyday reading of 1000–1999. */
    private fun natural(words: String): String =
        if (words.startsWith("одна тысяча")) "тысяча" + words.removePrefix("одна тысяча") else words

    /** Feminine accusative: "одна минута" → "одну минуту", "лошадиная сила" → "лошадиную силу". */
    private fun feminineAccusative(phrase: String): String = phrase.split(" ").joinToString(" ") { w ->
        when {
            w == "одна" -> "одну"
            w.endsWith("ая") -> w.dropLast(2) + "ую"
            w.endsWith("а") -> w.dropLast(1) + "у"
            w.endsWith("я") -> w.dropLast(1) + "ю"
            else -> w
        }
    }

    // prepositions that put "одна минута" into the accusative: "через одну минуту"
    private val accusativePreps = setOf("через", "за", "на", "в", "во", "про", "под", "спустя")

    private fun spellNumberForUnit(raw: String, unit: MeasureUnit, prevWord: String = ""): String {
        val neg = raw.startsWith("-")
        val body = raw.trimStart('-')
        val sign = if (neg) "минус " else ""
        val sep = body.indexOfFirst { it == ',' || it == '.' }
        if (sep >= 0) {
            val ip = body.substring(0, sep).toLongOrNull() ?: return raw
            val frac = body.substring(sep + 1)
            return "$sign${spellDecimal(ip, frac)} ${unit.few}"   // "1,5 километра"
        }
        val n = body.toLongOrNull() ?: return raw
        val words = natural(if (unit.feminine) spellIntegerFeminine(n) else spellInteger(n))
        val phrase = "$words ${pluralForm(n, unit.one, unit.few, unit.many)}"
        val acc = unit.feminine && !neg && n % 10L == 1L && n % 100L != 11L && prevWord.lowercase() in accusativePreps
        return sign + if (acc) feminineAccusative(phrase) else phrase
    }

    private fun romanToInt(s: String): Long? {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0; var prev = 0
        for (c in s.reversed()) { val x = v[c] ?: return null; if (x < prev) total -= x else { total += x; prev = x } }
        // canonical form only
        var n = total; val sb = StringBuilder()
        for ((value, r) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
                50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (n >= value) { sb.append(r); n -= value }
        }
        return if (total in 1..3999 && sb.toString() == s) total.toLong() else null
    }

    private val ordUnits = arrayOf("", "первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой", "восьмой", "девятый")
    private val ordTeens = arrayOf("десятый", "одиннадцатый", "двенадцатый", "тринадцатый", "четырнадцатый",
        "пятнадцатый", "шестнадцатый", "семнадцатый", "восемнадцатый", "девятнадцатый")
    private val ordTens = arrayOf("", "", "двадцатый", "тридцатый", "сороковой", "пятидесятый", "шестидесятый",
        "семидесятый", "восьмидесятый", "девяностый")
    private val ordHundreds = arrayOf("", "сотый", "двухсотый", "трёхсотый", "четырёхсотый", "пятисотый",
        "шестисотый", "семисотый", "восьмисотый", "девятисотый")
    // genitive-like prefixes for "двухтысячный", "пятимиллионный"
    private val genPrefix = mapOf(2L to "двух", 3L to "трёх", 4L to "четырёх", 5L to "пяти", 6L to "шести",
        7L to "семи", 8L to "восьми", 9L to "девяти", 10L to "десяти", 11L to "одиннадцати", 12L to "двенадцати",
        15L to "пятнадцати", 20L to "двадцати", 30L to "тридцати", 40L to "сорока", 50L to "пятидесяти", 100L to "сто")

    private val units = arrayOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть",
        "семь", "восемь", "девять"
    )
    private val unitsFeminine = arrayOf(
        "ноль", "одна", "две", "три", "четыре", "пять", "шесть",
        "семь", "восемь", "девять"
    )
    private val teens = arrayOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
        "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"
    )
    private val tens = arrayOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто"
    )
    private val hundreds = arrayOf(
        "", "сто", "двести", "триста", "четыреста",
        "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"
    )

    /** Standard Russian rule: 1 form for 1, 21, 31… (but not 11); 2/3/4 form for 2-4, 22-24…; "many" form otherwise. */
    private fun pluralForm(n: Long, one: String, few: String, many: String): String {
        val abs = (if (n < 0) -n else n) % 100
        if (abs in 11..14) return many
        return when (abs % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    /** Spell numbers 0..999 with optional feminine inflection for the units digit. */
    private fun spellTriad(n: Int, feminine: Boolean): String {
        if (n == 0) return ""
        val parts = mutableListOf<String>()
        val h = n / 100
        val rest = n % 100
        if (h > 0) parts.add(hundreds[h])
        if (rest in 10..19) {
            parts.add(teens[rest - 10])
        } else {
            val t = rest / 10
            val u = rest % 10
            if (t > 0) parts.add(tens[t])
            if (u > 0) parts.add(if (feminine) unitsFeminine[u] else units[u])
        }
        return parts.joinToString(" ")
    }

    /**
     * Converts a non-negative integer up to 10^12 - 1 into words.
     *
     * "Тысяча" is grammatically feminine, so the units in the thousands
     * triad use одна/две (not один/два). "Миллион" and "миллиард" are
     * masculine — units stay один/два.
     */
    fun spellInteger(value: Long): String {
        if (value == 0L) return units[0]

        var n = if (value < 0) -value else value
        val parts = mutableListOf<String>()
        if (value < 0) parts.add("минус")

        val billions = (n / 1_000_000_000L).toInt(); n %= 1_000_000_000L
        val millions = (n / 1_000_000L).toInt();      n %= 1_000_000L
        val thousands = (n / 1_000L).toInt();         n %= 1_000L
        val units1to999 = n.toInt()

        if (billions > 0) {
            parts.add(spellTriad(billions, feminine = false))
            parts.add(pluralForm(billions.toLong(), "миллиард", "миллиарда", "миллиардов"))
        }
        if (millions > 0) {
            parts.add(spellTriad(millions, feminine = false))
            parts.add(pluralForm(millions.toLong(), "миллион", "миллиона", "миллионов"))
        }
        if (thousands > 0) {
            parts.add(spellTriad(thousands, feminine = true))
            parts.add(pluralForm(thousands.toLong(), "тысяча", "тысячи", "тысяч"))
        }
        if (units1to999 > 0) {
            parts.add(spellTriad(units1to999, feminine = false))
        }

        return whitespaceRegex.replace(parts.joinToString(" ").trim(), " ")
    }

    /**
     * Decimal: "3,14" -> "три целых четырнадцать сотых"; for longer fractional
     * parts we fall back to "три целых один четыре один пять девять два шесть"
     * to avoid extremely awkward agreement.
     */
    fun spellDecimal(integerPart: Long, fractional: String): String {
        val intWords = spellInteger(integerPart)
        val intSuffix = pluralForm(integerPart, "целая", "целых", "целых")
        if (fractional.length in 1..2 && fractional.all { it.isDigit() }) {
            val fracValue = fractional.toLong()
            val fracWords = spellIntegerFeminine(fracValue) // "одна сотая", "две десятых"
            // Use feminine for the integer triad here too (целая is feminine).
            val intWordsFem = spellIntegerFeminine(integerPart)
            val denomWord = if (fractional.length == 1) {
                pluralForm(fracValue, "десятая", "десятых", "десятых")
            } else {
                pluralForm(fracValue, "сотая", "сотых", "сотых")
            }
            return whitespaceRegex
                .replace("$intWordsFem ${pluralForm(integerPart, "целая", "целых", "целых")} $fracWords $denomWord", " ")
                .trim()
        }
        // Fall back: digit-by-digit reading for the fractional tail.
        val tail = fractional.map { digit ->
            if (digit.isDigit()) units[digit - '0'] else digit.toString()
        }.joinToString(" ")
        return "$intWords $intSuffix $tail"
    }

    /** Same as spellInteger, but the trailing units triad uses feminine forms (for "целая"). */
    private fun spellIntegerFeminine(value: Long): String {
        if (value == 0L) return unitsFeminine[0]
        var n = if (value < 0) -value else value
        val parts = mutableListOf<String>()
        if (value < 0) parts.add("минус")

        val billions = (n / 1_000_000_000L).toInt(); n %= 1_000_000_000L
        val millions = (n / 1_000_000L).toInt();      n %= 1_000_000L
        val thousands = (n / 1_000L).toInt();         n %= 1_000L
        val units1to999 = n.toInt()

        if (billions > 0) {
            parts.add(spellTriad(billions, feminine = false))
            parts.add(pluralForm(billions.toLong(), "миллиард", "миллиарда", "миллиардов"))
        }
        if (millions > 0) {
            parts.add(spellTriad(millions, feminine = false))
            parts.add(pluralForm(millions.toLong(), "миллион", "миллиона", "миллионов"))
        }
        if (thousands > 0) {
            parts.add(spellTriad(thousands, feminine = true))
            parts.add(pluralForm(thousands.toLong(), "тысяча", "тысячи", "тысяч"))
        }
        if (units1to999 > 0) {
            parts.add(spellTriad(units1to999, feminine = true))
        }
        return whitespaceRegex.replace(parts.joinToString(" ").trim(), " ")
    }

    /**
     * Walks the input text and replaces numeric tokens. Order of substitutions
     * matters: percent / degree handlers must run before the general number
     * rule, otherwise "15%" becomes "15 percent of …" then the "15" gets
     * spelled out separately.
     */
    /** Masculine nominative ordinal: 896 → "восемьсот девяносто шестой", 2000 → "двухтысячный". */
    fun spellOrdinal(n: Long): String? {
        if (n == 0L) return "нулевой"
        if (n % 1000L == 0L) {
            val (unit, k) = when {
                n % 1_000_000_000L == 0L -> "миллиардный" to n / 1_000_000_000L
                n % 1_000_000L == 0L -> "миллионный" to n / 1_000_000L
                else -> "тысячный" to n / 1000L
            }
            if (k == 1L) return unit
            val pre = genPrefix[k] ?: return null
            return pre + unit
        }
        val r = n % 1000L
        val t = r % 100L
        val last = when {
            t == 0L -> r            // round hundreds: "восьмисотый"
            t < 20L -> t
            t % 10L == 0L -> t
            else -> t % 10L
        }
        val word = when {
            last >= 100L -> ordHundreds[(last / 100L).toInt()]
            last >= 20L -> ordTens[(last / 10L).toInt()]
            last >= 10L -> ordTeens[(last - 10L).toInt()]
            else -> ordUnits[last.toInt()]
        }
        val head = n - last
        if (head == 0L) return word
        // "тысяча девятьсот девяностый", not "одна тысяча …"
        val cardinal = spellInteger(head)
        val prefix = if (cardinal.startsWith("одна тысяча")) "тысяча" + cardinal.removePrefix("одна тысяча") else cardinal
        return "$prefix $word".trim()
    }

    /** Decline a masculine-nominative ordinal phrase by the written ending ("-го", "-м", "-е"…). */
    private fun declineOrdinal(phrase: String, ending: String, n: Long): String {
        val words = phrase.split(" ")
        val w = words.last()
        val head = words.dropLast(1).joinToString(" ")
        val soft = w.endsWith("третий")
        val stem = w.dropLast(2)
        val stressed = w.endsWith("ой")
        val form = when (ending) {
            "ый", "ий", "ой", "й" -> "mnom"
            "ого", "его", "го" -> "gen"
            "ому", "ему", "му" -> "dat"
            "ым", "им" -> "ins"
            "м", "ом", "ем" -> "prep"
            "ая", "яя", "я" -> "fnom"
            "ую", "юю", "ю" -> "facc"
            "ей" -> "fobl"
            "ое", "ее", "е" -> if (n >= 10 && n % 10L == 0L) "plnom" else "nnom"
            "#ое" -> "nnom"   // a date: "двадцатое августа" (not "двадцатые", as "90-е")
            "ых", "их", "х" -> "plgen"
            "ыми", "ими", "ми" -> "plins"
            else -> "mnom"
        }
        val end = if (soft) when (form) {
            "mnom" -> return phrase
            "gen" -> "его"; "dat" -> "ему"; "ins" -> "им"; "prep" -> "ем"
            "fnom" -> "я"; "facc" -> "ю"; "fobl" -> "ей"; "nnom" -> "е"
            "plnom" -> "и"; "plgen" -> "их"; else -> "ими"
        } else when (form) {
            "mnom" -> if (stressed) "ой" else "ый"
            "gen" -> "ого"; "dat" -> "ому"; "ins" -> "ым"; "prep" -> "ом"
            "fnom" -> "ая"; "facc" -> "ую"; "fobl" -> "ой"; "nnom" -> "ое"
            "plnom" -> "ые"; "plgen" -> "ых"; else -> "ыми"
        }
        // "третий" has a soft stem: "трет" + "ь" + "его" → "третьего", "третья"…
        val word = if (soft) stem + "ь" + end else stem + end
        return if (head.isEmpty()) word else "$head $word"
    }

    // ---- abbreviations ------------------------------------------------------
    // A trailing dot that also ends the sentence ("…и т. д. Потом…") is kept, so the pause stays.
    private val L = "(?<![\\p{L}])"   // not glued to a letter on the left
    /** pattern, words, and whether its dot may also end the sentence (not before a name). */
    private class Abbr(val re: Regex, val words: String, val mayEndSentence: Boolean)

    private val abbreviations: List<Abbr> = listOf(
        Triple("до\\s?н\\.\\s?э\\.", "до нашей эры", true),
        Triple("н\\.\\s?э\\.", "нашей эры", true),
        Triple("и\\s+т\\.\\s?д\\.", "и так далее", true),
        Triple("и\\s+т\\.\\s?п\\.", "и тому подобное", true),
        Triple("в\\s+т\\.\\s?ч\\.", "в том числе", false),
        Triple("т\\.\\s?е\\.", "то есть", false),
        Triple("т\\.\\s?к\\.", "так как", false),
        Triple("т\\.\\s?н\\.", "так называемый", false),
        Triple("т\\.\\s?о\\.", "таким образом", false),
        Triple("и\\s+др\\.", "и другие", true),
        Triple("и\\s+пр\\.", "и прочее", true),
        Triple("напр\\.", "например", false),
        Triple("см\\.(?=\\s*[«\"„(\\p{L}\\d])", "смотри", false),
        Triple("ср\\.(?=\\s*[«\"„(\\p{L}\\d])", "сравни", false),
        Triple("ок\\.(?=\\s*\\d)", "около", false),
        Triple("гл\\.(?=\\s*[\\dIVXLC])", "глава", false),
        Triple("стр\\.(?=\\s*\\d)", "страница", false),
        Triple("с\\.(?=\\s*\\d)", "страница", false),
        Triple("рис\\.", "рисунок", false),
        Triple("табл\\.", "таблица", false),
        Triple("изд\\.", "издание", true),
        Triple("ред\\.", "редакция", true),
        Triple("прим\\.", "примечание", true),
        Triple("ул\\.", "улица", false),
        Triple("пл\\.(?=\\s*\\p{Lu})", "площадь", false),
        Triple("просп\\.", "проспект", false),
        Triple("пр-т", "проспект", false),
        Triple("обл\\.", "область", true),
        Triple("р-н", "район", false),
        Triple("пос\\.(?=\\s*\\p{Lu})", "посёлок", false),
        Triple("д\\.(?=\\s*\\d)", "дом", false),
        Triple("кв\\.(?=\\s*\\d)", "квартира", false),
        Triple("им\\.(?=\\s*\\p{Lu})", "имени", false),
        Triple("проф\\.", "профессор", false),
        Triple("акад\\.", "академик", false),
        Triple("доц\\.", "доцент", false),
        Triple("тов\\.(?=\\s*\\p{Lu})", "товарищ", false),
        Triple("г-ну", "господину", false),
        Triple("г-на", "господина", false),
        Triple("г-н", "господин", false),
        Triple("г-жа", "госпожа", false),
        Triple("г-жи", "госпожи", false),
        Triple("св\\.(?=\\s*\\p{Lu})", "святой", false),
        // after a number these are units ("21 ед." → "двадцать одна единица"); alone — plural
        Triple("(?<!\\d)(?<!\\d\\s)ед\\.", "единиц", true),
        Triple("(?<!\\d)(?<!\\d\\s)экз\\.", "экземпляров", true)
    ).map { (pattern, words, ends) ->
        Abbr(Regex("(?<![\\p{L}])$pattern", RegexOption.IGNORE_CASE), words, ends)
    }

    // "XIX в." / "в XIX в." / "XVII–XVIII вв." → words the century rule below understands
    private val centuryRangeRegex = Regex(
        "(?<![\\p{L}\\d])((?:в|В)\\s+)?(\\d{1,2}|[IVXLC]{1,6})\\s?[–—-]\\s?(\\d{1,2}|[IVXLC]{1,6})\\s?вв\\.")
    private val centuryAbbrRegex = Regex("(?<![\\p{L}\\d])((?:в|В)\\s+)?(\\d{1,2}|[IVXLC]{1,6})\\s?(вв|в)\\.")
    // "+10 к силе" → "плюс 10 к силе"
    private val plusNumberRegex = Regex("(?<![\\p{L}\\d+])\\+(?=\\d)")

    private fun centuryOrdinal(token: String, prepositional: Boolean): String? {
        val n = token.toLongOrNull() ?: romanToInt(token) ?: return null
        val phrase = spellOrdinal(n) ?: return null
        return declineOrdinal(phrase, if (prepositional) "м" else "й", n)
    }

    private fun expandAbbreviations(text: String): String {
        var t = centuryRangeRegex.replace(text) { m ->
            val prep = m.groupValues[1]
            val a = centuryOrdinal(m.groupValues[2], prep.isNotEmpty()) ?: return@replace m.value
            val b = centuryOrdinal(m.groupValues[3], prep.isNotEmpty()) ?: return@replace m.value
            "$prep$a — $b " + (if (prep.isNotEmpty()) "веках" else "века") + keepSentenceDot(text, m.range.last + 1)
        }
        t = centuryAbbrRegex.replace(t) { m ->
            val prep = m.groupValues[1]
            val plural = m.groupValues[3] == "вв"
            val noun = when {
                plural -> if (prep.isNotEmpty()) "веках" else "века"
                prep.isNotEmpty() -> "веке"
                else -> "век"
            }
            "$prep${m.groupValues[2]} $noun" + keepSentenceDot(t, m.range.last + 1)
        }
        t = plusNumberRegex.replace(t, "плюс ")
        for (a in abbreviations) {
            val src = t
            t = a.re.replace(src) { m ->
                val w = if (m.value.first().isUpperCase()) a.words.replaceFirstChar { it.uppercase() } else a.words
                w + if (a.mayEndSentence) keepSentenceDot(src, m.range.last + 1) else ""
            }
        }
        return t
    }

    /** "." if the abbreviation's dot also ended the sentence (end of text, a new line or a capital follows). */
    private fun keepSentenceDot(text: String, after: Int): String {
        var i = after
        while (i < text.length && (text[i] == ' ' || text[i] == '\u00A0')) i++
        if (i >= text.length || text[i] == '\n') return "."
        val c = text[i]
        return if (c.isUpperCase() || c == '«' || c == '"' || c == '„') "." else ""
    }

    fun normalize(text: String): String {
        var t = expandAbbreviations(numberDashRegex.replace(text, "-"))

        // "XIX веке" → "19 веке" (spelled as an ordinal just below)
        t = romanNounRegex.replace(t) { m ->
            val n = romanToInt(m.groupValues[1]) ?: return@replace m.value
            "$n ${m.groupValues[2]}"
        }
        t = yearNounRegex.replace(t) { m ->
            val prep = m.groupValues[1]
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val noun = m.groupValues[3]
            // a count, not a year: "прошло 21 год", "ему 3 года" (was "двадцать первый год")
            val low = noun.lowercase()
            if (n < 1000 && prep.isEmpty() && (low == "год" ||
                    (low == "года" && n % 10 in 2..4 && n % 100 !in 12..14))) {
                return@replace "${spellInteger(n)} $noun"
            }
            val dative = prep.isNotEmpty() && noun.lowercase() in setOf("году", "веку", "столетию")
            val ending = if (dative) "му" else when (noun.lowercase()) {
                "году", "веке", "столетии" -> "м"
                "года", "века", "столетия" -> "го"
                "годом", "веком" -> "ым"
                "столетие" -> "ое"
                else -> "й"
            }
            val phrase = spellOrdinal(n) ?: return@replace m.value
            (if (prep.isNotEmpty()) "$prep " else "") + "${declineOrdinal(phrase, ending, n)} $noun"
        }

        // dates: the day is an ordinal (neuter), in the case its preposition asks for
        run {
            val src0 = t
            t = dateRegex.replace(src0) { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
                if (n !in 1..31) return@replace m.value
                val p = prevWordRegex.find(src0.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase() ?: ""
                val ending = when (p) {
                    in datPreps -> "му"
                    in prepPreps -> "м"
                    in insPreps -> "ым"
                    in genPreps -> "го"
                    else -> "#ое"
                }
                val phrase = spellOrdinal(n) ?: return@replace m.value
                declineOrdinal(phrase, ending, n) + " " + m.groupValues[2]
            }
        }

        // "1-5-й уровень" → "первый — пятый уровень" (was "один-пятый")
        t = ordinalRangeRegex.replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val b = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            if (a >= b) return@replace m.value
            val ending = m.groupValues[3]
            val next = m.groupValues[4]
            val nextWord = next.trim().lowercase()
            val effective = when {
                ending == "х" -> "ых"
                ending == "ми" -> "ыми"
                ending == "й" && nextWord.length > 2 &&
                    (nextWord.endsWith("е") || nextWord.endsWith("и") || nextWord.endsWith("ой") || nextWord.endsWith("ей")) -> "ей"
                else -> ending
            }
            val pa = spellOrdinal(a) ?: return@replace m.value
            val pb = spellOrdinal(b) ?: return@replace m.value
            declineOrdinal(pa, effective, a) + " — " + declineOrdinal(pb, effective, b) + next
        }

        t = ordinalRegex.replace(t) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val ending = m.groupValues[2]
            val next = m.groupValues[3]
            // cardinals with a case ending
            when (ending) {
                "мя" -> cardinalIns[n]?.let { return@replace it + next }
                "ти" -> cardinalTi[n]?.let { return@replace it + next }
                "х" -> cardinalGen[n]?.let { return@replace it + next }
                "ми" -> if (n == 7L || n == 8L) cardinalTi[n]?.let { return@replace it + next }
            }
            if (ending == "мя" || ending == "ти") return@replace m.value
            // "-й" before a feminine noun in an oblique case: "на 3-й странице" → "третьей"
            val nextWord = next.trim().lowercase()
            val effective = if (ending == "й" && nextWord.length > 2 &&
                (nextWord.endsWith("е") || nextWord.endsWith("и") || nextWord.endsWith("ой") || nextWord.endsWith("ей"))) "ей"
                else ending
            val phrase = spellOrdinal(n) ?: return@replace m.value
            declineOrdinal(phrase, effective, n) + next
        }

        t = compoundRegex.replace(t) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val pre = compoundPrefix[n] ?: return@replace m.value
            pre + m.groupValues[2]
        }

        t = timeRegex.replace(t) { m ->
            val h = m.groupValues[1].toLong()
            val mm = m.groupValues[2].toLong()
            val minutes = if (mm < 10) "ноль ${spellInteger(mm)}" else spellInteger(mm)
            "${spellInteger(h)} $minutes"
        }

        t = yearAbbrRegex.replace(t) { m ->
            val prep = m.groupValues[1]
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val phrase = spellOrdinal(n) ?: return@replace m.value
            if (prep.isNotEmpty()) "$prep ${declineOrdinal(phrase, "м", n)} году"
            else "${declineOrdinal(phrase, "го", n)} года"
        }
        t = celsiusUnitRegex.replace(t) { m ->
            val u = MeasureUnit("градус", "градуса", "градусов")
            spellNumberForUnit(m.groupValues[1], u) + if (m.groupValues[2].isNotEmpty()) " Цельсия" else ""
        }
        t = unitRegex.replace(t) { m ->
            val raw = m.groupValues[1]
            val after = m.value.substring(m.value.indexOf(raw) + raw.length).trimStart()
            val key = unitAt.find(after)?.value ?: return@replace m.value
            val unit = measureUnits[key] ?: return@replace m.value
            val prevWord = Regex("(\\p{L}+)\\s*$").find(t.substring(0, m.range.first))?.groupValues?.get(1) ?: ""
            spellNumberForUnit(raw, unit, prevWord)
        }

        // Range: "10-15" / "10—15" -> "от 10 до 15" (then numbers spelled out below).
        t = rangeRegex.replace(t) { m ->
            "от ${m.groupValues[1]} до ${m.groupValues[2]}"
        }

        // Percent: "15%" or "15 %"
        t = percentRegex.replace(t) { m ->
            "${m.groupValues[1]} процентов"
        }

        // Degrees Celsius: "−5°C" or "5 °C"
        t = celsiusRegex.replace(t) { m ->
            "${m.groupValues[1]} градусов Цельсия"
        }
        t = degreesRegex.replace(t) { m ->
            "${m.groupValues[1]} градусов"
        }

        // Decimals with comma: "3,14"
        t = decimalRegex.replace(t) { m ->
            val sign = if (m.groupValues[1].startsWith("-")) { "минус " } else ""
            val intPart = m.groupValues[1].trimStart('-').toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            "$sign${spellDecimal(intPart, frac)}"
        }

        // Plain integers — last, after compound forms above have already
        // rewritten themselves into "<number> <unit>".
        val src = t
        t = integerRegex.replace(src) { m ->
            val raw = m.groupValues[1]
            val n = raw.toLongOrNull() ?: return@replace raw
            // in the case the preposition or the noun asks for ("из двух вариантов")
            if (n >= 0) {
                val at = caseAround(src.substring(0, m.range.first), src.substring(m.range.last + 1), n)
                // 1000–2100 alone after a preposition is most likely a year ("с 1941 по 1945"):
                // left as it was; with a noun's ending ("к 2000 метрам") it is a count
                val yearLike = n in 1000..2100 && !at.byNoun
                if (at.case >= 0 && !yearLike) spellCardinalCase(n, at.case, at.feminine)?.let { return@replace it }
            }
            natural(spellInteger(n))
        }

        return whitespaceRegex.replace(t, " ").trim()
    }
}
