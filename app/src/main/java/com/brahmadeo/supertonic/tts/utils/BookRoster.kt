package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

/**
 * Characters of the WHOLE book, worked out once in the background and cached:
 * their genders (a name with no "сказал/сказала" in this chapter may have one
 * elsewhere) and their ranking by number of lines (only the main ones get their
 * own voices). Built from the book of the current reading session.
 */
object BookRoster {
    /** [aliases]: one person under two names, found in the whole book ("Сноук" → "Лорк", the hero's own name → "Я"). */
    class Roster(val genders: Map<String, DialogueAnalyzer2.Role>, val ranking: List<String>,
                 val aliases: Map<String, String> = emptyMap())

    private fun file(context: Context, scope: String) =
        File(context.filesDir, "rosters/" + Integer.toHexString(scope.hashCode()) + ".json")

    private val building = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Bumped when the analysis changes: an older roster is built again (1.6.7: one name per person). */
    // 3 (1.6.15): the hero's own name and first name + surname are one person
    private const val VERSION = 3

    /**
     * The roster of the book, built now if there is none (or waited for if it is being built):
     * the first chapter is read with the whole book's characters too. Null after [timeoutMs].
     */
    suspend fun await(context: Context, scope: String, timeoutMs: Long): Roster? {
        cached(context, scope)?.let { return it }
        val started = System.currentTimeMillis()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { build(context, scope) }
        while (System.currentTimeMillis() - started < timeoutMs) {
            cached(context, scope)?.let { return it }
            if (job.isCompleted && scope !in building) return cached(context, scope)
            kotlinx.coroutines.delay(200)
        }
        BookSession.log(context, "roster: not ready after ${timeoutMs} ms, reading the chapter on its own")
        return null
    }

    /** Forget the book's roster: it is built again the next time the book is read. */
    fun clear(context: Context, scope: String) {
        if (scope.isNotEmpty()) file(context, scope).delete()
    }

    /** The cached roster of the book [scope], or null if it has not been built yet. */
    fun cached(context: Context, scope: String): Roster? {
        if (scope.isEmpty()) return null
        val f = file(context, scope)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            if (o.optInt("v", 1) < VERSION) return null
            val g = o.getJSONObject("genders")
            val genders = HashMap<String, DialogueAnalyzer2.Role>()
            for (k in g.keys()) genders[k] = if (g.getString(k) == "F") DialogueAnalyzer2.Role.FEMALE else DialogueAnalyzer2.Role.MALE
            val r = o.getJSONArray("ranking")
            val al = o.optJSONObject("aliases")
            val aliases = HashMap<String, String>()
            if (al != null) for (k in al.keys()) aliases[k] = al.getString(k)
            Roster(genders, (0 until r.length()).map { r.getString(it) }, aliases)
        } catch (e: Exception) {
            null
        }
    }

    /** Read every chapter of the session's book and analyse it as one text (once per book). */
    suspend fun build(context: Context, scope: String) {
        if (scope.isEmpty() || !building.add(scope)) return
        try {
            val path = BookSession.currentPath(context) ?: return
            if (File(path).name != scope) return
            val parser = EbookParser(context)
            val publication = parser.openPublication(File(path)).getOrNull() ?: return
            val texts = publication.readingOrder.mapNotNull { parser.extractText(publication, it).getOrNull() }
            BookSession.log(context, "roster: analysing ${texts.size} chapters, ${texts.sumOf { it.length }} chars")
            val result = DialogueAnalyzer2.analyze(texts.joinToString("\n"))
            val genders = JSONObject()
            for (c in result.characters) when (c.role) {
                DialogueAnalyzer2.Role.MALE -> genders.put(c.name, "M")
                DialogueAnalyzer2.Role.FEMALE -> genders.put(c.name, "F")
                else -> {}
            }
            val ranking = JSONArray()
            result.characters.forEach { ranking.put(it.name) }
            val aliases = JSONObject()
            result.aliases.forEach { (k, v) -> aliases.put(k, v) }
            val f = file(context, scope)
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("v", VERSION).put("genders", genders).put("ranking", ranking).put("aliases", aliases).toString())
            BookSession.log(context, "roster: ${result.characters.size} characters: " +
                result.characters.take(8).joinToString { "${it.name}(${it.lines})" } + "; одно лицо: " + result.aliases)
        } catch (e: Exception) {
            BookSession.log(context, "roster failed: ${e.message}")
        } finally {
            building.remove(scope)
        }
    }
}
