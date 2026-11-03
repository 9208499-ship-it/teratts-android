package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Background music under the reading in the app's own player (the idea and the way it plays —
 * a separate looping MediaPlayer, no audio focus, no media session of its own — after MyTTS,
 * github.com/davnozdu/supertonic-android, GPL-3.0).
 *
 * Tracks: every MP3 in the app's assets/music/ (put a file there and it is in the list), the
 * user's own file (copied into the app), or the user's own list: a folder (its songs, subfolders
 * too, by name) or several files — played one after another, round and round. The music plays while the book is read and stops
 * with pause and stop; it is also mixed into saved audio files if the user says so on saving.
 */
object BackgroundMusic {
    private const val TAG = "BackgroundMusic"
    private const val PREFS = "SupertonicPrefs"
    private const val KEY_ON = "music_on"
    private const val KEY_VOLUME = "music_volume"
    private const val KEY_TRACK = "music_track"
    private const val KEY_OWN_NAME = "music_own_name"
    private const val KEY_EXPORT = "music_export"
    const val DEFAULT_VOLUME = 15
    const val OWN = "own"
    /** The user's own list: a folder or several files (read where they lie, not copied). */
    const val LIST = "list"
    private const val KEY_FOLDER = "music_folder"
    private const val KEY_FILES = "music_files"
    private const val KEY_LIST_NAME = "music_list_name"
    private const val ASSETS = "music"
    private const val ASSET = "asset:"

    class Track(val id: String, val title: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ON, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ON, on).apply()

    /** 0…100 (% of full loudness; the voice is at 100). */
    fun volume(c: Context) = prefs(c).getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
    fun setVolume(c: Context, v: Int) { prefs(c).edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply(); applyVolume(c) }

    /** The last answer to "mix the music into the file?" — offered as the default next time. */
    fun exportChoice(c: Context) = prefs(c).getBoolean(KEY_EXPORT, false)
    fun setExportChoice(c: Context, mix: Boolean) = prefs(c).edit().putBoolean(KEY_EXPORT, mix).apply()

    private fun ownFile(c: Context) = File(c.filesDir, "background_music/own")

    /** The tracks to choose from: the bundled ones, then the user's own file if there is one. */
    fun tracks(c: Context): List<Track> {
        val out = ArrayList<Track>()
        try {
            for (name in (c.assets.list(ASSETS) ?: emptyArray()).sorted()) {
                if (name.lowercase().endsWith(".mp3") || name.lowercase().endsWith(".m4a") || name.lowercase().endsWith(".ogg"))
                    out.add(Track(ASSET + name, name.substringBeforeLast('.').replace('_', ' ')))
            }
        } catch (_: Exception) { }
        if (ownFile(c).isFile) out.add(Track(OWN, prefs(c).getString(KEY_OWN_NAME, null) ?: "MP3"))
        val listName = prefs(c).getString(KEY_LIST_NAME, null) ?: ""
        when {
            prefs(c).getString(KEY_FOLDER, null) != null ->
                out.add(Track(LIST, c.getString(com.brahmadeo.supertonic.tts.R.string.music_folder, listName)))
            prefs(c).getString(KEY_FILES, null) != null ->
                out.add(Track(LIST, c.getString(com.brahmadeo.supertonic.tts.R.string.music_files, listName)))
        }
        return out
    }

    // ---- the user's own list: a folder or several files ----

    private fun isAudio(name: String, mime: String): Boolean {
        val n = name.lowercase()
        if (n.endsWith(".m3u") || n.endsWith(".m3u8") || n.endsWith(".pls") || mime.contains("mpegurl") || mime.contains("scpls")) return false
        return mime.startsWith("audio/") || listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma", ".amr", ".mka")
            .any { n.endsWith(it) }
    }

