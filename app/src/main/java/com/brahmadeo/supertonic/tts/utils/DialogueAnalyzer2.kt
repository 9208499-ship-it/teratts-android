package com.brahmadeo.supertonic.tts.utils

/**
 * Reading by roles, v2: not just "a man / a woman" but WHO speaks, and HOW.
 *
 *  - Speaker: a name from the author words ("сказал Пётр"), a role noun
 *    ("ответил старик", "произнёс компьютер", "ИИ замолчал"), or a pronoun
 *    ("сказал он") resolved against the people in the current dialogue: in an
 *    exchange between two men, "он" after Пётр's line is the other man.
 *    Lines without author words alternate between the last two speakers.
 *  - Manner of a line from the author words: шёпот / крик / быстро / медленно.
 *  - Character traits for the whole book: habitual manner and age words
 *    (старик, бабушка, мальчик…) give each character a base speed.
 *
 * When unsure, the line keeps only its gender (or none) — never a wild guess.
 * Pure Kotlin, no Android classes.
 */
object DialogueAnalyzer2 {

    enum class Role { NARRATOR, SPEECH, MALE, FEMALE }
    enum class Manner { NORMAL, WHISPER, SHOUT, FAST, SLOW }

    data class Span(
        val start: Int, val end: Int, val role: Role,
        val speaker: String? = null, val manner: Manner = Manner.NORMAL
    )

    /** A speaking character: canonical name, gender, how many lines, suggested speed. */
    data class Character(val name: String, val role: Role, val lines: Int, val speed: Float, val traits: List<String>)

    class Result(val spans: List<Span>, val characters: List<Character>)

    /**
     * What is known beyond this text: genders of characters learned from the whole
     * book, and the user's corrections — speechKey(line) → speaker name, or
     * [FORCE_NARRATOR] / [FORCE_UNKNOWN]. A correction also steers turn-taking: the
     * lines after it alternate from the right person.
     */
    class Prior(
        val genders: Map<String, Role> = emptyMap(),
        val overrides: Map<String, String> = emptyMap()
    )

    const val FORCE_NARRATOR = "#narrator"
    const val FORCE_UNKNOWN = "#unknown"

    /** Normalised key of a line for corrections: letters and digits only. */
    fun speechKey(line: String): String =
        line.lowercase().replace('ё', 'е').replace(Regex("[^а-яa-z0-9]+"), " ").trim().take(80)

    /** Case forms of a Russian name: "Пётр" → петра, петру, петром, петре; "Маша" → маши, машу… */
    fun nameForms(name: String, female: Boolean?): Set<String> {
        val n = name.lowercase().replace('ё', 'е')
        val forms = linkedSetOf(n)
        val vowels = "аеиоуыэюя"
        when {
            n.first().isDigit() -> if (n.endsWith("й")) {
                val st = n.dropLast(1); forms += listOf(st + "го", st + "му", st + "м")
            }
            n.endsWith("ия") -> { val st = n.dropLast(1); forms += listOf(st + "и", st + "ю", st + "ей") }
            n.endsWith("а") && n.length > 2 -> {
                val st = n.dropLast(1); val c = st.last()
                forms += listOf(st + (if (c in "гкхжшщч") "и" else "ы"), st + "е", st + "у", st + "ою",
                    st + (if (c in "жшщчц") "ей" else "ой"))
            }
            n.endsWith("я") && n.length > 2 -> { val st = n.dropLast(1); forms += listOf(st + "и", st + "е", st + "ю", st + "ей") }
            n.endsWith("й") -> { val st = n.dropLast(1); forms += listOf(st + "я", st + "ю", st + "ем", st + "е") }
            n.endsWith("ь") -> {
                val st = n.dropLast(1)
                forms += if (female == true) listOf(st + "и", st + "ью") else listOf(st + "я", st + "ю", st + "ем", st + "е")
            }
            n.last() !in vowels -> {
                // fleeting vowel: Павел → Павла
                val stems = linkedSetOf(n)
                if (n.length > 4 && Regex(".*[^аеиоуыэюя](ел|ек|ок)$").matches(n)) stems += n.dropLast(2) + n.last()
                for (st in stems) forms += listOf(st + "а", st + "у", st + "ом", st + "е")
            }
        }
        return forms
    }

    data class Piece(val text: String, val role: Role, val speaker: String?, val manner: Manner)

    private enum class G { M, F }

    // ------------------------------------------------------------- lexicons

    private val dashes = setOf('—', '–', '-', '―')
    private val SPACED_DASH = Regex("""\s[—–―-]\s""")
    /** A dash opening a line inside a paragraph: after ":" or the end of a sentence, before a capital or a quote. */
    private val INNER_DASH_LINE = Regex("""(?<=[:.!?…])\s+[—–―]\s+(?=[«"„“\p{Lu}])""")
    private val WORD = Regex("""\d+[‑-][а-яё]+|[А-Яа-яЁё]+""")

    /** First-person narration: "— … — уточнил я". The hero speaks in the narrator's voice. */
    const val HERO = "Я"

    /** The voice in the hero's head: a neural net, a system, notifications ("В голове прозвучало: «…»"). */
    const val SYSTEM = "Голос в голове"

    /** Words that introduce a message heard in the head or shown to the hero. */
    private val systemCues = listOf("в голове", "прозвучал", "прозвучало", "раздал", "голос", "высветил",
        "всплыл", "сообщени", "уведомлени", "надпись", "появилось", "пришло", "пискнул", "звякнул")

