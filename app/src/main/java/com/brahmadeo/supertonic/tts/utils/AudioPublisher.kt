package com.brahmadeo.supertonic.tts.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * A finished audio file into the phone's Music folder ("Music/Supertonic Audio/…").
 *
 * The file is made in the app's own cache first and handed over complete. Writing straight
 * into Music with plain file paths works only on Android 11 and later: on Android 10 such a
 * write is refused (scoped storage), and up to Android 9 it needs a storage permission the app
 * did not have — "Ошибка экспорта" at once, before a single phrase. From Android 10 on the
 * file goes through MediaStore (no permission needed for the app's own files); before that,
 * by path, with WRITE_EXTERNAL_STORAGE asked for by the player.
 */
object AudioPublisher {
    private const val TAG = "TeraExport"

    private fun musicDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    /** "Supertonic Audio/Книга" — the folder of [path] inside Music (paths outside it: the app's folder). */
    fun relDirOf(path: File): String {
        val music = musicDir().absolutePath.trimEnd('/')
        val dir = path.absolutePath.trimEnd('/')
        return if (dir.startsWith("$music/")) dir.removePrefix("$music/") else "Supertonic Audio"
    }

    private fun mimeOf(name: String) = if (name.endsWith(".wav", true)) "audio/x-wav" else "audio/mp4"

    /** Is there already a file [name] in Music/[relDir] (saved before by this app)? */
    fun exists(context: Context, relDir: String, name: String): Boolean {
        val f = File(File(musicDir(), relDir), name)
        if (f.exists() && f.length() > 0) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}>0",
                arrayOf("${Environment.DIRECTORY_MUSIC}/$relDir/", name), null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) { false }
    }

    /** Removes Music/[relDir]/[name] saved before by this app (a chapter saved again replaces it). */
    fun delete(context: Context, relDir: String, name: String) {
        try { File(File(musicDir(), relDir), name).delete() } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            context.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_MUSIC}/$relDir/", name)
            )
        } catch (_: Exception) { }
    }

    /** [s] fit for a file or folder name: no slashes, colons, quotes and the like, no control characters, not too long. */
    fun safeName(s: String, max: Int = 80): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("\\s+"), " ").trim()
            .take(max).trim().trimEnd('.')

    /**
     * Copies [tmp] to Music/[relDir]/[name]; returns the path to show ("Music/Supertonic Audio/x.m4a"),
     * or null if it could not be saved (the reason goes to the log).
     */
    fun publish(context: Context, tmp: File, relDir: String, name: String): String? {
        val shown = "${Environment.DIRECTORY_MUSIC}/$relDir/$name"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$relDir")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                try {
                    resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
                        ?: throw IllegalStateException("no output stream")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                    throw e
                }
            } else {
                val dir = File(musicDir(), relDir)
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create $dir")
                val out = File(dir, name)
                tmp.copyTo(out, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
            }
            shown
        } catch (e: Throwable) {
            Log.e(TAG, "cannot save $shown", e)
            null
        }
    }
}
