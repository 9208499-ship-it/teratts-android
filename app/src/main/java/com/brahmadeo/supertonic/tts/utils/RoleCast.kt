package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * Who reads whom in reading by roles — one place for the reader and the Characters window, so
 * the window shows what will be heard.
 *
 * The characters of the whole book are taken in the order of their lines (most first), and voices
 * are handed out in that order: the main hero and heroine get the voices chosen for them (if any),
 * the next ones — free voices of their sex, the favourite ones first; a voice not for characters is
 * never handed out. When the free voices of a sex run out, the rest are read by the common voice of
 * that sex ("Мужские реплики" / "Женские реплики") — two characters never share a voice by
 * chance. A voice once given stays for the whole book.
 */
object RoleCast {
    enum class Kind {
        /** chosen by hand in the Characters window */
        MANUAL,
        /** the main hero's / heroine's voice from the settings */
        HERO,
        /** handed out automatically (and remembered) */
        AUTO,
        /** no voice of their own: the common male / female voice (or the unknown lines' one) */
        COMMON,
        /** «Я» without a voice of his own: the narrator's */
        NARRATOR
    }

    class Entry(val voice: String?, val kind: Kind)

    class Cast(val entries: Map<String, Entry>, val mainHero: String?, val mainHeroine: String?) {
        fun voice(name: String) = entries[name]?.voice
        fun kind(name: String) = entries[name]?.kind
    }

    /** The common voices as the running model has them (file names). */
    class Common(val narrator: String, val male: String, val female: String, val unknown: String) {
        val all get() = setOf(male, female, unknown)
    }

