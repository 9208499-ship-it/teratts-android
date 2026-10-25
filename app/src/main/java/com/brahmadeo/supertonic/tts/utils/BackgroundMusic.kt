package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Background music under the reading in the app's own player (the idea and the way it plays —
 * a separate looping MediaPlayer, no audio focus, no media session of its own — after MyTTS,
 * github.com/davnozdu/supertonic-android, GPL-3.0).
 *
 * Tracks: every MP3 in the app's assets/music/ (put a file there and it is in the list), or
 * the user's own file (copied into the app). The music plays while the book is read and stops
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
        return out
    }

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
        if (player != null && playerTrack != t.id) release(keepPlace = false)
        val p = player
        if (p != null) {
            if (prepared) try { p.setVolume(factor(c), factor(c)); if (!p.isPlaying) p.start() } catch (e: Exception) { fail(t.id, e) }
            return
        }
        try {
            val mp = MediaPlayer()
            player = mp; playerTrack = t.id; prepared = false
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra -> if (player === mp) fail(t.id, Exception("error $what/$extra")); true }
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                try {
                    if (position > 0 && mp.duration > 0) mp.seekTo(position % mp.duration)
                    mp.setVolume(factor(c), factor(c))
                    if (wanted) mp.start()
                } catch (e: Exception) { fail(t.id, e) }
            }
            setSource(c, t, mp)
            mp.prepareAsync()
        } catch (e: Exception) { fail(t.id, e) }
    }

    private fun applyVolume(c: Context) = main.post {
        if (prepared) try { player?.setVolume(factor(c), factor(c)) } catch (_: Exception) { }
    }

    /** Another track chosen: the next [follow] opens it. */
    private fun reload() = main.post { failed = null; release(keepPlace = false) }

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
