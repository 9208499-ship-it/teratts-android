package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.edit

data class RecentBook(
    val title: String,
    val path: String
)

object EbookManager {
    private const val PREFS_NAME = "EbookPrefs"
    private const val KEY_RECENT_BOOKS = "recent_books"
    private const val MAX_BOOKS = 15

    fun getRecentBooks(context: Context): List<RecentBook> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_RECENT_BOOKS, "[]") ?: "[]"
            val jsonArray = JSONArray(jsonString)
            val books = mutableListOf<RecentBook>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                books.add(RecentBook(obj.getString("title"), obj.getString("path")))
            }
            books
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun addBook(context: Context, title: String, path: String) {
        try {
            val books = getRecentBooks(context).toMutableList()
            books.removeAll { it.path == path }
            books.add(0, RecentBook(title, path))
            
            val trimmedBooks = if (books.size > MAX_BOOKS) books.take(MAX_BOOKS) else books
            
            val jsonArray = JSONArray()
            trimmedBooks.forEach {
                val obj = JSONObject()
                obj.put("title", it.title)
                obj.put("path", it.path)
                jsonArray.put(obj)
            }
            
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit {
                    putString(KEY_RECENT_BOOKS, jsonArray.toString())
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private const val NAMES = "EbookNames"

    private fun saveList(context: Context, books: List<RecentBook>) {
        val jsonArray = JSONArray()
        books.forEach { jsonArray.put(JSONObject().put("title", it.title).put("path", it.path)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_RECENT_BOOKS, jsonArray.toString()) }
    }

    /** The name the reader gave the book in the library (kept when the book is opened again). */
    fun customName(context: Context, path: String): String? =
        context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).getString("title:$path", null)

    fun renameBook(context: Context, path: String, title: String) {
        val t = title.trim()
        context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).edit {
            if (t.isEmpty()) remove("title:$path") else putString("title:$path", t)
        }
        if (t.isNotEmpty()) saveList(context, getRecentBooks(context).map { if (it.path == path) RecentBook(t, it.path) else it })
    }

    /**
     * Out of the library: the entry, the app's copy of the book (only that — never a file of the
     * reader's own), its place, its characters; the player's book session if it was this book.
     */
    fun removeBook(context: Context, path: String) {
        saveList(context, getRecentBooks(context).filterNot { it.path == path })
        val file = File(path)
        try {
            val dir = File(context.filesDir, "ebooks").canonicalPath
            if (file.canonicalPath.startsWith(dir + File.separator)) file.delete()
        } catch (e: Exception) { }
        context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).edit { remove(path); remove("title:$path") }
        context.getSharedPreferences("TeraBookPositions", Context.MODE_PRIVATE).edit { remove(file.name) }
        try { BookRoster.clear(context, file.name) } catch (e: Exception) { }
        if (BookSession.currentPath(context) == path) BookSession.clear(context)
    }

    /**
     * A title as it should be shown, or null if it is unreadable. Titles from PDF and EPUB
     * metadata or from file names often come in a wrong encoding: "Ãëàâà" (windows-1251 read
     * as Latin-1) and "Ð“Ð»Ð°Ð²Ð°" (UTF-8 read as Latin-1) are decoded again; "����" (the
     * letters already lost) cannot be, and is no title.
     */
    fun readableTitle(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.count { it == '\uFFFD' } > 0) return null
        val high = s.count { it.code in 0xC0..0xFF }
        if (s.all { it.code <= 0xFF } && high >= 2 && high * 3 >= s.count { it.isLetter() }) {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            for (cs in listOf("UTF-8", "windows-1251")) {
                try {
                    val dec = java.nio.charset.Charset.forName(cs).newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    if (dec.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' } * 2 >= dec.count { it.isLetter() }) return dec.trim()
                } catch (e: Exception) { }
            }
        }
        // mostly not letters or digits ("_-_�"): no title
        if (s.count { it.isLetterOrDigit() } * 2 < s.count { !it.isWhitespace() }) return null
        return s
    }

    /** What the library calls the book: the reader's name for it, its own title, its file's name. */
    fun displayTitle(context: Context, book: RecentBook): String? =
        customName(context, book.path)
            ?: readableTitle(book.title.takeUnless { it.startsWith("book_") })
            ?: readableTitle(originalName(context, book.path))

    /** "Мастер и Маргарита.fb2.zip" → "Мастер и Маргарита". */
    private fun cleanName(name: String?): String? =
        name?.replace(Regex("""(?i)(\.(fb2|fb3|epub|pdf|txt))?(\.zip)?$"""), "")?.trim()?.takeIf { it.isNotEmpty() }

    /** The name the book file had on the phone (the copy inside the app is book_<hash>). */
    fun originalName(context: Context, path: String): String? =
        context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).getString(path, null)

    private fun displayNameOf(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i != -1 && c.moveToFirst()) c.getString(i)?.let { return it }
            }
        } catch (e: Exception) { }
        // some file managers give no name: the last part of the address (already decoded)
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /** What the file really is, by its content — names and types from file managers lie. */
    private fun sniff(file: File): String? {
        val head = try {
            file.inputStream().use { val b = ByteArray(2048); val n = it.read(b); String(b, 0, maxOf(n, 0), Charsets.ISO_8859_1) }
        } catch (e: Exception) { return null }
        if (head.startsWith("%PDF")) return "pdf"
        if (head.contains("<FictionBook")) return "fb2"
        if (head.startsWith("PK")) {
            if (head.contains("application/epub+zip")) return "epub"
            val names = try { BookConverter.zipEntryNames(file) } catch (e: Exception) { return null }
            return when {
                names.any { it.lowercase().endsWith(".fb2") } -> "fb2.zip"
                names.any { it.lowercase().endsWith("body.xml") } -> "fb3"
                names.any { it == "META-INF/container.xml" } -> "epub"
                else -> null
            }
        }
        return null
    }

    fun importBook(context: Context, uri: Uri): String? {
        val name = cleanName(displayNameOf(context, uri))
        val path = importCopy(context, uri, name) ?: return null
        if (name != null) context.getSharedPreferences(NAMES, Context.MODE_PRIVATE).edit { putString(path, name) }
        return path
    }

    private fun importCopy(context: Context, uri: Uri, niceName: String?): String? {
        try {
            val contentResolver = context.contentResolver
            
            // Try to get filename from content resolver
            var displayName: String? = null
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            if (displayName == null) displayName = displayNameOf(context, uri)

            // Try to get correct extension
            val mimeType = contentResolver.getType(uri)
            val uriString = uri.toString().lowercase()
            val lowerName = displayName?.lowercase() ?: ""
            val byName = when {
                mimeType == "application/pdf" -> "pdf"
                mimeType == "application/epub+zip" -> "epub"
                lowerName.endsWith(".pdf") -> "pdf"
                lowerName.endsWith(".epub") -> "epub"
                lowerName.endsWith(".fb2.zip") -> "fb2.zip"
                lowerName.endsWith(".fb2") -> "fb2"
                lowerName.endsWith(".fb3") -> "fb3"
                lowerName.endsWith(".txt") -> "txt"
                lowerName.endsWith(".zip") -> "zip"
                uriString.endsWith(".pdf") || uriString.contains(".pdf?") -> "pdf"
                uriString.endsWith(".epub") || uriString.contains(".epub?") -> "epub"
                mimeType == "text/plain" -> "txt"
                mimeType != null -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "epub"
                else -> "epub"
            }

            // Name the copy after its content: the same book opened again is the same file,
            // so its reading place, characters and voices are kept (as reading apps do).
            val tmp = File(context.filesDir, "ebooks/import_${System.currentTimeMillis()}.$byName")
            tmp.parentFile?.mkdirs()
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            } ?: return null
            // the content decides (an .fb2 shown as "bin" or without a name still opens)
            val extension = sniff(tmp) ?: byName
            val stamp = digest.digest().take(8).joinToString("") { "%02x".format(it) }
            File(context.filesDir, "ebooks/book_$stamp.$extension").takeIf { it.exists() }
                ?.let { tmp.delete(); return it.absolutePath }
            // converted before covers were kept: convert it again (same file — places and characters stay)
            File(context.filesDir, "ebooks/book_$stamp.epub").takeIf { it.exists() && !BookConverter.outdated(it) }
                ?.let { tmp.delete(); return it.absolutePath }
            val destFile = File(context.filesDir, "ebooks/book_$stamp.$extension")
            if (!tmp.renameTo(destFile)) { tmp.copyTo(destFile, overwrite = true); tmp.delete() }

            // FB2 / FB3 / TXT: convert to EPUB so the Readium reader can open it.
            val header = destFile.inputStream().use { val b = ByteArray(512); val n = it.read(b); b.copyOf(maxOf(n, 0)) }
            val format = when (extension) {
                "pdf", "epub" -> null
                "fb2" -> "fb2"
                "fb2.zip" -> "fb2zip"
                "fb3" -> "fb3"
                "txt" -> "txt"
                else -> BookConverter.detectFormat(displayName ?: destFile.name, header)
            }
            if (format != null) {
                val epub = File(context.filesDir, "ebooks/book_$stamp.epub")
                try {
                    BookConverter.convert(destFile, format, epub, niceName)
                    destFile.delete()
                    return epub.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                    epub.delete()
                    destFile.delete()
                    return null
                }
            }

            return destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