    /** The common voices as reading picks them (the main voice = the one on the main screen) for the book [scope]. */
    fun common(context: Context, scope: String = ""): Common {
        val prefs = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val main = RolePrefs.forEngine(context, prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json")
        val narrator0 = RolePrefs.narratorVoice(context).takeIf { it.isNotEmpty() }?.let { RolePrefs.forEngine(context, it) } ?: main
        val narrator = teller(context, scope, BookRoster.cached(context, scope), narrator0) ?: narrator0
        // nobody but the narrator and the hero speaks in the narrator's voice
        fun notNarrator(v: String, female: Boolean) = if (v != narrator) v else CharacterVoices.alternative(context, narrator, female) ?: v
        val male = notNarrator(RolePrefs.forRole(context, RolePrefs.maleVoice(context)), false)
        val female = notNarrator(RolePrefs.forRole(context, RolePrefs.femaleVoice(context)), true)
        val unknown = RolePrefs.unknownVoice(context).takeIf { it.isNotEmpty() }
            ?.let { RolePrefs.forRole(context, it) }?.let { notNarrator(it, CharacterVoices.isFemaleVoice(it)) } ?: male
        return Common(narrator, male, female, unknown)
    }

    /**
     * A book told in the first person is the teller's own story («я умудрилась»): its narration is said in
     * the teller's voice and sex, not in the narrator's (Lecron, 4PDA). The voice set for «Я» by hand; or the
     * main hero's / heroine's from the settings when «Я» is the main one of that sex; or — when the
     * narrator's voice is of the other sex — the common voice of the teller's sex (the others of that sex
     * then get another one). Null: the narrator's voice as it is (a third-person book, one told by several
     * people, the teller's sex not clear, the setting off).
     */
    fun teller(context: Context, scope: String, roster: BookRoster.Roster?, narrator: String): String? {
        if (scope.isEmpty() || roster == null || !RolePrefs.enabled(context) || !RolePrefs.tellerVoice(context)) return null
        val female = when (roster.teller) {
            DialogueAnalyzer2.Teller.FEMALE -> true
            DialogueAnalyzer2.Teller.MALE -> false
            else -> return null
        }
        val hero = DialogueAnalyzer2.HERO
        if (RolePrefs.charactersEnabled(context)) {
            if (CharacterVoices.isManual(context, hero, scope))
                CharacterVoices.explicit(context, hero, scope)?.let { return RolePrefs.forEngine(context, it) }
            val sex = if (female) DialogueAnalyzer2.Role.FEMALE else DialogueAnalyzer2.Role.MALE
            val main = roster.ranking.firstOrNull { it != DialogueAnalyzer2.SYSTEM && roster.genders[it] == sex }
            if (main == hero) RolePrefs.heroVoice(context, female).takeIf { it.isNotEmpty() }
                ?.let { RolePrefs.forEngine(context, it) }?.takeIf { it in RolePrefs.availableVoices(context) }?.let { return it }
        }
        if (if (female) CharacterVoices.isFemaleVoice(narrator) else CharacterVoices.isMaleVoice(narrator)) return null
        return RolePrefs.forRole(context, if (female) RolePrefs.femaleVoice(context) else RolePrefs.maleVoice(context)).takeIf { it.isNotEmpty() }
    }

    /** [lines]: in the whole book (empty for a roster built before 1.34). */
    data class Book(val order: List<String>, val genders: Map<String, DialogueAnalyzer2.Role>, val lines: Map<String, Int>)

    /**
     * The characters in order (the whole book's by lines when it is known, then this text's
     * others) and their sex (the whole book's first), under the names merges give them.
     */
    fun book(roster: BookRoster.Roster?, result: DialogueAnalyzer2.Result, userAliases: Map<String, String>): Book {
        val aliases = (roster?.aliases ?: emptyMap()) + userAliases
        fun resolve(n: String): String {
            var cur = n
            repeat(8) { cur = aliases[cur] ?: return cur }
            return cur
        }
        val order = ((roster?.ranking ?: emptyList()).map { resolve(it) } + result.characters.map { it.name }).distinct()
        val genders = HashMap<String, DialogueAnalyzer2.Role>()
        for (c in result.characters) {
            val g = roster?.genders?.get(c.name) ?: c.role
            if (g == DialogueAnalyzer2.Role.MALE || g == DialogueAnalyzer2.Role.FEMALE) genders[c.name] = g
        }
        roster?.genders?.forEach { (n, g) -> genders.putIfAbsent(resolve(n), g) }
        val lines = HashMap<String, Int>()
        roster?.lines?.forEach { (n, l) -> resolve(n).let { lines[it] = (lines[it] ?: 0) + l } }
        return Book(order, genders, lines)
    }

    /**
     * Voices for the characters [order] (names as in the text) of the book [scope]. Automatic
     * voices are handed out (and remembered) here. [common]: the voices not to give to anybody.
     */
    fun cast(
        context: Context, scope: String, order: List<String>, genders: Map<String, DialogueAnalyzer2.Role>,
        common: Common
    ): Cast {
        val male = DialogueAnalyzer2.Role.MALE
        val female = DialogueAnalyzer2.Role.FEMALE
        val mainHero = order.firstOrNull { it != DialogueAnalyzer2.SYSTEM && genders[it] == male }
        val mainHeroine = order.firstOrNull { it != DialogueAnalyzer2.SYSTEM && genders[it] == female }
        if (!RolePrefs.charactersEnabled(context)) return Cast(emptyMap(), mainHero, mainHeroine)

        val available = RolePrefs.availableVoices(context)
        val rating = RolePrefs.ratings(context, available)
        val heroM = RolePrefs.heroVoice(context, false).takeIf { it.isNotEmpty() }
            ?.let { RolePrefs.forEngine(context, it) }?.takeIf { it in available && mainHero != null }
        val heroF = RolePrefs.heroVoice(context, true).takeIf { it.isNotEmpty() }
            ?.let { RolePrefs.forEngine(context, it) }?.takeIf { it in available && mainHeroine != null }
        val heroVoices = setOfNotNull(heroM, heroF)
        val mainSet = order.filter { it != DialogueAnalyzer2.HERO }.take(RolePrefs.mainCount(context)).toSet() + DialogueAnalyzer2.SYSTEM
        val stored = HashMap(CharacterVoices.all(context, scope))
        val out = LinkedHashMap<String, Entry>()

        fun fits(v: String, g: DialogueAnalyzer2.Role?) = when (g) {
            male -> CharacterVoices.isMaleVoice(v)
            female -> CharacterVoices.isFemaleVoice(v)
            else -> false
        }
        fun allowed(v: String, g: DialogueAnalyzer2.Role?) = v in available && fits(v, g) && !v.contains("whisper") &&
            v != common.narrator && v !in common.all && v !in heroVoices && rating[v] != RolePrefs.NOT_FOR_CHARACTERS

        for (name in order) {
            val g = genders[name]
            val st = stored[name]
            // chosen by hand: kept (as the model has it), whatever it is
            if (st != null && CharacterVoices.isManual(context, name, scope)) {
                out[name] = Entry(RolePrefs.forEngine(context, st), Kind.MANUAL)
                continue
            }
            val heroPick = when (name) {
                mainHero -> heroM
                mainHeroine -> heroF
                else -> null
            }
            if (heroPick != null) {
                out[name] = Entry(heroPick, Kind.HERO)
                continue
            }
            // «Я» speaks in the narrator's voice unless a voice was set for him
            if (name == DialogueAnalyzer2.HERO) {
                out[name] = Entry(null, Kind.NARRATOR)
                continue
            }
            if (st != null) {
                if (allowed(st, g)) { out[name] = Entry(st, Kind.AUTO); continue }
                CharacterVoices.forget(context, name, scope)
                stored.remove(name)
            }
            if (name !in mainSet || (g != male && g != female)) {
                out[name] = Entry(null, Kind.COMMON)
                continue
            }
            val taken = stored.filterKeys { it != name }.values.toHashSet()
            val pick = available.filter { allowed(it, g) && it !in taken }
                .sortedWith(compareBy({ -(rating[it] ?: 0) }, { CharacterVoices.order(it) }, { it }))
                .firstOrNull()
            if (pick == null) {
                // no free voice of that sex: the common one, not somebody else's
                out[name] = Entry(null, Kind.COMMON)
                continue
            }
            CharacterVoices.store(context, name, pick, scope)
            stored[name] = pick
            out[name] = Entry(pick, Kind.AUTO)
        }
        return Cast(out, mainHero, mainHeroine)
    }
}
