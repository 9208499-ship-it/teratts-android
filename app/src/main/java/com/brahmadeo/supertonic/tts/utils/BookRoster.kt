package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Characters of the WHOLE book, worked out once in the background and cached:
 * their genders (a name with no "сказал/сказала" in this chapter may have one
 * elsewhere) and their ranking by number of lines (only the main ones get their
 * own voices). Built from the book of the current reading session.
 */
object BookRoster {
    class Roster(val genders: Map<String, DialogueAnalyzer2.Role>, val ranking: List<String>)

    private fun file(context: Context, scope: String) =
        File(context.filesDir, "rosters/" + Integer.toHexString(scope.hashCode()) + ".json")

    private val building = java.util.Collections.synchronizedSet(HashSet<String>())

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
            val g = o.getJSONObject("genders")
            val genders = HashMap<String, DialogueAnalyzer2.Role>()
            for (k in g.keys()) genders[k] = if (g.getString(k) == "F") DialogueAnalyzer2.Role.FEMALE else DialogueAnalyzer2.Role.MALE
            val r = o.getJSONArray("ranking")
            Roster(genders, (0 until r.length()).map { r.getString(it) })
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
            val f = file(context, scope)
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("genders", genders).put("ranking", ranking).toString())
            BookSession.log(context, "roster: ${result.characters.size} characters: " +
                result.characters.take(8).joinToString { "${it.name}(${it.lines})" })
        } catch (e: Exception) {
            BookSession.log(context, "roster failed: ${e.message}")
        } finally {
            building.remove(scope)
        }
    }
}
