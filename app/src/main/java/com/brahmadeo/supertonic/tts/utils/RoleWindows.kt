package com.brahmadeo.supertonic.tts.utils

/**
 * The text cut into the windows the role model reads (the same as `build_joint.py` made for its
 * training): about 4000 characters of paragraphs in a row plus ~1800 on each side, every line of
 * the core a target; the mentions in the window — the characters' names and their other names,
 * «я/меня/мне…» outside speech in a first-person book, and the author words of the neighbouring
 * lines whose speaker the rules found («— …, — ответил он»).
 */
object RoleWindows {
    const val CORE = 4000
    const val SIDE = 1800
    private const val HERO = "Я"

    /** A line of the text as the rules saw it ([DialogueAnalyzer2.Probe]); [rules] — its speaker by the rules. */
    class Line(val start: Int, val end: Int, val kind: Int, val quoted: Boolean,
               val speech: List<IntArray>, val authors: List<IntArray>, val rules: String)

    /** [type]: 0 a name, 1 «я», 2 a neighbour's author words. Offsets are in the window. */
    class Mention(val ent: String, val a: Int, val b: Int, val type: Int)

    /** [pi]: the paragraph's number in the text; [span] — the paragraph in the window, [speech] — its speech. */
    class Target(val pi: Int, val span: IntArray, val speech: List<IntArray>, val kind: Int, val rules: String) {
        val hard get() = kind == 0
    }

    class Window(val base: Int, val text: String, val speech: List<IntArray>, val ments: List<Mention>, val targets: List<Target>)

    private const val L = "А-Яа-яЁё"

    private fun pattern(n: String): Regex {
        if (' ' in n || n.any { it.isDigit() } || n.length <= 3) {
            val opts = if (n[0].isLowerCase()) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return Regex("(?<![$L])" + Regex.escape(n) + "[а-яё]{0,2}(?![$L])", opts)
        }
        val stem = if (n.last().lowercaseChar() in "аяьйоеиыу") n.dropLast(1) else n
        val first = stem[0]
        if (first.isUpperCase()) return Regex("(?<![$L])" + Regex.escape(stem) + "[а-яё]{0,3}(?![$L])")
        return Regex("(?<![$L])[" + first + first.uppercaseChar() + "]" + Regex.escape(stem.substring(1)) + "[а-яё]{0,3}(?![$L])")
    }

    private const val VOWELS = "аяьйоеиыуэюё"

    /**
     * Case forms of a name taken for names of their own → the name: «Лэнгдона», «Лэнгдону», «Лэнгдоном» →
     * «Лэнгдон»; «Анну», «Анной» → «Анна»; «Базаровым» → «Базаров» (1.57). One word, capitalised, four
     * letters or more; a known woman's name («Александра» beside «Александр») is not a form.
     */
    fun caseForms(names: Collection<String>): Map<String, String> {
        val ns = names.filter { it.length >= 3 && ' ' !in it && it[0].isUpperCase() }.toSet()
        val out = HashMap<String, String>()
        for (n in ns) {
            if (n.length < 4) continue
            val lw = n.lowercase()
            for (m in ns) {
                if (m == n || m.length >= n.length) continue
                val ml = m.lowercase()
                val male = ml.last() !in VOWELS && lw.startsWith(ml) && lw.substring(ml.length) in MALE_ENDS &&
                    !(NameGender.known(n) && NameGender.guess(n) == NameGender.G.F)
                val female = (ml.last() == 'а' || ml.last() == 'я') && lw.length > ml.length - 1 &&
                    lw.startsWith(ml.dropLast(1)) && lw.substring(ml.length - 1) in FEMALE_ENDS && !NameGender.known(n)
                if (male || female) { out[n] = m; break }
            }
        }
        return out
    }
    private val MALE_ENDS = setOf("а", "у", "ом", "е", "ым", "ем")
    private val FEMALE_ENDS = setOf("ы", "и", "е", "у", "ю", "ой", "ей", "ою", "ею")

    private val HERO_PRON = Regex("(?<![$L])(я|меня|мне|мной|мною)(?![$L])", RegexOption.IGNORE_CASE)