    private fun displayName(c: Context, uri: Uri): String? = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    /** The songs of a folder chosen by the user (and of its subfolders, two levels down), by name. */
    private fun folderItems(c: Context, tree: Uri): List<Uri> {
        val out = ArrayList<Pair<String, Uri>>()
        fun walk(id: String, path: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            c.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val cid = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: ""
                    val mime = cur.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(cid, "$path$name/", depth + 1)
                    } else if (isAudio(name, mime)) {
                        out.add((path + name).lowercase() to DocumentsContract.buildDocumentUriUsingTree(tree, cid))
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out.sortedBy { it.first }.map { it.second }
    }

    /** The songs of the user's list, in the order they play. */
    fun listUris(c: Context): List<Uri> {
        prefs(c).getString(KEY_FOLDER, null)?.let { f ->
            return try { folderItems(c, Uri.parse(f)) } catch (e: Exception) { Log.w(TAG, "folder: ${e.message}"); emptyList() }
        }
        return prefs(c).getString(KEY_FILES, null)?.split('\n')?.filter { it.isNotBlank() }?.map { Uri.parse(it) } ?: emptyList()
    }

    /** Lets go of the access to the previous list (Android keeps only so many). */
    private fun forgetList(c: Context) {
        val p = prefs(c)
        val old = listOfNotNull(p.getString(KEY_FOLDER, null)) + (p.getString(KEY_FILES, null)?.split('\n') ?: emptyList())
        for (u in old) if (u.isNotBlank()) try {
            c.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    /** A folder chosen by the user: its songs play one after another. Returns the list's title; throws if no music. */
    fun importFolder(c: Context, tree: Uri): String {
        try { c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val items = try { folderItems(c, tree) } catch (_: Exception) { emptyList() }
        require(items.isNotEmpty()) { c.getString(com.brahmadeo.supertonic.tts.R.string.music_folder_empty) }
        val name = try {
            displayName(c, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        } catch (_: Exception) { null } ?: "?"
        if (prefs(c).getString(KEY_FOLDER, null) != tree.toString()) forgetList(c)
        prefs(c).edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_FILES).putString(KEY_LIST_NAME, name)
            .putString(KEY_TRACK, LIST).putBoolean(KEY_ON, true).apply()
        reload()
        return c.getString(com.brahmadeo.supertonic.tts.R.string.music_folder, name) + " · " + items.size
    }

    /** Files chosen by the user: one is copied into the app (as before), several play one after another. */
    fun importFiles(c: Context, uris: List<Uri>): String {
        if (uris.size == 1) return importOwn(c, uris[0])
        val ok = uris.filter { u -> isAudio(displayName(c, u) ?: "", c.contentResolver.getType(u) ?: "") }
        require(ok.isNotEmpty()) { c.getString(com.brahmadeo.supertonic.tts.R.string.music_files_none) }
        forgetList(c)
        for (u in ok) try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        prefs(c).edit().putString(KEY_FILES, ok.joinToString("\n")).remove(KEY_FOLDER).putString(KEY_LIST_NAME, ok.size.toString())
            .putString(KEY_TRACK, LIST).putBoolean(KEY_ON, true).apply()
        reload()
        return c.getString(com.brahmadeo.supertonic.tts.R.string.music_files, ok.size.toString())
    }

    /** Where a saved file's music comes from: the track, or every song of the list in turn. */
    fun sources(c: Context, t: Track): List<(MediaExtractor) -> Unit> =
        if (t.id == LIST) listUris(c).map { u -> { ex: MediaExtractor -> ex.setDataSource(c, u, null) } }
        else listOf { ex: MediaExtractor -> setSource(c, t, ex) }

    /** The chosen track — or the first one there is (a chosen track that is gone falls back). */
    fun track(c: Context): Track? {
        val all = tracks(c)
        val id = prefs(c).getString(KEY_TRACK, null)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun setTrack(c: Context, id: String) { prefs(c).edit().putString(KEY_TRACK, id).apply(); reload() }

    /** Music on and something to play: then saving asks whether to mix it in. */
    fun ready(c: Context) = enabled(c) && track(c) != null

    /** Points [ex] at the track (bundled asset or own file). */
    fun setSource(c: Context, t: Track, ex: MediaExtractor) {
        if (t.id.startsWith(ASSET)) c.assets.openFd("$ASSETS/" + t.id.removePrefix(ASSET)).use { fd ->
            ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        } else ex.setDataSource(ownFile(c).absolutePath)
    }

    private fun setSource(c: Context, t: Track, mp: MediaPlayer) {
        if (t.id.startsWith(ASSET)) c.assets.openFd("$ASSETS/" + t.id.removePrefix(ASSET)).use { fd ->
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        } else mp.setDataSource(ownFile(c).absolutePath)
    }

    /** Copies the user's file into the app and chooses it. Returns its name; throws if it is no audio. */
    fun importOwn(c: Context, uri: Uri): String {
        val name = try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: "MP3"
        val dir = ownFile(c).parentFile!!.apply { mkdirs() }
        val tmp = File(dir, "own.part")
        (c.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("файл недоступен")).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(tmp.absolutePath)
            require((0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) {
                "это не звуковой файл"
            }
        } catch (e: Exception) {
            tmp.delete(); throw IllegalArgumentException(e.message ?: "это не звуковой файл")
        } finally { ex.release() }
        if (!tmp.renameTo(ownFile(c))) { tmp.copyTo(ownFile(c), overwrite = true); tmp.delete() }
        val title = name.substringBeforeLast('.')
        prefs(c).edit().putString(KEY_OWN_NAME, title).putString(KEY_TRACK, OWN).putBoolean(KEY_ON, true).apply()
        reload()
        return title
    }

    // ------------------------------------------------------------------ playing

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playerTrack: String? = null
    private var prepared = false
    private var wanted = false
    private var failed: String? = null
    private var position = 0
    private var listIdx = 0                         // the song of the list now
    private var listCache: List<Uri>? = null
    private var skips = 0                           // songs that would not play, one after another
    private var appContext: Context? = null
    private val idleRelease = Runnable { if (!wanted) release() }

    private fun factor(c: Context) = volume(c) / 100f

    /**
     * Called by the player service a few times a second: [reading] — the book is being read
     * (not paused, not stopped). Starts, pauses or lets go of the music to match.
     */
    fun follow(c: Context, reading: Boolean) = main.post { followNow(c.applicationContext, reading) }

    private fun followNow(c: Context, reading: Boolean) {
        appContext = c
        val t = if (reading && enabled(c)) track(c) else null
        wanted = t != null
        if (t == null) {
            if (prepared) try { if (player?.isPlaying == true) player?.pause() } catch (_: Exception) { }
            // an idle player is let go after two minutes (its place in the track is kept)
            main.removeCallbacks(idleRelease)
            if (player != null) main.postDelayed(idleRelease, 120_000)
            return
        }
        main.removeCallbacks(idleRelease)
        if (failed == t.id) return
        // the user's list: one song after another, round and round
        val list = if (t.id == LIST) (listCache ?: listUris(c).also { listCache = it }) else null
        if (list != null && list.isEmpty()) { fail(t.id, Exception("no songs")); return }
        val key = if (list != null) "$LIST#${listIdx % list.size}" else t.id
        if (player != null && playerTrack != key) release(keepPlace = false)
        val p = player
        if (p != null) {
            if (prepared) try { p.setVolume(factor(c), factor(c)); if (!p.isPlaying) p.start() } catch (e: Exception) { fail(t.id, e) }
            return
        }
        // a song of the list that will not play: the next one (the list fails only if none plays)
        fun broken(e: Exception) {
            if (list != null && ++skips < list.size) { Log.w(TAG, "song skipped: ${e.message}"); nextSong(c) }
            else fail(t.id, e)
        }
        try {
            val mp = MediaPlayer()
            player = mp; playerTrack = key; prepared = false
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.isLooping = list == null
            if (list != null) mp.setOnCompletionListener { if (player === mp) nextSong(c) }
            mp.setOnErrorListener { _, what, extra -> if (player === mp) broken(Exception("error $what/$extra")); true }
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                skips = 0
                try {
                    if (position > 0 && mp.duration > 0) mp.seekTo(position % mp.duration)
                    mp.setVolume(factor(c), factor(c))
                    if (wanted) mp.start()
                } catch (e: Exception) { broken(e) }
            }
            if (list != null) mp.setDataSource(c, list[listIdx % list.size]) else setSource(c, t, mp)
            mp.prepareAsync()
        } catch (e: Exception) { broken(e) }
    }

    /** The song is over (or would not play): the next one of the list, from its start. */
    private fun nextSong(c: Context) {
        listIdx++
        release(keepPlace = false)
        if (wanted) followNow(c, true)
    }

    private fun applyVolume(c: Context) = main.post {
        if (prepared) try { player?.setVolume(factor(c), factor(c)) } catch (_: Exception) { }
    }

    /** Another track chosen: the next [follow] opens it. */
    private fun reload() = main.post { failed = null; listCache = null; listIdx = 0; skips = 0; release(keepPlace = false) }

    private fun fail(id: String, e: Exception) {
        Log.w(TAG, "music not playable: ${e.message}")
        failed = id
        release(keepPlace = false)
    }

    fun release(keepPlace: Boolean = true) {
        val p = player ?: return
        if (keepPlace && prepared) position = try { p.currentPosition } catch (_: Exception) { 0 } else if (!keepPlace) position = 0
        try { p.release() } catch (_: Exception) { }
        player = null; playerTrack = null; prepared = false
    }
}