    /** Content of a bare «…» message that reads like a system report rather than the hero's thought. */
    private val systemContent = Regex(
        // (?<![а-яё]) instead of \b: Java's \b does not see Cyrillic letters as word characters
        """(?i)(?<![а-яё])(подключен|инсталлирован|вероятност|опци|операци|выполнено|принято|завершен|""" +
            """уровень|модул|параметр|загрузк|доступ|обнаружен|активирован|установлен|ошибк|предупреждени)|%"""
    )
    private val firstPersonThought = Regex("""(?i)(^|[^а-яё])(я|мне|меня|мой|моя|моё|мои|мы|нам|нас)([^а-яё]|$)""")

    private val notNames = setOf(
        "Он", "Она", "Они", "Оно", "Я", "Мы", "Вы", "Ты", "Это", "Тот", "Та", "То", "Те", "Там", "Тут",
        "Но", "И", "А", "Да", "Нет", "Не", "Ну", "Вот", "Так", "Как", "Что", "Кто", "Где", "Когда", "Потом",
        "Затем", "Тогда", "Его", "Её", "Ее", "Их", "Ему", "Ей", "Им", "Все", "Всё", "Уже", "Ещё", "Еще",
        "После", "Перед", "Через", "Голос", "Господин", "Госпожа", "Здесь", "Теперь", "Сейчас", "Почему",
        "Зачем", "Если", "Хорошо", "Ладно", "Конечно", "Спасибо", "Привет", "Слушай", "Смотри", "Мне", "Меня",
        "Наконец", "Вдруг", "Сначала", "Вероятно", "Видимо", "Похоже", "Кстати", "Впрочем", "Однако",
        "Опять", "Снова", "Внезапно", "Неожиданно", "Вскоре", "Сразу", "Тотчас", "Тут", "Следом", "Затем"
    )

    /** Common diminutives → full names (both directions are tried when matching people). */
    private val diminutives: Map<String, List<String>> = mapOf(
        "петя" to listOf("пётр"), "маша" to listOf("мария"), "маруся" to listOf("мария"), "саша" to listOf("александр", "александра"),
        "шура" to listOf("александр", "александра"), "ваня" to listOf("иван"), "коля" to listOf("николай"), "дима" to listOf("дмитрий"),
        "митя" to listOf("дмитрий"), "миша" to listOf("михаил"), "лёша" to listOf("алексей"), "алёша" to listOf("алексей"),
        "серёжа" to listOf("сергей"), "андрюша" to listOf("андрей"), "наташа" to listOf("наталья", "наталия"),
        "катя" to listOf("екатерина"), "лена" to listOf("елена"), "оля" to listOf("ольга"), "таня" to listOf("татьяна"),
        "женя" to listOf("евгений", "евгения"), "вова" to listOf("владимир"), "володя" to listOf("владимир"),
        "юра" to listOf("юрий"), "костя" to listOf("константин"), "паша" to listOf("павел"), "гриша" to listOf("григорий"),
        "федя" to listOf("фёдор"), "витя" to listOf("виктор"), "толя" to listOf("анатолий"), "боря" to listOf("борис"),
        "слава" to listOf("вячеслав", "ярослав", "святослав"), "света" to listOf("светлана"), "аня" to listOf("анна"),
        "надя" to listOf("надежда"), "люба" to listOf("любовь"), "галя" to listOf("галина"), "валя" to listOf("валентина", "валентин"),
        "зина" to listOf("зинаида"), "даша" to listOf("дарья"), "настя" to listOf("анастасия"), "ира" to listOf("ирина"),
        "юля" to listOf("юлия"), "ксюша" to listOf("ксения"), "соня" to listOf("софья", "софия"), "поля" to listOf("полина"),
        "лиза" to listOf("елизавета"), "вика" to listOf("виктория"), "кира" to listOf("кирилл"), "стёпа" to listOf("степан"),
        "тёма" to listOf("артём"), "рома" to listOf("роман"), "гена" to listOf("геннадий"), "жора" to listOf("георгий"),
        "гоша" to listOf("георгий", "игорь"), "вася" to listOf("василий"), "лёва" to listOf("лев"), "сеня" to listOf("семён", "арсений"),
        "макс" to listOf("максим"), "тоня" to listOf("антонина", "антон"), "люда" to listOf("людмила"), "мила" to listOf("людмила"),
        "вера" to listOf("вероника"), "лара" to listOf("лариса"), "рита" to listOf("маргарита"), "нюра" to listOf("анна"),
        "дуня" to listOf("евдокия"), "фрося" to listOf("ефросинья"), "тима" to listOf("тимофей", "тимур"), "яша" to listOf("яков"),
        "миля" to listOf("эмилия"), "эля" to listOf("элеонора", "эльвира"), "лёня" to listOf("леонид"), "веня" to listOf("вениамин")
    )

    /** Ranks and titles: "— Лейтенант, вы готовы?" addresses someone by rank, not by name. */
    private val titles = setOf(
        "лейтенант", "капитан", "майор", "полковник", "генерал", "сержант", "рядовой", "командир", "адмирал",
        "товарищ", "господин", "госпожа", "сэр", "мадам", "доктор", "профессор", "начальник", "шеф", "босс",
        "ваше", "милорд", "миледи", "сударь", "сударыня", "барин", "барыня", "отец", "батюшка", "матушка", "старик"
    )

    /** Capitalised words that open a line with a comma but are not someone's name. */
    private val notVocatives = setOf(
        "Господи", "Боже", "Чёрт", "Черт", "Блин", "Эй", "Ой", "Ах", "Эх", "Ох", "Слушайте", "Послушай",
        "Послушайте", "Знаешь", "Знаете", "Понимаешь", "Понимаете", "Извини", "Извините", "Простите",
        "Прости", "Пожалуйста", "Спасибо", "Ладно", "Значит", "Короче", "Кстати", "Впрочем", "Наверное",
        "Конечно", "Нет", "Да", "Ага", "Угу", "Ну", "Итак", "Так", "Вот", "Здравствуй", "Здравствуйте",
        "Привет", "Пока", "Товарищи", "Друзья", "Ребята", "Господа", "Дамы", "Мама", "Папа", "Сынок", "Дочка"
    )

