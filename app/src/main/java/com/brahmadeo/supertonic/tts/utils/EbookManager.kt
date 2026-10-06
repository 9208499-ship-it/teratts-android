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

    fun importBook(context: Context, uri: Uri): String? {
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

            // Try to get correct extension
            val mimeType = contentResolver.getType(uri)
            val uriString = uri.toString().lowercase()
            val lowerName = displayName?.lowercase() ?: ""
            val extension = when {
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
            val tmp = File(context.filesDir, "ebooks/import_${System.currentTimeMillis()}.$extension")
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
            val stamp = digest.digest().take(8).joinToString("") { "%02x".format(it) }
            File(context.filesDir, "ebooks/book_$stamp.$extension").takeIf { it.exists() }
                ?.let { tmp.delete(); return it.absolutePath }
            File(context.filesDir, "ebooks/book_$stamp.epub").takeIf { it.exists() }
                ?.let { tmp.delete(); return it.absolutePath }
            val destFile = File(context.filesDir, "ebooks/book_$stamp.$extension")
            if (!tmp.renameTo(destFile)) { tmp.copyTo(destFile, overwrite = true); tmp.delete() }

            // FB2 / FB3 / TXT: convert to EPUB so the Readium reader can open it.
            val header = destFile.inputStream().use { val b = ByteArray(512); val n = it.read(b); b.copyOf(maxOf(n, 0)) }
            val format = if (extension == "pdf" || extension == "epub") null
                         else BookConverter.detectFormat(displayName ?: destFile.name, header)
            if (format != null) {
                val epub = File(context.filesDir, "ebooks/book_$stamp.epub")
                try {
                    BookConverter.convert(destFile, format, epub)
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
