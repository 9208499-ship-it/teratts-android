package com.brahmadeo.supertonic.tts.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.brahmadeo.supertonic.tts.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import java.io.File

/**
 * «Сохранить исправленную книгу» (owner, 11.2026): the whole book's text with the obvious mistakes put
 * right (WordFixer, as the screen and the voice get it) into Download/TeraTTS as a .txt, and beside it
 * the list of every fix with the words about it, to be looked through.
 */
object BookFixExport {
    class Saved(val fixes: Int, val folder: String, val bookName: String)

    private const val PDF_CHUNK = 20

    suspend fun save(
        context: Context, file: File, publication: Publication, title: String, isPdf: Boolean,
        progress: (Int, Int) -> Unit
    ): Result<Saved> = withContext(Dispatchers.IO) {
        try {
            val parser = EbookParser(context)
            val book = StringBuilder()
            val list = StringBuilder()
            var total = 0
            fun part(name: String, text: String, fixes: List<WordFixer.Fix>) {
                val t = text.trim()
                if (t.isEmpty()) return
                if (book.isNotEmpty()) book.append("\n\n\n")
                book.append(t)
                if (fixes.isEmpty()) return
                total += fixes.size
                list.append("\n— ").append(name).append(" —\n")
                for (f in fixes) list.append("«").append(f.from.replace('\n', ' ')).append("» → «").append(f.to)
                    .append("»: ").append(f.around).append('\n')
            }

            if (isPdf) {
                val pages = publication.positions().size
                var a = 0
                while (a < pages) {
                    val b = minOf(a + PDF_CHUNK, pages)
                    progress(a, pages)
                    val fixes = ArrayList<WordFixer.Fix>()
                    val text = parser.extractPages(file, publication, (a until b).toList(), fixes).getOrNull()
                    if (text != null) part(context.getString(R.string.book_fix_pages, a + 1, b), text, fixes)
                    a = b
                }
            } else {
                val order = publication.readingOrder
                val titles = titles(publication)
                for (i in order.indices) {
                    progress(i, order.size)
                    val fixes = ArrayList<WordFixer.Fix>()
                    val text = parser.extractText(publication, order[i], fixes).getOrNull() ?: continue
                    val name = titles[i] ?: order[i].title?.trim()?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.outline_chapter_n, i + 1)
                    part(name, text, fixes)
                }
            }
            if (book.isEmpty()) return@withContext Result.failure(Exception("no text"))

            val base = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(80).ifEmpty { "book" }
            val bookName = "$base — ${context.getString(R.string.book_fix_suffix_book)}.txt"
            val listName = "$base — ${context.getString(R.string.book_fix_suffix_list)}.txt"
            val header = context.getString(R.string.book_fix_list_header, title, total) + "\n" +
                context.getString(R.string.book_fix_list_note) + "\n"
            val listText = header + (if (total == 0) "\n" + context.getString(R.string.book_fix_list_none) + "\n" else list.toString())
            val folder = write(context, bookName, book.toString())
            write(context, listName, listText)
            Result.success(Saved(total, folder, bookName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The book's contents titles by their place in the reading order (the first entry for a file). */
    private fun titles(publication: Publication): Map<Int, String> {
        val order = publication.readingOrder
        fun same(a: String, b: String) = a == b || a.endsWith("/$b") || b.endsWith("/$a") ||
            a.substringAfterLast('/') == b.substringAfterLast('/')
        val titles = HashMap<Int, String>()
        fun walk(links: List<org.readium.r2.shared.publication.Link>) {
            for (l in links) {
                val t = l.title?.replace(Regex("\\s+"), " ")?.trim()
                if (!t.isNullOrEmpty()) {
                    val p = l.url().toString().substringBefore('#')
                    val i = order.indexOfFirst { same(it.url().toString().substringBefore('#'), p) }
                    if (i >= 0 && i !in titles) titles[i] = t
                }
                walk(l.children)
            }
        }
        try { walk(publication.tableOfContents) } catch (e: Exception) { }
        return titles
    }

    /** [name] into Download/TeraTTS (the app's own Download folder before Android 10); the folder's name. */
    private fun write(context: Context, name: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val rel = "${Environment.DIRECTORY_DOWNLOADS}/TeraTTS"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return rel
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "TeraTTS")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return dir.absolutePath
    }
}
