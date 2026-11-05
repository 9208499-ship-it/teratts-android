package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * A folder the user chose (Download, the card…) looked through when the library opens: books in it
 * that are not in the library yet are offered to be added (dullish, 4PDA). The folder is opened through
 * Android's own folder picker, once — the app gets the right to read it, nothing more.
 */
object BookFolder {
    private const val TAG = "TeraBookFolder"
    private const val PREFS = "TeraBookFolder"
    private const val KEY_URI = "tree"
    private const val KEY_SEEN = "seen"
    /** Book files only: not PDF and TXT (Download is full of them that are not books). */
    private val EXT = listOf(".epub", ".fb2", ".fb2.zip", ".fb3")

    class Found(val uri: Uri, val name: String, val size: Long) {
        val key get() = "$name|$size"
    }

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(c: Context): Uri? = p(c).getString(KEY_URI, null)?.let { Uri.parse(it) }

    /** A short name of the folder to show ("Download", "Книги"). */
    fun label(c: Context): String? = folder(c)?.let { u ->
        try { DocumentsContract.getTreeDocumentId(u).substringAfterLast(':').substringAfterLast('/').ifEmpty { "/" } } catch (e: Exception) { null }
    }

    fun set(c: Context, tree: Uri) {
        try {
            c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { Log.w(TAG, "permission: ${e.message}") }
        folder(c)?.takeIf { it != tree }?.let { old ->
            try { c.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        }
        p(c).edit().putString(KEY_URI, tree.toString()).apply()
    }

    fun clear(c: Context) {
        folder(c)?.let { old ->
            try { c.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        }
        p(c).edit().remove(KEY_URI).apply()
    }

    /** Offered once: added or turned down, it is not offered again. */
    fun markSeen(c: Context, found: List<Found>) {
        val seen = HashSet(p(c).getStringSet(KEY_SEEN, emptySet()) ?: emptySet())
        seen.addAll(found.map { it.key })
        p(c).edit().putStringSet(KEY_SEEN, seen).apply()
    }

    /** Books in the folder (and its folders, two levels down) not in the library and not offered before. */
    fun scan(c: Context): List<Found> {
        val tree = folder(c) ?: return emptyList()
        val seen = p(c).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        val have = EbookManager.getRecentBooks(c).mapNotNull { EbookManager.originalName(c, it.path)?.lowercase() }.toHashSet()
        val out = ArrayList<Found>()
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
            c.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2 && !name.startsWith(".")) walk(id, depth + 1)
                        continue
                    }
                    val low = name.lowercase()
                    if (EXT.none { low.endsWith(it) }) continue
                    val f = Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cur.getLong(3))
                    val bare = low.replace(Regex("""(\.(fb2|fb3|epub))?(\.zip)?$"""), "").trim()
                    if (f.key in seen || bare in have) continue
                    out.add(f)
                }
            }
        }
        return try {
            walk(DocumentsContract.getTreeDocumentId(tree), 0)
            out.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            // the right to read it is gone (the card taken out, the folder deleted)
            Log.w(TAG, "scan: ${e.message}"); emptyList()
        }
    }
}