    /** Role nouns that speak, with grammatical gender (the voice follows it). */
    private val speakerNouns: Map<String, G> = mapOf(
        "старик" to G.M, "старуха" to G.F, "мальчик" to G.M, "девочка" to G.F, "девушка" to G.F,
        "женщина" to G.F, "мужчина" to G.M, "парень" to G.M, "юноша" to G.M, "ребёнок" to G.M,
        "капитан" to G.M, "доктор" to G.M, "врач" to G.M, "солдат" to G.M, "командир" to G.M,
        "офицер" to G.M, "незнакомец" to G.M, "незнакомка" to G.F, "хозяин" to G.M, "хозяйка" to G.F,
        "бабушка" to G.F, "дедушка" to G.M, "дед" to G.M, "бабка" to G.F, "мать" to G.F, "отец" to G.M,
        "мама" to G.F, "папа" to G.M, "король" to G.M, "королева" to G.F, "принц" to G.M,
        "принцесса" to G.F, "ведьма" to G.F, "маг" to G.M, "стражник" to G.M, "страж" to G.M,
        "продавец" to G.M, "продавщица" to G.F, "водитель" to G.M, "секретарь" to G.M,
        "секретарша" to G.F, "официант" to G.M, "официантка" to G.F, "начальник" to G.M,
        "профессор" to G.M, "учитель" to G.M, "учительница" to G.F, "инженер" to G.M, "пилот" to G.M,
        "компьютер" to G.M, "робот" to G.M, "андроид" to G.M, "бот" to G.M, "автомат" to G.M,
        "система" to G.F, "машина" to G.F, "нейросеть" to G.F, "программа" to G.F,
        "ассистент" to G.M, "помощник" to G.M, "помощница" to G.F, "диспетчер" to G.M,
        "искин" to G.M, "дроид" to G.M, "дрон" to G.M, "меддроид" to G.M, "сеть" to G.F, "интеллект" to G.M
    )

    private val ageOld = setOf("старик", "старуха", "бабушка", "дедушка", "дед", "бабка")
    private val ageYoung = setOf("мальчик", "девочка", "ребёнок")
    private val machines = setOf("компьютер", "робот", "андроид", "бот", "автомат", "система", "машина",
        "нейросеть", "программа", "ассистент", "искин", "дроид", "дрон", "меддроид", "сеть", "интеллект")

    /** Stems in the author words that tell how a line is said. */
    private val mannerStems: List<Pair<String, Manner>> = listOf(
        "прошепт" to Manner.WHISPER, "шепну" to Manner.WHISPER, "шепча" to Manner.WHISPER,
        "вполголоса" to Manner.WHISPER, "еле слышно" to Manner.WHISPER, "тихо" to Manner.WHISPER,
        "прошелест" to Manner.WHISPER,
        "крикну" to Manner.SHOUT, "закрича" to Manner.SHOUT, "заор" to Manner.SHOUT, "рявкну" to Manner.SHOUT,
        "воскликну" to Manner.SHOUT, "выкрикну" to Manner.SHOUT, "взрев" to Manner.SHOUT, "гаркну" to Manner.SHOUT,
        "завопи" to Manner.SHOUT, "громко" to Manner.SHOUT,
        "выпали" to Manner.FAST, "затарато" to Manner.FAST, "протарато" to Manner.FAST,
        "скороговоркой" to Manner.FAST, "быстро" to Manner.FAST, "торопливо" to Manner.FAST,
        "протяну" to Manner.SLOW, "медленно" to Manner.SLOW, "неторопливо" to Manner.SLOW,
        "растягивая" to Manner.SLOW, "нараспев" to Manner.SLOW
    )

    private val notVerbs = setOf(
        "дела", "тела", "пола", "стола", "мела", "мила", "сила", "вила", "кила", "мгла", "игла", "стрела",
        "смола", "школа", "пчела", "крыла", "скула", "мул", "пол", "стол", "вол", "тыл", "ил", "зал", "бал",
        "вал", "кал", "мел", "угол", "орёл", "орел", "посол", "котёл", "котел", "дятел", "пепел"
    )

    // ------------------------------------------------------------------ API