    /**
     * [text]: what the rules analysed; [lines]: its lines; [names]: the book's characters (after merges);
     * [aliases]: other name → name.
     */
    fun build(text: String, lines: List<Line>, names: Collection<String>, aliases: Map<String, String>): List<Window> {
        fun resolve(n: String): String { var c = n; repeat(8) { c = aliases[c] ?: return c }; return c }
        val ns = names.map { resolve(it) }.toSet()
        val pats = ArrayList<Pair<String, Regex>>()
        for (n in ns.sortedByDescending { it.length }) if (n != HERO && n.isNotEmpty()) pats += n to pattern(n)
        for ((a, to) in aliases) if (a.isNotEmpty() && a != HERO) pats += resolve(to) to pattern(a)
        val heroBook = HERO in ns

        // paragraphs
        val paras = ArrayList<IntArray>()
        var o = 0
        for (p in text.split('\n')) { paras += intArrayOf(o, o + p.length); o += p.length + 1 }
        val startToPi = HashMap<Int, Int>()
        paras.forEachIndexed { i, p -> startToPi[p[0]] = i }
        val byPi = HashMap<Int, Line>()
        for (l in lines) { val pi = startToPi[l.start] ?: continue; if (l.speech.isNotEmpty()) byPi[pi] = l }
        // the targets as in training (build_joint.py «train»): every line but a quotation and a line whose
        // author words the rules read with a pronoun («— …, — ответил он») or an introduction before it —
        // those are the neighbours whose author words are a mention of their speaker (type 2). Until 1.56
        // every line was a target, and the model never saw that mention: one of its strongest signs.
        // Such a line the rules gave nobody stays a target (the model may fill it in).
        val targets = byPi.filter { (_, l) -> !l.quoted && !((l.kind == 1 || l.kind == 5) && l.rules.isNotEmpty()) }.keys

        // cores
        val cores = ArrayList<List<Int>>()
        var cur = ArrayList<Int>(); var size = 0
        for (i in paras.indices) {
            cur += i; size += paras[i][1] - paras[i][0] + 1
            if (size >= CORE) { cores += cur; cur = ArrayList(); size = 0 }
        }
        if (cur.isNotEmpty()) cores += cur

        val out = ArrayList<Window>()
        for (core in cores) {
            val tg = core.filter { it in targets }
            if (tg.isEmpty()) continue
            var lo = core.first(); var budget = SIDE
            while (lo > 0 && budget > 0) { lo--; budget -= paras[lo][1] - paras[lo][0] + 1 }
            var hi = core.last(); budget = SIDE
            while (hi < paras.size - 1 && budget > 0) { hi++; budget -= paras[hi][1] - paras[hi][0] + 1 }
            val base = paras[lo][0]
            val w = text.substring(base, paras[hi][1])
            fun sh(r: IntArray) = intArrayOf(r[0] - base, r[1] - base)
            val speechAll = ArrayList<IntArray>()
            for (i in lo..hi) byPi[i]?.let { l -> l.speech.forEach { speechAll += sh(it) } }
            val ments = ArrayList<Mention>()
            val taken = ArrayList<IntArray>()
            fun overl(a: Int, b: Int) = taken.any { a < it[1] && it[0] < b }
            // (one matcher each: on Android every findAll() match is a new matcher with its own copy of the text)
            for ((ent, rx) in pats) {
                val m = rx.toPattern().matcher(w)
                while (m.find()) {
                    val a = m.start(); val b = m.end()
                    if (overl(a, b)) continue
                    taken += intArrayOf(a, b); ments += Mention(ent, a, b, 0)
                }
            }
            if (heroBook) {
                val m = HERO_PRON.toPattern().matcher(w)
                while (m.find()) {
                    val a = m.start(); val b = m.end()
                    if (speechAll.any { it[0] <= a && b <= it[1] } || overl(a, b)) continue
                    taken += intArrayOf(a, b); ments += Mention(HERO, a, b, 1)
                }
            }
            for (i in lo..hi) {
                val l = byPi[i] ?: continue
                if (l.rules.isEmpty() || l.kind == 0 || l.authors.isEmpty()) continue
                if (i in targets && l.kind != 0) continue      // the target's own remark: not given (it was not in training)
                for (r in l.authors) {
                    val (a, b) = sh(r).let { it[0] to it[1] }
                    val seg = w.substring(a, b)
                    val a2 = a + (seg.length - seg.trimStart(' ', ',', '–', '—', '-').length)
                    val b2 = b - (seg.length - seg.trimEnd(' ', ',', '.', ':', ';', '–', '—', '-').length)
                    if (b2 - a2 < 2 || overl(a2, b2)) continue
                    taken += intArrayOf(a2, b2); ments += Mention(l.rules, a2, b2, 2)
                }
            }
            val tl = tg.map { pi ->
                val l = byPi.getValue(pi)
                Target(pi, sh(paras[pi]), l.speech.map { sh(it) }, l.kind, l.rules)
            }
            out += Window(base, w, speechAll, ments, tl)
        }
        return out
    }
}
