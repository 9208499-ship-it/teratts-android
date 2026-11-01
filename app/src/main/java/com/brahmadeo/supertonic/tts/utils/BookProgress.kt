package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.cover
import java.io.File

/**
 * How far into a whole book a place is — by characters, as reader apps count it — and the book's
 * cover: for the player ("в книге 12,4 %") and the library. Both are worked out once per book file
 * (opening a book takes a moment) and kept.
 */
object BookProgress {
    private const val PREFS = "TeraBookProgress"
    // one book opened at a time: the library asks for several at once
    private val lock = Mutex()

    private fun key(f: File) = f.name + "#" + f.length()

    /** Characters of each chapter with real text (reading-order index → length), if known already. */
    fun cachedLengths(context: Context, path: String): Map<Int, Int>? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(File(path)), null) ?: return null
        return try {
            s.split(',').filter { it.contains(':') }.associate { p -> p.substringBefore(':').toInt() to p.substringAfter(':').toInt() }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    /** As [cachedLengths], worked out (the book opened, every chapter's text taken) if not known yet. */
    suspend fun lengths(context: Context, path: String): Map<Int, Int>? = withContext(Dispatchers.IO) {
        cachedLengths(context, path)?.let { return@withContext it }
        lock.withLock {
            cachedLengths(context, path)?.let { return@withLock it }
            val f = File(path)
            if (!f.exists()) return@withLock null
            val parser = EbookParser(context)
            val pub = parser.openPublication(f).getOrNull() ?: return@withLock null
            try {
                val m = LinkedHashMap<Int, Int>()
                for ((i, link) in pub.readingOrder.withIndex()) {
                    val t = parser.extractText(pub, link).getOrNull()?.trim() ?: continue
                    if (t.length >= BookSession.MIN_CHAPTER_CHARS) m[i] = t.length
                }
                if (m.isEmpty()) return@withLock null
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key(f), m.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
                m
            } catch (e: Throwable) {
                Log.w("TeraBook", "lengths: ${e.message}"); null
            } finally {
                try { pub.close() } catch (_: Throwable) { }
            }
        }
    }

    /** Share 0..1 of the book read: chapter [chapter] (reading order) at [progression] (0..1) in it. */
    fun share(lens: Map<Int, Int>, chapter: Int, progression: Float): Float? {
        val total = lens.values.sum().takeIf { it > 0 } ?: return null
        val before = lens.filterKeys { it < chapter }.values.sum()
        val cur = lens[chapter] ?: 0
        return ((before + progression.coerceIn(0f, 1f) * cur) / total).coerceIn(0f, 1f)
    }

    /** The chapter's number among those with text, and how many there are: 3 to 37. */
    fun chapterNumber(lens: Map<Int, Int>, chapter: Int): Pair<Int, Int>? {
        val keys = lens.keys.sorted()
        val k = keys.indexOf(chapter)
        return if (k < 0) null else (k + 1) to keys.size
    }

    /** The share of the book read at the place saved for it (library), if its chapters are known. */
    fun savedShare(context: Context, path: String): Float? {
        val pos = BookPositions.get(context, File(path).name) ?: return null
        val lens = cachedLengths(context, path) ?: return null
        return share(lens, pos.chapter, pos.progression)
    }

    /** The cover, small (kept as a JPEG in the app's folder), or null if the book has none. */
    suspend fun cover(context: Context, path: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(path)
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val base = Integer.toHexString(key(f).hashCode())
        val jpg = File(dir, "$base.jpg")
        val none = File(dir, "$base.none")
        if (jpg.isFile) return@withContext BitmapFactory.decodeFile(jpg.path)
        if (none.exists() || !f.exists()) return@withContext null
        lock.withLock {
            if (jpg.isFile) return@withLock BitmapFactory.decodeFile(jpg.path)
            val pub = EbookParser(context).openPublication(f).getOrNull() ?: return@withLock null
            try {
                val b = pub.cover()
                if (b == null) { none.createNewFile(); return@withLock null }
                val scale = minOf(1f, 360f / maxOf(b.width, b.height).coerceAtLeast(1))
                val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1),
                    (b.height * scale).toInt().coerceAtLeast(1), true) else b
                jpg.outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                s
            } catch (e: Throwable) {
                Log.w("TeraBook", "cover: ${e.message}"); none.createNewFile(); null
            } finally {
                try { pub.close() } catch (_: Throwable) { }
            }
        }
    }
}
