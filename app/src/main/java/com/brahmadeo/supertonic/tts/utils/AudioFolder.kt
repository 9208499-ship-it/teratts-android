package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * The user's own folder for saved audiobooks (any storage: the phone's memory, the memory card),
 * chosen with Android's folder picker — instead of Music/Supertonic Audio. A book being saved is
 * written into a hidden folder «.Название книги» (with .nomedia in it): players do not see a book
 * half made (Lecron, dullish, 4PDA), file managers and space counters show it as it is. When the last
 * chapter is done the folder is renamed «Название книги» — at once, nothing is copied — and a player
 * finds the whole book.
 */
object AudioFolder {
    private const val TAG = "TeraAudioFolder"
    private const val PREFS = "TeraAudioFolder"
    private const val KEY = "tree"
    private const val NOMEDIA = ".nomedia"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tree(c: Context): Uri? = p(c).getString(KEY, null)?.let { Uri.parse(it) }

    /** A short name of the folder to show. */
    fun label(c: Context): String? = tree(c)?.let { u ->
        try {
            val id = DocumentsContract.getTreeDocumentId(u)
            val vol = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            (if (vol.equals("primary", true)) "" else "$vol:") + path.ifEmpty { "/" }
        } catch (e: Exception) { null }
    }

    fun set(c: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { c.contentResolver.takePersistableUriPermission(uri, flags) } catch (e: Exception) { Log.w(TAG, "permission: ${e.message}") }
        tree(c)?.takeIf { it != uri }?.let { old -> try { c.contentResolver.releasePersistableUriPermission(old, flags) } catch (_: Exception) { } }
        p(c).edit().putString(KEY, uri.toString()).apply()
    }

    fun clear(c: Context) {
        tree(c)?.let { old ->
            try { c.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
        }
        p(c).edit().remove(KEY).apply()
    }

    private fun root(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** The child [name] of the folder [parent] (a document in [tree]), or null. */
    private fun child(c: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cur ->
            while (cur.moveToNext()) if (cur.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
        }
        return null
    }

    private fun children(c: Context, tree: Uri, parent: Uri): List<Pair<Uri, String>> {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val out = ArrayList<Pair<Uri, String>>()
        c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cur ->
            while (cur.moveToNext()) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)) to (cur.getString(1) ?: ""))
        }
        return out
    }

    private fun dir(c: Context, tree: Uri, name: String, create: Boolean): Uri? {
        val r = root(tree)
        return child(c, tree, r, name)
            ?: if (create) DocumentsContract.createDocument(c.contentResolver, r, DocumentsContract.Document.MIME_TYPE_DIR, name) else null
    }

    private fun mimeOf(name: String) = if (name.endsWith(".wav", true)) "audio/x-wav" else "audio/mp4"

    /** Shown to the user: "<folder>/<book>/<name>". */
    fun shown(c: Context, book: String, name: String? = null) = listOfNotNull(label(c), book, name).joinToString("/")

    /** Is [name] of [book] saved already (in the finished folder or the hidden one)? */
    fun exists(c: Context, book: String, name: String): Boolean {
        val t = tree(c) ?: return false
        return try {
            listOf(book, ".$book").any { d -> dir(c, t, d, false)?.let { child(c, t, it, name) } != null }
        } catch (e: Exception) { false }
    }

    /** Removes [name] of [book] (a chapter saved again replaces it). */
    fun delete(c: Context, book: String, name: String) {
        val t = tree(c) ?: return
        for (d in listOf(book, ".$book")) try {
            dir(c, t, d, false)?.let { child(c, t, it, name) }?.let { DocumentsContract.deleteDocument(c.contentResolver, it) }
        } catch (_: Exception) { }
    }

    /**
     * [tmp] saved as [name] in the folder of [book]: the hidden one while the whole book is being
     * saved ([hidden]), else the book's own. Returns what to show, or null (the reason in the log).
     */
    fun publish(c: Context, tmp: File, book: String, name: String, hidden: Boolean): String? {
        val t = tree(c) ?: return null
        return try {
            val d = dir(c, t, if (hidden) ".$book" else book, true) ?: throw IllegalStateException("no folder")
            if (hidden && child(c, t, d, NOMEDIA) == null)
                try { DocumentsContract.createDocument(c.contentResolver, d, "application/octet-stream", NOMEDIA) } catch (_: Exception) { }
            child(c, t, d, name)?.let { DocumentsContract.deleteDocument(c.contentResolver, it) }
            val f = DocumentsContract.createDocument(c.contentResolver, d, mimeOf(name), name) ?: throw IllegalStateException("no file")
            c.contentResolver.openOutputStream(f, "w")?.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IllegalStateException("no output stream")
            shown(c, book, name)
        } catch (e: Throwable) {
            Log.e(TAG, "cannot save $book/$name", e)
            null
        }
    }

    /**
     * The whole book is saved: «.book» becomes «book» (renamed; if «book» is there already, from
     * chapters saved one by one, the files are moved into it). True if done.
     */
    fun finish(c: Context, book: String): Boolean {
        val t = tree(c) ?: return false
        return try {
            val hidden = dir(c, t, ".$book", false) ?: return true
            child(c, t, hidden, NOMEDIA)?.let { DocumentsContract.deleteDocument(c.contentResolver, it) }
            val final = dir(c, t, book, false)
            if (final == null) {
                DocumentsContract.renameDocument(c.contentResolver, hidden, book) != null
            } else {
                for ((u, n) in children(c, t, hidden)) {
                    child(c, t, final, n)?.let { DocumentsContract.deleteDocument(c.contentResolver, it) }
                    val moved = try { DocumentsContract.moveDocument(c.contentResolver, u, hidden, final) != null } catch (e: Exception) { false }
                    if (!moved) {
                        // a storage that cannot move: copied, then removed
                        val f = DocumentsContract.createDocument(c.contentResolver, final, mimeOf(n), n) ?: continue
                        c.contentResolver.openInputStream(u)?.use { i -> c.contentResolver.openOutputStream(f, "w")?.use { o -> i.copyTo(o, 1 shl 16) } }
                        DocumentsContract.deleteDocument(c.contentResolver, u)
                    }
                }
                DocumentsContract.deleteDocument(c.contentResolver, hidden)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot finish $book", e); false
        }
    }
}