    fun analyze(text: String, prior: Prior? = null): Result {
        val paragraphs = paragraphRanges(text)
        val parsed = paragraphs.map { (s, e) -> parseParagraph(text, s, e) }

        // pass 1: learn names' genders from author words with a gendered verb
        val nameVotes = HashMap<String, IntArray>()
        for (p in parsed) if (p.speech.isNotEmpty()) for ((a, b) in authorPieces(text, p)) {
            val h = hint(words(text, a, b))
            val g = h.verbGender ?: continue
            val n = h.name ?: continue
            val c = nameVotes.getOrPut(n) { IntArray(2) }
            if (g == G.M) c[0]++ else c[1]++
        }
        val nameGender = HashMap<String, G>()
        for ((n, c) in nameVotes) {
            if (c[0] > c[1] * 2) nameGender[n] = G.M else if (c[1] > c[0] * 2) nameGender[n] = G.F
        }
        prior?.genders?.forEach { (n, r) ->
            if (n !in nameGender) when (r) { Role.MALE -> nameGender[n] = G.M; Role.FEMALE -> nameGender[n] = G.F; else -> {} }
        }
        // every case form of every known name → the name ("Петра", "Петру" → "Пётр")
        val nameOf = HashMap<String, String>()
        for ((n, g) in nameGender) if (n.first().isUpperCase() || n.first().isDigit()) {
            for (f in nameForms(n, g == G.F)) nameOf.putIfAbsent(f, n)
        }
        fun canon(w: String): String? = nameOf[w.lowercase().replace('ё', 'е')]
        // who is spoken TO: "— Маша, иди сюда", "…, Маша!", "сказал Петру", "посмотрела на Машу"
        // a vocative is in the nominative, so even a name not seen before counts
        fun known(w: String): String? = canon(w) ?: run {
            // Петя ↔ Пётр, Маша ↔ Мария: a known person under another form of the name
            val lw = w.lowercase()
            val linked = (diminutives[lw] ?: emptyList()) + diminutives.filterValues { lw in it }.keys
            linked.firstNotNullOfOrNull { canon(it) }
        }
        fun vocative(w: String): String? {
            if (w.lowercase() in titles) return null
            return known(w) ?: w.takeIf { it.length >= 3 && it[0].isUpperCase() && it !in notNames && it !in notVocatives }
        }
        fun addresseeIn(speech: String, authorWords: List<String>): String? {
            val sw = WORD.findAll(speech).map { it.value }.toList()
            sw.firstOrNull()?.let { first ->
                val after = speech.substringAfter(first, "").trimStart()
                if (after.startsWith(",") || after.startsWith("!")) vocative(first)?.let { return it }
            }
            // ", Маша," / ", Маша!" anywhere in the line
            Regex(""",\s*([А-ЯЁ][а-яё]+)\s*(?=[,!?.…»”"])""").findAll(speech).forEach { m ->
                vocative(m.groupValues[1])?.let { return it }
            }
            for (w in authorWords) {
                val c = canon(w) ?: continue
                if (w.lowercase().replace('ё', 'е') != c.lowercase().replace('ё', 'е')) return c   // an oblique form
            }
            return null
        }
        var heroGender: G? = null
        fun genderOf(speaker: String?): G? = when {
            speaker == null -> null
            speaker == HERO -> heroGender
            speaker == SYSTEM -> G.F
            // numbered names agree like adjectives: "896‑й" (m), "17‑я" (f)
            speaker.first().isDigit() && nameGender[speaker] == null ->
                if (speaker.endsWith("я")) G.F else G.M
            speaker in speakerNouns -> speakerNouns[speaker]
            else -> nameGender[speaker]
        }

        // pass 2: speakers, with a memory of the current dialogue
        val out = ArrayList<Span>()
        val run = ArrayList<String>()                 // speakers of the current exchange, oldest first
        val lastMentioned = HashMap<G, String>()       // most recent person of each gender
        val lineCount = HashMap<String, Int>()
        val mannerCount = HashMap<String, IntArray>()  // per speaker, by Manner.ordinal
        var pendingQuoteSpeaker: String? = null        // set by "В голове прозвучало:" for the next «…»
        var pendingFromIntro = false                   // true only for an introduction ending with ":"
        var lastAddressee: String? = null              // who the previous line spoke to — likely answers next
        var heroAttributed = 0                         // "сказал я": the book is told in the first person

        for (p in parsed) {
            if (p.speech.isEmpty()) {
                // narration: remember who was mentioned; a long passage ends the exchange
                for (w in words(text, p.start, p.end)) {
                    val n = canon(w) ?: continue
                    val g = nameGender[n] ?: continue
                    lastMentioned[g] = n
                }
                // "В голове прозвучало:" / "Раздался голос 896‑го:" → who speaks in the next «…»
                pendingQuoteSpeaker = introSpeaker(text.substring(p.start, p.end))
                pendingFromIntro = pendingQuoteSpeaker != null
                if (p.end - p.start > 300) { run.clear(); lastAddressee = null }
                out.addAll(p.spans(null, null, Manner.NORMAL))
                continue
            }
            val authors = authorPieces(text, p)
            val h = authors.asSequence().map { (a, b) -> hint(words(text, a, b)) }
                .firstOrNull { it.name != null || it.noun != null || it.hero || it.pronoun != null || it.verbGender != null }
            val manner = authors.asSequence().map { (a, b) -> mannerOf(text.substring(a, b)) }
                .firstOrNull { it != Manner.NORMAL } ?: Manner.NORMAL

            var speaker: String? = null
            var gender: G? = null
            val forced = prior?.overrides?.takeIf { it.isNotEmpty() }?.let { ov ->
                p.speech.asSequence().mapNotNull { (a, b, _) -> ov[speechKey(text.substring(a, b))] }.firstOrNull()
            }
            if (forced == FORCE_NARRATOR) {
                // the user says this is not a line at all: read it as narration
                out.addAll(p.pieces.map { (a, b, _) -> Span(a, b, Role.NARRATOR) })
                continue
            }
            val speechText = p.speech.joinToString(" ") { (a, b, _) -> text.substring(a, b) }
            val addressee = if (forced == null) {
                addresseeIn(speechText, authors.flatMap { (a, b) -> words(text, a, b) })
            } else null
            if (forced != null) {
                speaker = if (forced == FORCE_UNKNOWN) null else forced
                gender = genderOf(speaker)
            } else if (p.quoted) {
                // a «…» message: introduced in this paragraph ("прозвучал голос: «…»"), by the
                // previous one ("В голове прозвучало:"), or recognisable by its content
                val intro = authors.firstOrNull()?.let { (a, b) -> introSpeaker(text.substring(a, b)) }
                val bySubject = h?.let { it.name ?: it.noun ?: if (it.hero) HERO else null }
                speaker = intro ?: bySubject
                if (speaker == null && authors.isEmpty()) {
                    val body = p.speech.joinToString(" ") { (a, b, _) -> text.substring(a, b) }
                    speaker = when {
                        firstPersonThought.containsMatchIn(body) -> HERO      // the hero thinking: narrator's voice
                        pendingQuoteSpeaker != null -> pendingQuoteSpeaker     // a run of messages
                        systemContent.containsMatchIn(body) -> SYSTEM
                        else -> null
                    }
                }
                pendingQuoteSpeaker = if (authors.isEmpty()) speaker else null
                pendingFromIntro = false   // a run of «…» messages does not carry over to dash lines
                gender = if (speaker == SYSTEM) G.F else h?.verbGender ?: genderOf(speaker)
            } else if (authors.isEmpty() && pendingQuoteSpeaker != null && pendingFromIntro) {
                // "Раздался голос 896‑го:" followed by a dash line
                speaker = pendingQuoteSpeaker
                gender = genderOf(speaker)
                pendingQuoteSpeaker = null
                pendingFromIntro = false
            } else if (h != null) {
                when {
                    h.hero -> { speaker = HERO; gender = h.verbGender; heroAttributed++ }
                    h.name != null -> {
                        speaker = canon(h.name) ?: h.name
                        gender = h.verbGender ?: nameGender[speaker]
                    }
                    h.noun != null -> { speaker = h.noun; gender = speakerNouns[h.noun] }
                    else -> {
                        gender = h.pronoun ?: h.verbGender
                        val prev = run.lastOrNull()
                        val others = run.filter { genderOf(it) == gender && it != prev && it != HERO }
                        val sameGenderOthers = others.filter { isHuman(it) }.ifEmpty { others }
                        speaker = when {
                            // turn-taking: "он" right after one man spoke is the other man
                            prev != null && genderOf(prev) == gender && sameGenderOthers.isNotEmpty() ->
                                sameGenderOthers.last()
                            prev != null && genderOf(prev) != gender ->
                                (run.lastOrNull { genderOf(it) == gender && isHuman(it) }
                                    ?: run.lastOrNull { genderOf(it) == gender }) ?: lastMentioned[gender]
                            prev == null -> lastMentioned[gender]
                            else -> null
                        }
                    }
                }
            } else if (authors.isEmpty() && !p.quoted) {
                // bare line: the one just spoken to answers; otherwise the two people take turns
                val asked = lastAddressee
                if (asked != null && run.lastOrNull() != asked) {
                    speaker = asked
                } else {
                    val distinct = run.distinct()
                    if (distinct.size >= 2 && run.size >= 2) speaker = run[run.size - 2]
                }
            }
            if (forced == null && addressee != null) {
                if (speaker == addressee) {
                    // "— Маша, иди сюда" cannot be Маша's own line
                    speaker = run.lastOrNull { it != addressee }
                    gender = null
                } else if (speaker == null && authors.isEmpty() && !p.quoted) {
                    speaker = run.lastOrNull { it != addressee }
                }
            }
            if (!p.quoted) lastAddressee = addressee
            if (speaker == HERO && gender != null) heroGender = gender
            if (speaker != null && gender == null) gender = genderOf(speaker)
            if (speaker != null) {
                if (!p.quoted) run.add(speaker)
                if (isHuman(speaker)) gender?.let { lastMentioned[it] = speaker }
                lineCount[speaker] = (lineCount[speaker] ?: 0) + 1
                mannerCount.getOrPut(speaker) { IntArray(Manner.values().size) }[manner.ordinal]++
            }
            out.addAll(p.spans(gender, speaker, manner))
        }

        // Not a first-person book: unattributed «…» thoughts are not "the hero-narrator's" —
        // leave them unknown (the reader gives them the main character's voice).
        if (heroAttributed == 0 && HERO in lineCount) {
            for (i in out.indices) if (out[i].speaker == HERO) out[i] = out[i].copy(speaker = null, role = Role.SPEECH)
            lineCount.remove(HERO)
            mannerCount.remove(HERO)
        }

        // One machine, two names: "искин" / "компьютер" and a single numbered name ("896‑й")
        // are the same character in practice — merge them under the numbered name.
        val numbered = lineCount.keys.filter { it.first().isDigit() }
        val alias = HashMap<String, String>()
        if (numbered.size == 1) {
            for (m in lineCount.keys) if (m in machines) alias[m] = numbered[0]
        }
        if (alias.isNotEmpty()) {
            for (i in out.indices) out[i].speaker?.let { sp -> alias[sp]?.let { out[i] = out[i].copy(speaker = it) } }
            for ((from, to) in alias) {
                lineCount[to] = (lineCount[to] ?: 0) + (lineCount.remove(from) ?: 0)
                val a = mannerCount.remove(from)
                if (a != null) { val b = mannerCount.getOrPut(to) { IntArray(a.size) }; for (k in a.indices) b[k] += a[k] }
                if (nameGender[to] == null) speakerNouns[from]?.let { nameGender[to] = it }
            }
        }

        val characters = lineCount.entries.sortedByDescending { it.value }.map { (name, n) ->
            val g = genderOf(name)
            val counts = mannerCount[name] ?: IntArray(Manner.values().size)
            val traits = ArrayList<String>()
            var speed = 1.0f
            if (name in ageOld) { speed *= 0.9f; traits.add("пожилой") }
            if (name in ageYoung) { speed *= 1.08f; traits.add("ребёнок") }
            if (name in machines) traits.add("машина")
            val fast = counts[Manner.FAST.ordinal]
            val slow = counts[Manner.SLOW.ordinal]
            if (n >= 3 && fast * 3 >= n) { speed *= 1.1f; traits.add("говорит быстро") }
            if (n >= 3 && slow * 3 >= n) { speed *= 0.9f; traits.add("говорит медленно") }
            if (n >= 3 && counts[Manner.WHISPER.ordinal] * 3 >= n) traits.add("говорит тихо")
            if (n >= 3 && counts[Manner.SHOUT.ordinal] * 3 >= n) traits.add("говорит громко")
            Character(name, when (g) { G.M -> Role.MALE; G.F -> Role.FEMALE; null -> Role.SPEECH },
                n, speed.coerceIn(0.85f, 1.15f), traits)
        }
        return Result(out, characters)
    }

    /**
     * Apply the user's merges ("искин" → "896‑й"): speakers in the spans are renamed and
     * the merged characters' lines and traits are combined under the target.
     */
    fun applyAliases(r: Result, aliases: Map<String, String>): Result {
        if (aliases.isEmpty()) return r
        fun resolve(n: String): String {
            var cur = n
            repeat(8) { cur = aliases[cur] ?: return cur }
            return cur
        }
        val spans = r.spans.map { sp -> sp.speaker?.let { val t = resolve(it); if (t != it) sp.copy(speaker = t) else sp } ?: sp }
        val byName = LinkedHashMap<String, MutableList<Character>>()
        for (c in r.characters) byName.getOrPut(resolve(c.name)) { ArrayList() }.add(c)
        val chars = byName.map { (name, group) ->
            val total = group.sumOf { it.lines }
            val own = group.firstOrNull { it.name == name }
            val role = own?.role?.takeIf { it != Role.SPEECH }
                ?: group.firstOrNull { it.role != Role.SPEECH }?.role ?: Role.SPEECH
            val speed = (group.sumOf { (it.speed * it.lines).toDouble() } / total.coerceAtLeast(1)).toFloat()
            Character(name, role, total, speed, group.flatMap { it.traits }.distinct())
        }.sortedByDescending { it.lines }
        return Result(spans, chars)
    }

    /** Pieces of one sentence ([offset] from [locate]); punctuation-only pieces are dropped. */
    fun piecesOf(sentence: String, offset: Int, spans: List<Span>): List<Piece> {
        if (offset < 0) return listOf(Piece(sentence, Role.NARRATOR, null, Manner.NORMAL))
        val end = offset + sentence.length
        val out = ArrayList<Piece>()
        // spans are sorted and contiguous: jump to the first one that can overlap
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].end <= offset) lo = mid + 1 else hi = mid
        }
        for (k in lo until spans.size) {
            val s = spans[k]
            if (s.start >= end) break
            val a = maxOf(s.start, offset)
            val b = minOf(s.end, end)
            if (a >= b) continue
            val piece = sentence.substring(a - offset, b - offset).trim()
            if (!piece.any { it.isLetterOrDigit() }) continue
            val last = out.lastOrNull()
            if (last != null && last.role == s.role && last.speaker == s.speaker && last.manner == s.manner) {
                out[out.size - 1] = last.copy(text = last.text + " " + piece)
            } else {
                out.add(Piece(piece, s.role, s.speaker, s.manner))
            }
        }
        return if (out.isEmpty()) listOf(Piece(sentence, Role.NARRATOR, null, Manner.NORMAL)) else out
    }

    /**
     * Where each chunk the reader speaks lies in the original text. The reader's
     * splitter glues sentences (and paragraphs) with spaces, adds a space before
     * "!"/"?" and drops commas at its cuts, so chunks are matched on letters and
     * digits only — robust to all of that. null = not found.
     */
    fun locateRanges(text: String, sentences: List<String>): List<IntRange?> {
        val map = IntArray(text.length)
        val sb = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) if (c.isLetterOrDigit()) { map[sb.length] = i; sb.append(c.lowercaseChar()) }
        val norm = sb.toString()
        var cursor = 0
        return sentences.map { sentence ->
            val ns = buildString { for (c in sentence) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }
            if (ns.isEmpty()) return@map null
            val at = norm.indexOf(ns, cursor)
            if (at < 0) return@map null
            cursor = at + ns.length
            val start = map[at]
            var end = map[at + ns.length - 1] + 1
            // keep the closing punctuation and quotes: "руках!»"
            while (end < text.length && !text[end].isLetterOrDigit() && !text[end].isWhitespace()) end++
            start until end
        }
    }

    /** Pieces (from the ORIGINAL text) of the chunk at [range]; [fallback] is read by the narrator if not found. */
    fun piecesIn(text: String, range: IntRange?, spans: List<Span>, fallback: String): List<Piece> {
        if (range == null) return listOf(Piece(fallback, Role.NARRATOR, null, Manner.NORMAL))
        val offset = range.first
        val end = range.last + 1
        val out = ArrayList<Piece>()
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].end <= offset) lo = mid + 1 else hi = mid
        }
        for (k in lo until spans.size) {
            val s = spans[k]
            if (s.start >= end) break
            val a = maxOf(s.start, offset)
            val b = minOf(s.end, end)
            if (a >= b) continue
            val piece = text.substring(a, b).trim()
            if (!piece.any { it.isLetterOrDigit() }) continue
            val last = out.lastOrNull()
            if (last != null && last.role == s.role && last.speaker == s.speaker && last.manner == s.manner) {
                out[out.size - 1] = last.copy(text = last.text + " " + piece)
            } else {
                out.add(Piece(piece, s.role, s.speaker, s.manner))
            }
        }
        return if (out.isEmpty()) listOf(Piece(fallback, Role.NARRATOR, null, Manner.NORMAL)) else out
    }

    fun locate(text: String, sentences: List<String>): IntArray {
        val res = IntArray(sentences.size) { -1 }
        var cursor = 0
        for ((i, s) in sentences.withIndex()) {
            val probe = s.trim()
            if (probe.isEmpty()) continue
            val at = text.indexOf(probe, cursor)
            if (at >= 0) { res[i] = at; cursor = at + probe.length }
        }
        return res
    }

    /** Speed factor and gain for a line's manner. */
    fun mannerSpeed(m: Manner): Float = when (m) {
        Manner.FAST -> 1.12f; Manner.SLOW -> 0.88f; Manner.WHISPER -> 0.95f; Manner.SHOUT -> 1.05f; else -> 1f
    }

    fun mannerGain(m: Manner): Float = when (m) {
        Manner.WHISPER -> 0.7f; else -> 1f
    }

    // ------------------------------------------------------------ internals

    private class Parsed(
        val start: Int, val end: Int, val pieces: List<Triple<Int, Int, Boolean>>,
        val quoted: Boolean = false   // «…» thoughts / messages, not a turn in a spoken exchange
    ) {
        val speech get() = pieces.filter { it.third }
        fun spans(g: G?, speaker: String?, manner: Manner): List<Span> = pieces.map { (s, e, isSpeech) ->
            if (!isSpeech) Span(s, e, Role.NARRATOR)
            else Span(s, e, when (g) { G.M -> Role.MALE; G.F -> Role.FEMALE; null -> Role.SPEECH }, speaker, manner)
        }
    }

    private class Hint(val name: String?, val noun: String?, val pronoun: G?, val verbGender: G?, val hero: Boolean = false)

    /** "искина" → "искин", "Сети" → "Сеть", "896‑го" → "896‑й": base forms after "голос …". */
    private fun baseForms(w: String): List<String> {
        if (w.first().isDigit()) return listOf(w.substringBefore('‑').substringBefore('-') + "‑й", w)
        val l = w.lowercase()
        return listOf(l, l.dropLast(1), l.dropLast(1) + "ь", l.dropLast(1) + "а", l.dropLast(2))
    }

    private fun words(text: String, a: Int, b: Int): List<String> =
        WORD.findAll(text.substring(a, b)).map { it.value }.toList()

    private fun authorPieces(text: String, p: Parsed): List<Pair<Int, Int>> =
        p.pieces.filter { !it.third && text.substring(it.first, it.second).any { c -> c.isLetter() } }
            .map { it.first to it.second }

    private fun mannerOf(author: String): Manner {
        val low = author.lowercase()
        for ((stem, m) in mannerStems) if (low.contains(stem)) return m
        return Manner.NORMAL
    }

    /**
     * Who speaks in the «…» introduced by [intro] (the text before a colon): the owner of
     * a voice ("голос 896‑го" → 896‑й), or the voice in the head ("в голове прозвучало").
     */
    private fun introSpeaker(intro: String): String? {
        val t = intro.trim()
        if (!t.endsWith(":")) return null
        val lastSentence = t.substringAfterLast('.').substringAfterLast('!').substringAfterLast('?')
        val low = lastSentence.lowercase()
        if (systemCues.none { low.contains(it) }) return null
        val ws = WORD.findAll(lastSentence).map { it.value }.toList()
        val vi = ws.indexOfFirst { it.lowercase() == "голос" }
        val owner = if (vi >= 0) ws.getOrNull(vi + 1) else null
        if (owner != null) {
            val forms = baseForms(owner)
            forms.firstOrNull { it in speakerNouns }?.let { return it }
            if (owner[0].isDigit()) return forms.first()
            if (owner[0].isUpperCase()) return forms.first().replaceFirstChar { it.uppercase() }
        }
        return SYSTEM
    }

    /** Gender of a past-tense verb ("сказал", "спросила", "усмехнулся", "произнёс"), or null. */
    private fun pastGender(w: String, first: Boolean): G? {
        val lw = w.lowercase()
        if (lw.length < 4 || (w[0].isUpperCase() && !first) || w[0].isDigit()) return null
        return when {
            lw.endsWith("лась") -> G.F
            lw.endsWith("лся") -> G.M
            lw.endsWith("ла") && isVerbLike(lw) -> G.F
            lw.endsWith("л") && isVerbLike(lw) -> G.M
            lw.length >= 5 && (lw.endsWith("ёс") || lw.endsWith("нес")) -> G.M   // произнёс, принёс
            else -> null
        }
    }

    private fun isNameWord(w: String) =
        w.length >= 2 && (w[0].isUpperCase() || w[0].isDigit()) && w !in notNames && w != "Я"

    /**
     * Who the author words point at. Russian puts the speaker next to the speech
     * verb ("уточнил я", "спросил искин", "Пётр кивнул"), so verb+neighbour pairs
     * are tried first across the whole phrase; then a name / role noun anywhere
     * (often at the very end: "…решил оставить последнее слово за собой искин").
     */
    private fun hint(ws: List<String>): Hint {
        val head = ws.take(16)
        // "голос искина", "голос Сети": the owner of the voice speaks
        val voiceIdx = head.indexOfFirst { it.lowercase() == "голос" }
        val owner = if (voiceIdx >= 0) head.getOrNull(voiceIdx + 1) else null
        var firstVerbG: G? = null
        if (owner != null) {
            val forms = baseForms(owner)
            val g = (0 until voiceIdx).firstNotNullOfOrNull { pastGender(head[it], it == 0) }
            forms.firstOrNull { it in speakerNouns }?.let { return Hint(null, it, null, g) }
            if (owner[0].isUpperCase() || owner[0].isDigit()) {
                val nm = if (owner[0].isDigit()) forms.first() else forms.first().replaceFirstChar { it.uppercase() }
                return Hint(nm, null, null, g)
            }
        }
        for (i in head.indices) {
            val g = pastGender(head[i], i == 0) ?: continue
            if (firstVerbG == null) firstVerbG = g
            for (n in listOf(i + 1, i - 1)) {
                val w = head.getOrNull(n) ?: continue
                val lw = w.lowercase()
                when {
                    lw == "я" -> return Hint(null, null, null, g, hero = true)
                    lw in speakerNouns -> return Hint(null, lw, null, g)
                    lw == "он" -> return Hint(null, null, G.M, g)
                    lw == "она" -> return Hint(null, null, G.F, g)
                    isNameWord(w) && pastGender(w, false) == null -> return Hint(w, null, null, g)
                }
            }
        }
        // no verb+subject pair: a name, then a role noun (the last one), then pronouns
        head.firstOrNull { isNameWord(it) && it != head.firstOrNull() || isNameWord(it) && it[0].isDigit() }
            ?.let { return Hint(it, null, null, firstVerbG) }
        head.lastOrNull { it.lowercase() in speakerNouns }?.let { return Hint(null, it.lowercase(), null, firstVerbG) }
        for (w in head.take(4)) when (w.lowercase()) {
            "он" -> return Hint(null, null, G.M, firstVerbG)
            "она" -> return Hint(null, null, G.F, firstVerbG)
        }
        if (head.take(6).any { it == "я" || it == "Я" }) return Hint(null, null, null, firstVerbG, hero = true)
        return Hint(null, null, null, firstVerbG)
    }

    /** Machines and acronyms ("ИИ", "компьютер") are rarely what "он/она" refers to. */
    private fun isHuman(speaker: String): Boolean =
        speaker != HERO && speaker != SYSTEM && speaker !in machines && !(speaker.length <= 4 && speaker.all { it.isUpperCase() }) &&
            !speaker.first().isDigit()

    private fun isVerbLike(w: String): Boolean {
        if (w in notVerbs) return false
        val stem = if (w.endsWith("ла")) w.dropLast(2) else w.dropLast(1)
        return (stem.lastOrNull() ?: return false) in "аеиоуыэюяё"
    }

    private fun paragraphRanges(text: String): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (i in 0..text.length) {
            if (i == text.length || text[i] == '\n') {
                if (i > start) res.add(start to i)
                start = i + 1
            }
        }
        return res
    }

    private fun parseParagraph(text: String, start: Int, end: Int): Parsed {
        var s = start
        while (s < end && text[s].isWhitespace()) s++
        if (s >= end) return Parsed(start, end, listOf(Triple(start, end, false)))
        if (text[s] in dashes) return parseDash(text, start, s, end)
        // a line can also start inside a paragraph: "…и сказала: — Держи себя в руках!"
        // or "Гусеница помолчала. — Держи себя в руках, — сказала она."
        val inner = INNER_DASH_LINE.find(text.substring(start, end))
        if (inner != null) {
            val dashAt = start + inner.range.first + inner.value.indexOfFirst { it in dashes }
            val before = text.substring(start, dashAt)
            // quotes in the narration part keep working ("Он сказал: «…»")
            val head = if (before.any { it in quotePairs.keys }) parseQuotes(text, start, dashAt).pieces
                       else listOf(Triple(start, dashAt, false))
            val rest = parseDash(text, dashAt, dashAt, end).pieces
            return Parsed(start, end, head + rest)
        }
        return parseQuotes(text, start, end)
    }

    private fun parseDash(text: String, pStart: Int, dashAt: Int, end: Int): Parsed {
        val pieces = ArrayList<Triple<Int, Int, Boolean>>()
        pieces.add(Triple(pStart, dashAt + 1, false))
        var speech = true
        var pos = dashAt + 1
        for (m in SPACED_DASH.findAll(text.substring(dashAt + 1, end))) {
            val a = dashAt + 1 + m.range.first
            val b = dashAt + 1 + m.range.last + 1
            val last = text.substring(pos, a).trimEnd().lastOrNull()
            val toggle = if (speech) last != null && last in ",.!?…:;" else last != null && last in ",.:"
            if (toggle) {
                pieces.add(Triple(pos, a, speech))
                pieces.add(Triple(a, b, false))
                pos = b
                speech = !speech
            }
        }
        pieces.add(Triple(pos, end, speech))
        return Parsed(pStart, end, pieces)
    }

    /** Opening quote → its closing pair: «ёлочки», „лапки“, “English”, "straight". */
    private val quotePairs = mapOf('«' to '»', '„' to '“', '“' to '”', '"' to '"')

    private fun parseQuotes(text: String, start: Int, end: Int): Parsed {
        val pieces = ArrayList<Triple<Int, Int, Boolean>>()
        var pos = start
        var i = start
        while (i < end) {
            val close = quotePairs[text[i]]
            if (close != null) {
                val at = text.indexOf(close, i + 1).let { if (it < 0 || it >= end) -1 else it }
                if (at < 0) break
                val before = text.substring(start, i).trimEnd()
                if (before.isEmpty() || before.endsWith(":")) {
                    if (i > pos) pieces.add(Triple(pos, i, false))
                    pieces.add(Triple(i, at + 1, true))
                    pos = at + 1
                }
                i = at + 1
                continue
            }
            i++
        }
        if (pos < end) pieces.add(Triple(pos, end, false))
        return Parsed(start, end, pieces, quoted = true)
    }
}
