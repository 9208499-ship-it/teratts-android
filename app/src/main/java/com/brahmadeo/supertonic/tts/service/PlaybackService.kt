@file:Suppress("DEPRECATION")
package com.brahmadeo.supertonic.tts.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import android.support.v4.media.MediaMetadataCompat
import com.brahmadeo.supertonic.tts.R
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.PlaybackPrefs
import com.brahmadeo.supertonic.tts.utils.QueueItem
import com.brahmadeo.supertonic.tts.utils.QueueManager
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import com.brahmadeo.supertonic.tts.utils.WavUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class PlaybackService : Service(), SupertonicTTS.ProgressListener, AudioManager.OnAudioFocusChangeListener {

    private val binder = object : IPlaybackService.Stub() {
        override fun synthesizeAndPlay(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
            this@PlaybackService.synthesizeAndPlay(text, lang, stylePath, speed, steps, startIndex)
        }

        override fun addToQueue(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
            this@PlaybackService.addToQueue(text, lang, stylePath, speed, steps, startIndex)
        }

        override fun play() {
            // paused: go on from the very word; nothing left to go on with: start the last text again
            if (this@PlaybackService.isSynthesizing) this@PlaybackService.play() else this@PlaybackService.playOrResume()
        }

        override fun pause() {
            this@PlaybackService.pause()
        }

        override fun stop() {
            this@PlaybackService.stopServicePlayback()
        }

        override fun isServiceActive(): Boolean {
            return this@PlaybackService.isServiceActive()
        }

        override fun setListener(listener: IPlaybackListener?) {
            this@PlaybackService.setListener(listener)
        }

        override fun removeListener(listener: IPlaybackListener?) {
            this@PlaybackService.removeListener(listener)
        }

        override fun exportAudio(text: String, lang: String, stylePath: String, speed: Float, steps: Int, outputPath: String) {
            this@PlaybackService.exportAudio(text, lang, stylePath, speed, steps, File(outputPath))
        }

        override fun setSpeed(speed: Float) {
            this@PlaybackService.liveSpeed = speed.coerceIn(0.5f, 2.5f)
        }

        override fun getCurrentIndex(): Int {
            // -1 when nothing is being read: a fresh service (the app was closed) knows no place,
            // and its 0 used to throw the player back to the start of the chapter
            return if (isPlaying || isSynthesizing) currentSentenceIndex else -1
        }
    }

    private val listeners = RemoteCallbackList<IPlaybackListener>()

    fun setListener(listener: IPlaybackListener?) {
        if (listener != null) {
            listeners.register(listener)
            try {
                listener.onStateChanged(isPlaying, audioTrack != null || isSynthesizing, isSynthesizing)
                // only while reading: an idle service's index is not the place of the text on screen
                if ((isPlaying || isSynthesizing) && currentSentenceIndex >= 0) listener.onProgress(currentSentenceIndex, -1)
            } catch (_: RemoteException) {}
        }
    }

    fun removeListener(listener: IPlaybackListener?) {
        if (listener != null) {
            listeners.unregister(listener)
        }
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    private var lastTrackRate: Int = -1
    @Volatile private var isPlaying = false
    @Volatile private var isSynthesizing = false
    /**
     * Every start and every stop takes a new number. A start that was still getting ready
     * (waiting for the previous phrase to finish, or for the next chapter to load) when
     * "pause" came, sees a newer number and does not start — it used to start anyway, so
     * the reading came back a second or two after the pause.
     */
    private val playGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var starting = false
    private val textNormalizer = TextNormalizer()
    private var resumeOnFocusGain = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    /** Engine start-up (models load in the background); reading waits for it. */
    
    private var engineReady: kotlinx.coroutines.Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val attributionContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("supertonic_playback")
        } else {
            this
        }
    }

    private var currentSentenceIndex: Int = -1

    /**
     * Buffer between the Rust inference thread (producer) and the AudioTrack
     * write loop (consumer). Capacity ~50 chunks gives the inference thread
     * up to ~5 seconds of look-ahead, which is enough to (a) hide the
     * per-sentence inference startup cost across paragraph boundaries and
     * (b) absorb temporary RTF dips (thermal throttle, garbage collection,
     * a particularly long sentence) without an underrun click.
     */
    @Volatile private var currentAudioChannel: Channel<ByteArray>? = null

    /**
     * Streaming listener installed on SupertonicTTS for the duration of a
     * synthesis job. Called from the Rust inference thread for each finished
     * audio chunk; we push the bytes into [currentAudioChannel] using
     * runBlocking so the Rust thread itself is blocked when the channel is
     * full — that's our backpressure signal. A separate coroutine drains the
     * channel into AudioTrack.
     */
    private val streamingListener = object : SupertonicTTS.ProgressListener {
        override fun onProgress(sessionId: Long, current: Int, total: Int) {
            // chunk-level progress inside a single sentence — uninteresting at the UI level
        }

        override fun onAudioChunk(sessionId: Long, data: ByteArray) {
            val ch = currentAudioChannel ?: return
            if (SupertonicTTS.isCancelled()) return
            // Block the Rust JNI thread on send instead of busy-waiting with
            // Thread.sleep(20) in a trySend loop. runBlocking parks this
            // thread until the channel has space (consumer drained a chunk
            // into AudioTrack) or the channel was closed (producer side
            // ended synthesis). Either way it costs zero CPU while waiting,
            // versus the old design that woke up 50 times per second to
            // poll. ClosedSendChannelException is the normal cancellation
            // path — caller invokes channel.close() in its finally block.
            try {
                runBlocking { ch.send(data) }
            } catch (_: ClosedSendChannelException) {
                // Producer closed the channel — synthesis cancelled, fine.
            } catch (_: InterruptedException) {
                // Rust side interrupted; let it return cleanly.
            }
        }
    }

    companion object {
        /** startIndex meaning "continue where this text was left off". */
        const val RESUME_INDEX = -1
        /** Say a phrase in a voice (the Characters window): extras "text", "voice" (style path), "speed". */
        const val ACTION_PREVIEW = "PREVIEW_VOICE"
        /** Going back this much when reading is resumed later (a minute, as audiobook players do). */
        const val RESUME_REWIND_SECONDS = 60f

        /**
         * How far to step back when coming back to a place, by how long ago it was left
         * (as audiobook apps do): a short pause — not at all, the phrase being heard is
         * replayed from its start anyway; an evening — a little; days — up to a minute.
         * A fixed minute made the place creep backwards on every short stop.
         */
        /**
         * The very first sentence of a start, if long, is cut after its first comma (40+ letters
         * in): the voice begins as soon as that short part is ready instead of after the whole
         * sentence — a few seconds sooner on a cold start. Only at a comma: it sounds natural.
         */
        fun quickStartSplit(subs: List<String>): List<String> {
            val first = subs.firstOrNull() ?: return subs
            if (first.length < 90) return subs
            val cut = Regex("[,;:]\\s").findAll(first).map { it.range.first + 1 }
                .firstOrNull { it in 40..(first.length - 30) } ?: return subs
            return listOf(first.substring(0, cut).trim(), first.substring(cut).trim()) + subs.drop(1)
        }

        fun rewindSecondsFor(savedAt: Long): Float {
            if (savedAt <= 0L) return 15f
            val minutes = (System.currentTimeMillis() - savedAt) / 60000f
            return when {
                minutes < 5f -> 0f
                minutes < 60f -> 10f
                minutes < 24f * 60f -> 20f
                else -> RESUME_REWIND_SECONDS
            }
        }

        /** The phrase about [seconds] of reading before [index] (≈14 characters a second at 1×). */
        fun rewindIndex(sentences: List<String>, index: Int, seconds: Float, speed: Float): Int {
            if (sentences.isEmpty()) return 0
            var i = index.coerceIn(0, sentences.size - 1)
            val need = seconds * 14f * speed.coerceAtLeast(0.3f)
            var chars = 0f
            while (i > 0 && chars < need) { i--; chars += sentences[i].length }
            return i
        }

        /**
         * Where to resume [text], a minute back: the book's Locator-like place (chapter +
         * quote + progression) if [text] is a chapter of the book being read, else the place
         * saved for this text. null = start from the beginning.
         */
        fun resumeIndex(context: android.content.Context, text: String, sentences: List<String>, speed: Float): Int? {
            var idx: Int? = null
            var savedAt = 0L
            val scope = com.brahmadeo.supertonic.tts.utils.BookSession.scopeFor(context, text)
            if (scope.isNotEmpty()) {
                com.brahmadeo.supertonic.tts.utils.BookPositions.get(context, scope)
                    ?.takeIf { it.chapter == com.brahmadeo.supertonic.tts.utils.BookSession.currentIndex(context) }
                    ?.let { p ->
                        val off = com.brahmadeo.supertonic.tts.utils.BookPositions.offsetIn(text, p)
                        val ranges = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(text, sentences)
                        idx = ranges.indexOfFirst { it != null && it.last >= off }.takeIf { it >= 0 }
                        savedAt = p.time
                    }
            }
            if (idx == null) {
                idx = savedPosition(context, text)
                savedAt = savedTime(context, text)
            }
            com.brahmadeo.supertonic.tts.utils.BookSession.log(context, "resume: book=${scope.isNotEmpty()} place=${idx ?: "none"} of ${sentences.size}")
            return idx?.let { rewindIndex(sentences, it, rewindSecondsFor(savedAt), speed) }
        }

        /** When the place in [text] was saved (ms), 0 if unknown. */
        fun savedTime(context: android.content.Context, text: String): Long {
            val p = context.getSharedPreferences("TeraReadingPositions", android.content.Context.MODE_PRIVATE)
            p.getLong(positionKey(text) + "_t", 0L).takeIf { it > 0L }?.let { return it }
            val prefix = p.getString("last_pos_prefix", null)
            return if (!prefix.isNullOrEmpty() && text.trim().startsWith(prefix)) p.getLong("last_pos_time", 0L) else 0L
        }

        /** The saved place in [text]: by its exact key, or by its beginning if the text changed slightly. */
        fun savedPosition(context: android.content.Context, text: String): Int? {
            val p = context.getSharedPreferences("TeraReadingPositions", android.content.Context.MODE_PRIVATE)
            p.getInt(positionKey(text), -1).takeIf { it >= 0 }?.let { return it }
            val prefix = p.getString("last_pos_prefix", null)
            return if (!prefix.isNullOrEmpty() && text.trim().startsWith(prefix)) p.getInt("last_pos_index", 0) else null
        }
        private const val POSITIONS_PREFS = "TeraReadingPositions"
        fun positionKey(text: String) = "pos_" + Integer.toHexString(text.hashCode()) + "_" + text.length

        // a new channel: the importance of an existing one cannot be changed from the app
        const val CHANNEL_ID = "tera_now_playing"
        const val NOTIFICATION_ID = 1
        const val TAG = "PlaybackService"
        // TeraTTS outputs full-scale speech; the 2.5× boost tuned for Supertonic clipped it
        const val VOLUME_BOOST_FACTOR = 1.0f
        const val AUDIO_WRITE_CHUNK_SIZE = 8192
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.load(this)
        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.load(this)
        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.load(this)
        QueueManager.initialize(this)

        audioManager = attributionContext.getSystemService(AUDIO_SERVICE) as AudioManager
        val powerManager = attributionContext.getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, SupertonicTextToSpeechService.WAKELOCK_TAG).apply {
            // Idempotent acquire/release. Without this, a rapid sequence of
            // synthesizeAndPlay() calls (e.g. queue with auto-advance, or
            // MacroDroid hammering the TTS service) acquires the lock once
            // per call but the stop path releases it only once — the lock
            // stays held until its 10-minute auto-timeout, falsely keeping
            // the CPU awake.
            setReferenceCounted(false)
        }
        
        mediaSession = MediaSessionCompat(attributionContext, "SupertonicMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // the lock screen / always-on display / headset: play also after a stop
                override fun onPlay() { this@PlaybackService.playOrResume() }
                override fun onPause() { this@PlaybackService.pause() }
                override fun onStop() { this@PlaybackService.stopPlayback() }
                override fun onSkipToNext() { this@PlaybackService.skipPhrase(1) }
                override fun onSkipToPrevious() { this@PlaybackService.skipPhrase(-1) }
            })
            isActive = true
        }

        val modelPath = File(filesDir, "${com.brahmadeo.supertonic.tts.utils.AssetManager.MODEL_VERSION}/onnx").absolutePath
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        // start the engine in the background: on the main thread it froze the app
        engineReady = serviceScope.launch(Dispatchers.IO) {
            com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
            SupertonicTTS.initialize(modelPath, libPath)
        }
        // Prewarm: synthesize a throwaway "." in the background so XNNPACK
        // JITs its kernels and ORT lays out activation buffers before the
        // user's first real request. Saves ~300-700 ms off the first audible
        // word on weak SoCs. Best-effort; idempotent (SupertonicTTS guards
        // against double-prewarming).
        serviceScope.launch(Dispatchers.IO) {
            engineReady?.join()
            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val voiceFile = prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
            val stylePath = File(filesDir,
                "${com.brahmadeo.supertonic.tts.utils.AssetManager.MODEL_VERSION}/voice_styles/$voiceFile"
            ).absolutePath
            SupertonicTTS.prewarm(stylePath)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_PLAYBACK") {
            stopPlayback()
        } else if (intent?.action == "TERA_PLAY") {
            playOrResume()
        } else if (intent?.action == "TERA_PAUSE") {
            pause()
        } else if (intent?.action == "TERA_NEXT") {
            skipPhrase(1)
        } else if (intent?.action == "TERA_PREV") {
            skipPhrase(-1)
        } else if (intent?.action == ACTION_PREVIEW) {
            previewVoice(intent.getStringExtra("text") ?: "", intent.getStringExtra("voice") ?: "",
                intent.getFloatExtra("speed", 1f))
        } else if (intent?.action == "RESET_ENGINE") {
            // reload in the background: loading the models takes seconds, and on the
            // main thread it froze the app ("не отвечает"); reading waits for engineReady
            val modelPath = File(filesDir, "${com.brahmadeo.supertonic.tts.utils.AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            val previous = engineReady
            engineReady = serviceScope.launch(Dispatchers.IO) {
                previous?.join()
                SupertonicTTS.release()
                com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
                SupertonicTTS.initialize(modelPath, libPath)
            }
        }
        return START_NOT_STICKY
    }

    fun isServiceActive(): Boolean {
        return isPlaying || isSynthesizing
    }

    fun addToQueue(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
        QueueManager.add(QueueItem(
            text = text,
            lang = lang,
            stylePath = stylePath,
            speed = speed,
            steps = steps,
            startIndex = startIndex
        ))
    }

    private var synthesisJob: Job? = null
    private var previewJob: Job? = null
    @Volatile private var sleepPhrase = -1          // sleep timer: the phrase let to finish
    private var previewTrack: AudioTrack? = null

    /**
     * A phrase in [voice] for the Characters window — on its own short track, apart
     * from the reading (which is stopped first, so the two do not talk at once).
     */
    private fun previewVoice(text: String, voice: String, speed: Float) {
        if (text.isBlank() || voice.isEmpty()) return
        previewJob?.cancel()
        try { previewTrack?.stop(); previewTrack?.release() } catch (_: Exception) {}
        previewTrack = null
        previewJob = serviceScope.launch(Dispatchers.IO) {
            if (isPlaying || isSynthesizing) {
                withContext(Dispatchers.Main) { stopServicePlayback() }
                delay(300)
                synthesisJob?.join()
            }
            engineReady?.join()
            SupertonicTTS.setCancelled(false)
            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val lang = prefs.getString("selected_lang", "ru") ?: "ru"
            val steps = prefs.getInt("diffusion_steps", 5)
            val prepared = textNormalizer.normalize(text, lang, false)
            // as loud as it will be in the book: next to the narrator (or the main voice)
            val main = prefs.getString("last_voice_path", "") ?: ""
            val ref = com.brahmadeo.supertonic.tts.utils.RolePrefs.narratorVoice(this@PlaybackService).takeIf { it.isNotEmpty() }
                ?.let { com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService, it) } ?: main
            val gain = VOLUME_BOOST_FACTOR * com.brahmadeo.supertonic.tts.utils.VoiceLoudness.factor(this@PlaybackService, voice, ref)
            val pcm = SupertonicTTS.generateAudio(prepared, lang, voice, speed.coerceIn(0.5f, 2.5f), 0f, steps,
                gain, null) ?: return@launch
            com.brahmadeo.supertonic.tts.utils.VoiceLoudness.observe(this@PlaybackService, voice, pcm, gain, SupertonicTTS.getAudioSampleRate())
            if (!isActive || pcm.isEmpty()) return@launch
            val rate = SupertonicTTS.getAudioSampleRate()
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size)
                    .build()
            } catch (e: Exception) { Log.e(TAG, "preview track", e); return@launch }
            previewTrack = track
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(pcm.size / 2 * 1000L / rate + 300)
            } catch (_: Exception) {
            } finally {
                try { track.stop(); track.release() } catch (_: Exception) {}
                if (previewTrack === track) previewTrack = null
            }
        }
    }

    // ---- what is being heard: phrase start frames in the written audio vs the playback head ----
    private val timeline = java.util.concurrent.CopyOnWriteArrayList<LongArray>()   // [phrase index, start frame]
    @Volatile private var framesWritten = 0L
    @Volatile private var headBase = 0L

    private fun notifyListenerSpoken(index: Int, fraction: Float) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try { listeners.getBroadcastItem(i).onSpokenPosition(index, fraction) } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    /** Reading speed; the slider on the playback screen changes it while reading (next sentence on). */
    @Volatile var liveSpeed: Float = 1.0f

    // ---- "now playing": the book and the phrase, for the notification, lock screen and AOD ----
    private data class PlayArgs(val text: String, val lang: String, val style: String, val steps: Int)
    @Volatile private var lastPlay: PlayArgs? = null
    @Volatile private var nowBook = ""
    @Volatile private var nowChapter = ""
    @Volatile private var nowPhrase = ""
    private var appIconBitmap: android.graphics.Bitmap? = null

    private fun appIcon(): android.graphics.Bitmap? = appIconBitmap ?: try {
        androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.toBitmap(192, 192)
    } catch (e: Exception) { null }.also { appIconBitmap = it }

    /** The book's title (as in the library) for a chapter of it; the app's name otherwise. */
    private fun bookTitleFor(text: String): String {
        val path = com.brahmadeo.supertonic.tts.utils.BookSession.currentPath(this)
        if (path != null && com.brahmadeo.supertonic.tts.utils.BookSession.scopeFor(this, text).isNotEmpty()) {
            com.brahmadeo.supertonic.tts.utils.EbookManager.getRecentBooks(this).firstOrNull { it.path == path }
                ?.title?.takeIf { it.isNotBlank() && !it.startsWith("book_") }?.let { return it }
            com.brahmadeo.supertonic.tts.utils.EbookManager.originalName(this, path)?.let { return it }
        }
        return getString(R.string.app_name)
    }

    /** The phrase being heard: the title on the lock screen / AOD card and in the notification. */
    private fun setNowPlaying(phrase: String) {
        nowPhrase = phrase.trim().replace(Regex("\\s+"), " ").take(160)
        try {
            mediaSession.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowPhrase.ifEmpty { nowBook })
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, nowPhrase.ifEmpty { nowBook })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowBook)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, nowBook)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, nowChapter)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, appIcon())
                .build())
            if (isPlaying) updateNotification(getString(R.string.notif_playing))
        } catch (e: Exception) { Log.w(TAG, "now playing", e) }
    }

    /** Play from the lock screen / notification: continue a paused reading, or start the last one again. */
    fun playOrResume() {
        if (isSynthesizing) { play(); return }
        val a = lastPlay ?: run {
            val p = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val t = p.getString("last_text", "") ?: ""
            if (t.isEmpty()) return
            PlayArgs(t, p.getString("last_lang", "ru") ?: "ru", p.getString("last_voice_path", "") ?: "", p.getInt("last_steps", 5))
        }
        synthesizeAndPlay(a.text, a.lang, a.style, liveSpeed, a.steps,
            if (lastPlay != null && currentSentenceIndex >= 0) currentSentenceIndex else RESUME_INDEX)
    }

    /** A phrase back / forward from the notification or the lock screen. */
    fun skipPhrase(d: Int) {
        val a = lastPlay ?: return
        val i = (currentSentenceIndex.coerceAtLeast(0) + d).coerceAtLeast(0)
        synthesizeAndPlay(a.text, a.lang, a.style, liveSpeed, a.steps, i)
    }

    fun synthesizeAndPlay(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        val gen = playGen.incrementAndGet()
        starting = true
        liveSpeed = speed
        lastPlay = PlayArgs(text, lang, stylePath, steps)
        nowBook = bookTitleFor(text)
        nowChapter = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: ""
        serviceScope.launch {
            // Cancel any in-flight synthesis, but keep the AudioTrack alive so the
            // next sentence can stream straight in without a re-init delay.
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            // paused / stopped (or started again) while the previous phrase was finishing
            if (gen != playGen.get()) return@launch

            val rate = SupertonicTTS.getAudioSampleRate()
            ensureAudioTrack(rate)
            // pause first: flush() is ignored on a playing track, and the frame count must start afresh
            try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
            timeline.clear()
            framesWritten = 0L
            headBase = try { (audioTrack?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL } catch (_: Exception) { 0L }

            isSynthesizing = true
            isPlaying = true
            SupertonicTTS.setCancelled(false)

            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            startForegroundService(getString(R.string.notif_synthesizing), false)
            // the player shows "pause" at once: a tap while the first phrase is being made
            // pauses it (it used to show "play", and a tap restarted the phrase)
            notifyListenerState(true)

            wakeLock?.acquire(10 * 60 * 1000L)

            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio Focus denied")
            }

            // When pre-roll is OFF (default) we kick AudioTrack into PLAYING
            // immediately so the first synthesized chunk plays as soon as it
            // arrives — same as before this feature existed. When pre-roll is
            // ON we keep AudioTrack stopped here; the consumer below starts
            // playback only after enough audio is buffered in the channel.
            // Writing PCM to AudioTrack in PAUSED state is a no-op on Android
            // (it returns immediately without queuing), so we have to hold
            // the chunks in the in-memory channel until play() is called.
            val preRollEnabled = PlaybackPrefs.preRollEnabled
            if (!preRollEnabled) {
                try {
                    if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                        audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.play()
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "AudioTrack.play() failed", e)
                }
            }

            // Auto-detect Russian when the in-app language picker disagrees
            // with the actual text content. Mirrors the same heuristic used
            // in SupertonicTextToSpeechService for system-TTS calls, so
            // pasting Russian into an "English"-selected session still routes
            // through the Russian normalisation path (numbers, accent
            // dictionary).
            val effectiveLang = autoDetectRussian(text, lang)

            val tStart = android.os.SystemClock.elapsedRealtime()
            var firstSoundLogged = false
            starting = false
            synthesisJob = launch(Dispatchers.IO) {
                engineReady?.join()
                Log.i("TeraStart", "engine ready after ${android.os.SystemClock.elapsedRealtime() - tStart} ms")
                // with the screen off the phone cuts CPU, so give it audio priority
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                } catch (_: Throwable) { }
                val sentences = textNormalizer.splitIntoSentences(text, effectiveLang)
                val totalSentences = sentences.size
                // Where reading stopped in this very text is remembered, so going to the
                // settings (another voice, roles…) and pressing play again continues
                // from the same sentence instead of the beginning.
                val positions = getSharedPreferences(POSITIONS_PREFS, MODE_PRIVATE)
                val posKey = positionKey(text)
                // resumed: the saved place, a minute back
                // a chapter of the book being read (any, not only the last one): make it current
                com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this@PlaybackService, text)
                val requestedIndex = if (startIndex == RESUME_INDEX) {
                    resumeIndex(this@PlaybackService, text, sentences, speed) ?: 0
                } else startIndex
                // the book's place (Locator-like: chapter, progression, quote) when reading a book
                val bookScope = com.brahmadeo.supertonic.tts.utils.BookSession.scopeFor(this@PlaybackService, text)
                val bookChapter = com.brahmadeo.supertonic.tts.utils.BookSession.currentIndex(this@PlaybackService)
                val chunkRanges = if (bookScope.isNotEmpty()) com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(text, sentences) else emptyList()
                fun savePosition(idx: Int) {
                    val now = System.currentTimeMillis()
                    positions.edit().putInt(posKey, idx).putLong(posKey + "_t", now)
                        .putString("last_pos_prefix", text.trim().take(160)).putInt("last_pos_index", idx)
                        .putLong("last_pos_time", now).apply()
                    chunkRanges.getOrNull(idx)?.let { r ->
                        com.brahmadeo.supertonic.tts.utils.BookPositions.save(this@PlaybackService, bookScope, bookChapter,
                            r.first.toFloat() / text.length.coerceAtLeast(1),
                            text.substring(r.first, minOf(text.length, r.first + 80)))
                    }
                }
                val validStartIndex = if (requestedIndex in 0 until totalSentences) requestedIndex else 0
                currentSentenceIndex = validStartIndex
                // a timer that ran out while nothing was read does not stop the reading just started
                if (com.brahmadeo.supertonic.tts.utils.SleepTimer.expired(this@PlaybackService)) {
                    com.brahmadeo.supertonic.tts.utils.SleepTimer.off(this@PlaybackService)
                }
                sleepPhrase = -1

                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)

                // Reading by roles: who says what, worked out once for the whole text
                val rolesOn = com.brahmadeo.supertonic.tts.utils.RolePrefs.enabled(this@PlaybackService)
                // character settings are kept per book (the book of this text, "" if none)
                val charScope = com.brahmadeo.supertonic.tts.utils.BookSession.scopeFor(this@PlaybackService, text)
                // the whole book's characters (built once in the background) and the user's corrections
                val roster = if (rolesOn) com.brahmadeo.supertonic.tts.utils.BookRoster.cached(this@PlaybackService, charScope) else null
                if (rolesOn && charScope.isNotEmpty() && roster == null) {
                    serviceScope.launch(Dispatchers.IO) { com.brahmadeo.supertonic.tts.utils.BookRoster.build(this@PlaybackService, charScope) }
                }
                val rolePrior = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Prior(
                    roster?.genders ?: emptyMap(),
                    if (rolesOn) com.brahmadeo.supertonic.tts.utils.SpeakerOverrides.all(this@PlaybackService, charScope) else emptyMap())
                val roleResult = if (rolesOn) {
                    try {
                        com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.applyAliases(com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.analyze(text, rolePrior),
                            com.brahmadeo.supertonic.tts.utils.CharacterVoices.aliases(this@PlaybackService, charScope))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Role analysis failed, reading without roles", e); null
                    }
                } else null
                val roleSpans = roleResult?.spans ?: emptyList()
                // where each spoken chunk lies in the text (robust to the splitter's spacing and gluing)
                val roleRanges = if (roleResult != null) com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(text, sentences) else emptyList()
                val maleStyle = com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService,
                    com.brahmadeo.supertonic.tts.utils.RolePrefs.maleVoice(this@PlaybackService)) ?: stylePath
                val femaleStyle = com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService,
                    com.brahmadeo.supertonic.tts.utils.RolePrefs.femaleVoice(this@PlaybackService)) ?: stylePath
                // narrator: its own voice if chosen, otherwise the main voice
                val narratorStyle = if (rolesOn) {
                    com.brahmadeo.supertonic.tts.utils.RolePrefs.narratorVoice(this@PlaybackService)
                        .takeIf { it.isNotEmpty() }
                        ?.let { com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService, it) } ?: stylePath
                } else stylePath
                // a line whose speaker is unknown is still a line: never the narrator's voice
                val unknownStyle = com.brahmadeo.supertonic.tts.utils.RolePrefs.unknownVoice(this@PlaybackService)
                    .takeIf { it.isNotEmpty() }
                    ?.let { com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService, it) } ?: maleStyle

                // Every character: their own voice (remembered across chapters) and speed
                val charVoice = HashMap<String, String>()
                val charSpeed = HashMap<String, Float>()
                if (roleResult != null) {
                    val perCharacter = com.brahmadeo.supertonic.tts.utils.RolePrefs.charactersEnabled(this@PlaybackService)
                    val available = com.brahmadeo.supertonic.tts.utils.RolePrefs.availableVoices(this@PlaybackService)
                    val narratorFile = com.brahmadeo.supertonic.tts.utils.RolePrefs.narratorVoice(this@PlaybackService)
                    // own voices only for the main characters of the book; the rest — by gender
                    val ranking = roster?.ranking ?: roleResult.characters.map { it.name }
                    val mainSet = ranking.filter { it != com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.HERO }
                        .take(com.brahmadeo.supertonic.tts.utils.RolePrefs.mainCount(this@PlaybackService)).toSet() + com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.SYSTEM
                    for (c in roleResult.characters) {
                        charSpeed[c.name] = com.brahmadeo.supertonic.tts.utils.CharacterVoices.speedOverride(this@PlaybackService, c.name, charScope) ?: c.speed
                        if (!perCharacter) continue
                        if (c.name == com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.HERO) {
                            // the hero speaks in the narrator's voice unless a voice was set for him
                            com.brahmadeo.supertonic.tts.utils.CharacterVoices.explicit(this@PlaybackService, c.name, charScope)
                                ?.let { com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService, it) }
                                ?.let { charVoice[c.name] = it }
                            continue
                        }
                        if (c.name !in mainSet && !com.brahmadeo.supertonic.tts.utils.CharacterVoices.isManual(this@PlaybackService, c.name, charScope)) continue
                        val default = if (c.role == com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.FEMALE)
                            com.brahmadeo.supertonic.tts.utils.RolePrefs.femaleVoice(this@PlaybackService) else com.brahmadeo.supertonic.tts.utils.RolePrefs.maleVoice(this@PlaybackService)
                        com.brahmadeo.supertonic.tts.utils.CharacterVoices.voiceFor(this@PlaybackService, c.name, c.role, available, default, narratorFile, charScope)
                            ?.let { com.brahmadeo.supertonic.tts.utils.RolePrefs.pathOf(this@PlaybackService, it) }
                            ?.let { charVoice[c.name] = it }
                    }
                }

                // a line nobody could be matched to: the voice chosen for unknown lines, or else
                // the book's main character (most lines) — in "Alice" that is Alice, not a man
                val speechStyle = if (com.brahmadeo.supertonic.tts.utils.RolePrefs.unknownVoice(this@PlaybackService).isNotEmpty()) unknownStyle else {
                    val main = roleResult?.let { r ->
                        (com.brahmadeo.supertonic.tts.utils.BookRoster.cached(this@PlaybackService, charScope)?.ranking ?: r.characters.map { it.name })
                            .firstOrNull { it != com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.HERO && it != com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.SYSTEM }
                    }
                    main?.let { charVoice[it] } ?: unknownStyle
                }

                // Channel sizing:
                //  pre-roll OFF: 50 chunks ~ 5 s look-ahead — enough to hide
                //                per-sentence inference startup, small enough
                //                that cancel still feels responsive (worst
                //                case: 5 s of buffered audio before silence).
                //  pre-roll ON:  ~50 s capacity. We deliberately let the
                //                producer race ahead and accumulate several
                //                sentences worth of PCM in RAM before
                //                AudioTrack starts. RAM cost: ~1-3 MB for
                //                typical voice settings, never spills to disk.
                val preRollSentences = PlaybackPrefs.preRollSentences
                val channelCapacity = if (preRollEnabled) 500 else 50
                val channel = Channel<ByteArray>(capacity = channelCapacity)
                currentAudioChannel = channel
                // Synthesis runs ahead of playback. So that the highlighted sentence
                // follows the voice, the producer puts an empty marker into the audio
                // queue before each sentence; the player reports the sentence when it
                // reaches the marker, i.e. when that sentence actually starts playing.
                val sentenceMarkers = java.util.concurrent.ConcurrentHashMap<ByteArray, Int>()
                // the same for each sentence inside a phrase: [phrase index, where the sentence starts in it]
                val subMarkers = java.util.concurrent.ConcurrentHashMap<ByteArray, LongArray>()

                // When pre-roll is enabled, the consumer waits on this signal
                // before starting AudioTrack. The producer below completes it
                // once preRollSentences sentences have finished synthesis (or
                // when the input is shorter than that target — see the
                // edge-case complete() after the producer loop).
                val preRollSignal: CompletableDeferred<Unit>? =
                    if (preRollEnabled) CompletableDeferred() else null

                var statePromotedToPlaying = false
                var sawAnyAudio = false

                // Consumer: drains the channel into AudioTrack. Runs in
                // parallel with the producer below; pause/cancel polling is
                // handled inside writeToTrackBlocking. When pre-roll is on,
                // it parks on preRollSignal until the producer has enough
                // audio queued, then calls audioTrack.play() and begins
                // streaming. This is the gate that lets the buffer fill up
                // without playback racing ahead and underrunning.
                // The phrase and the word being HEARD: the track's playback head against the frame
                // at which each phrase was written — not the moment the phrase was handed to the
                // track, which runs ahead by the whole audio buffer.
                val spokenTicker = launch(Dispatchers.Main) {
                    val rate = SupertonicTTS.getAudioSampleRate().coerceAtLeast(1)
                    var lastIndex = -1
                    while (isActive) {
                        kotlinx.coroutines.delay(120)
                        val t = audioTrack ?: continue
                        val head = try { (t.playbackHeadPosition.toLong() and 0xffffffffL) - headBase } catch (_: Exception) { continue }
                        val entries = timeline.toList()
                        val k = entries.indexOfLast { it[1] <= head }
                        if (k < 0) continue
                        val idx = entries[k][0].toInt()
                        val start = entries[k][1]
                        val subPos = entries[k].getOrNull(2)
                        val estimate = start + ((sentences.getOrNull(idx)?.length ?: 0) / (14f * liveSpeed) * rate).toLong()
                        val end = entries.getOrNull(k + 1)?.get(1)
                            ?: if (isSynthesizing) maxOf(framesWritten, estimate) else framesWritten
                        // the sentence being heard is known exactly (its marker); otherwise an estimate
                        val fraction = if (subPos != null) com.brahmadeo.supertonic.tts.utils.SpokenProgress.fractionAt(sentences.getOrNull(idx) ?: "", subPos.toInt())
                            else if (end > start) ((head - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
                        if (idx != lastIndex) {
                            lastIndex = idx
                            currentSentenceIndex = idx
                            notifyListenerProgress(idx, totalSentences)
                            savePosition(idx)
                            setNowPlaying(sentences.getOrNull(idx) ?: "")
                        }
                        notifyListenerSpoken(idx, fraction)
                        // sleep timer: the time is up — let the phrase being heard finish, then stop
                        if (com.brahmadeo.supertonic.tts.utils.SleepTimer.expired(this@PlaybackService)) {
                            if (sleepPhrase < 0) sleepPhrase = idx
                            else if (idx != sleepPhrase) {
                                sleepPhrase = -1
                                com.brahmadeo.supertonic.tts.utils.SleepTimer.off(this@PlaybackService)
                                stopServicePlayback()
                                break
                            }
                        }
                    }
                }

                val playerJob = launch(Dispatchers.IO) {
                    // Bump this thread's scheduling priority to AUDIO (-16
                    // nice). Android's audio framework gives such threads
                    // first-class treatment — they preempt regular user
                    // threads and even background GC where possible. Without
                    // this, the consumer competes with whatever else is on
                    // Dispatchers.IO and an unlucky scheduling slot can
                    // cause AudioTrack underrun (= clicking/skipping). Set
                    // once per playerJob; reverts when the coroutine exits.
                    try {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_AUDIO
                        )
                    } catch (_: Throwable) {
                        // Some OEMs deny the priority change; harmless.
                    }
                    if (preRollSignal != null) {
                        preRollSignal.await()
                        if (SupertonicTTS.isCancelled() || !isActive) return@launch
                        try {
                            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                                audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack?.play()
                            }
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "AudioTrack.play() failed (post pre-roll)", e)
                        }
                    }
                    for (data in channel) {
                        if (!isActive || SupertonicTTS.isCancelled()) break
                        if (data.isEmpty()) {
                            val sub = subMarkers.remove(data)
                            if (sub != null) { timeline.add(longArrayOf(sub[0], framesWritten, sub[1])); continue }
                            val playingIndex = sentenceMarkers.remove(data) ?: continue
                            // where this phrase starts in the written audio; spokenTicker turns it into "heard now"
                            timeline.add(longArrayOf(playingIndex.toLong(), framesWritten))
                            // the place is saved by the playback-head ticker only: this marker is written
                            // ahead of what is heard (by the whole audio buffer), and saving it here made
                            // the book resume after the place where listening actually stopped
                            continue
                        }
                        // Self-heal: we are supposed to be playing but the track was left
                        // stopped (start race, a system pause) — that left reading silent
                        // until the user pressed pause → play.
                        if (isPlaying) {
                            try {
                                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                                    audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack?.play()
                                }
                            } catch (e: IllegalStateException) {
                                Log.w(TAG, "AudioTrack.play() self-heal failed", e)
                            }
                        }
                        if (!firstSoundLogged) {
                            firstSoundLogged = true
                            Log.i("TeraStart", "first sound after ${android.os.SystemClock.elapsedRealtime() - tStart} ms")
                        }
                        writeToTrackBlocking(data)
                    }
                }

                var producedSentences = 0
                var quickStart = true      // the first phrase of this start: speak as soon as possible
                try {
                    for (index in validStartIndex until totalSentences) {
                        if (SupertonicTTS.isCancelled() || !isActive) break

                        // Honour pause without consuming CPU. Producer can pause
                        // even though consumer is still draining the buffer —
                        // the buffer fills up, sends block, all clean.
                        while (!isPlaying && isSynthesizing && isActive) {
                            delay(100)
                        }
                        if (SupertonicTTS.isCancelled() || !isActive || !isSynthesizing) break

                        // marker: "sentence <index> starts here" (see sentenceMarkers)
                        val marker = ByteArray(0)
                        sentenceMarkers[marker] = index
                        try { channel.send(marker) } catch (_: Exception) { break }

                        // A sentence may hold a line and the author words ("— Привет, — сказала она"):
                        // with roles on it is voiced piece by piece, each with its own voice.
                        val pieces = if (roleResult != null) {
                            com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.piecesIn(text, roleRanges.getOrNull(index), roleSpans, sentences[index])
                        } else {
                            listOf(com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Piece(sentences[index],
                                com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.NARRATOR, null, com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Manner.NORMAL))
                        }
                        var result: ByteArray? = null
                        val sentenceStarted = System.nanoTime()
                        // sentences of this phrase: where each starts, counted in letters (pieces are
                        // cut from the book text, the phrase from the splitter — letters match in both)
                        val chunkText = sentences[index]
                        val sentStarts = com.brahmadeo.supertonic.tts.utils.SpokenProgress.sentenceStarts(chunkText)
                        val startLetters = sentStarts.map { p -> chunkText.substring(0, p).count { it.isLetterOrDigit() } }
                        var lettersDone = 0
                        var sentenceBytes = 0L
                        for (pc in pieces) {
                            if (SupertonicTTS.isCancelled() || !isActive) break
                            // first-person books: the hero ("уточнил я") speaks in the narrator's voice
                            val pieceStyle = if (pc.speaker == com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.HERO) (charVoice[pc.speaker] ?: narratorStyle)
                            else pc.speaker?.let { charVoice[it] } ?: when (pc.role) {
                                com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.MALE -> maleStyle
                                com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.FEMALE -> femaleStyle
                                com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.SPEECH -> speechStyle
                                else -> narratorStyle
                            }
                            // character's own pace × how this line is said (выпалил / протянул)
                            val pieceSpeed = (liveSpeed * (pc.speaker?.let { charSpeed[it] } ?: 1f) *
                                com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.mannerSpeed(pc.manner)).coerceIn(0.5f, 2.5f)
                            // every voice as loud as the main one (voices differ by a lot), then how the line is said
                            val pieceGain = VOLUME_BOOST_FACTOR * com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.mannerGain(pc.manner) *
                                com.brahmadeo.supertonic.tts.utils.VoiceLoudness.factor(this@PlaybackService, pieceStyle, narratorStyle)
                            // sentence by sentence, each with its marker: the highlight follows the
                            // voice exactly, as readers do with system TTS (the engine cuts at
                            // sentence ends anyway, so this costs nothing)
                            var subs = com.brahmadeo.supertonic.tts.utils.SpokenProgress.splitSentences(pc.text)
                            if (quickStart) { subs = quickStartSplit(subs); quickStart = false }
                            for (sub in subs) {
                                if (SupertonicTTS.isCancelled() || !isActive) break
                                if (sentStarts.size > 1) {
                                    val k = startLetters.indexOfLast { it <= lettersDone + 1 }.coerceAtLeast(0)
                                    val m = ByteArray(0)
                                    subMarkers[m] = longArrayOf(index.toLong(), sentStarts[k].toLong())
                                    try { channel.send(m) } catch (_: Exception) { break }
                                }
                                lettersDone += sub.count { it.isLetterOrDigit() }
                                val normalizedText = textNormalizer.normalize(sub, effectiveLang, isAdvancedEnabled)
                                // Streaming: each finished chunk inside generateAudio is
                                // pushed via streamingListener.onAudioChunk into the
                                // channel, where the consumer above picks it up.
                                val r = SupertonicTTS.generateAudio(
                                    normalizedText, effectiveLang, pieceStyle, pieceSpeed, 0.0f, steps,
                                    pieceGain, streamingListener
                                )
                                if (r != null && r.isNotEmpty()) {
                                    result = r; sentenceBytes += r.size
                                    com.brahmadeo.supertonic.tts.utils.VoiceLoudness.observe(this@PlaybackService, pieceStyle, r, pieceGain,
                                        SupertonicTTS.getAudioSampleRate())
                                }
                            }
                        }
                        recordReaderRtf(sentenceBytes, System.nanoTime() - sentenceStarted)

                        if (result != null && result.isNotEmpty()) {
                            sawAnyAudio = true
                            producedSentences++
                            // Pre-roll: release the consumer once enough
                            // sentences are queued. Also release if input
                            // turned out to be shorter than the target — we
                            // don't want to wait forever for a 5th sentence
                            // that doesn't exist.
                            if (preRollSignal != null &&
                                !preRollSignal.isCompleted &&
                                producedSentences >= preRollSentences
                            ) {
                                preRollSignal.complete(Unit)
                            }
                            if (!statePromotedToPlaying && isPlaying) {
                                statePromotedToPlaying = true
                                withContext(Dispatchers.Main) {
                                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                    notifyListenerState(true)
                                    // from "preparing" to the player: book, phrase, ⏮ ⏯ ⏭
                                    try { updateNotification(getString(R.string.notif_playing)) } catch (_: Exception) {}
                                }
                            }
                            // Inter-sentence breath. Suspending send — never
                            // blocks the inference thread itself, just makes
                            // the producer wait if the buffer is full.
                            if (index < totalSentences - 1) {
                                try { channel.send(silenceBytes(80)) } catch (_: Exception) { break }
                            }
                        } else if (SupertonicTTS.isCancelled()) {
                            break
                        }
                    }
                } finally {
                    // Edge cases for the pre-roll gate:
                    //   - text was shorter than preRollSentences → consumer
                    //     would await forever for chunks that never come
                    //   - cancelled mid-pre-roll → consumer should unblock
                    //     and exit cleanly via the channel.close() below
                    if (preRollSignal != null && !preRollSignal.isCompleted) {
                        preRollSignal.complete(Unit)
                    }
                    channel.close()
                    currentAudioChannel = null
                }

                // Consumer drains anything left in the buffer; then AudioTrack itself drains.
                playerJob.join()
                if (sawAnyAudio) drainAudioTrack()
                spokenTicker.cancel()

                withContext(Dispatchers.Main) {
                    if (isSynthesizing && isActive) {
                        val wasCancelled = SupertonicTTS.isCancelled()
                        isSynthesizing = false
                        if (!wasCancelled) {
                            notifyListenerProgress(totalSentences, totalSentences)
                            // read to the end: next time start from the beginning
                            getSharedPreferences(POSITIONS_PREFS, MODE_PRIVATE).edit()
                                .remove(positionKey(text)).apply()
                        }
                        notifyListenerState(true)
                        if (!wasCancelled && com.brahmadeo.supertonic.tts.utils.SleepTimer.atChapterEnd(this@PlaybackService)) {
                            // sleep timer "to the end of the chapter": no next chapter, no queue
                            com.brahmadeo.supertonic.tts.utils.SleepTimer.off(this@PlaybackService)
                            stopPlayback()
                        } else if (!wasCancelled) {
                            val nextItem = QueueManager.next()
                            if (nextItem != null) {
                                SupertonicTTS.reset()
                                synthesizeAndPlay(nextItem.text, nextItem.lang, nextItem.stylePath, nextItem.speed, nextItem.steps, nextItem.startIndex)
                            } else if (run {
                                    com.brahmadeo.supertonic.tts.utils.BookSession.log(this@PlaybackService, "chapter finished; queue empty")
                                    com.brahmadeo.supertonic.tts.utils.BookSession.matches(this@PlaybackService, text)
                                }) {
                                // end of a chapter of an open book: the next chapter follows
                                continueWithNextChapter(lang, stylePath, steps)
                            } else {
                                stopPlayback()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Create the shared AudioTrack on first use, or recreate it only when the
     * sample-rate changes or the OS has invalidated it (UNINITIALIZED state).
     *
     * Crucially we do NOT release/recreate per sentence — the OEM stack on
     * Oppo/OnePlus needs ~500 ms per init, which is exactly the gap users hear
     * between paragraphs in the old design.
     */
    private fun ensureAudioTrack(rate: Int) {
        synchronized(this) {
            val existing = audioTrack
            val isHealthy = existing != null &&
                existing.state == AudioTrack.STATE_INITIALIZED &&
                lastTrackRate == rate
            if (isHealthy) return

            try { existing?.release() } catch (_: Exception) {}

            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // Bigger buffer (~1 s at 44.1 kHz mono 16-bit ≈ 88 KB) — gives the
            // synthesiser plenty of slack to absorb RTF dips without underrun.
            val bufferBytes = (minBuf * 8).coerceAtLeast(minBuf)

            val builder = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setContext(attributionContext)
            }

            try {
                audioTrack = builder.build()
                lastTrackRate = rate
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AudioTrack", e)
                audioTrack = null
            }
        }
    }

    /**
     * Push PCM into the shared AudioTrack from any thread.
     *
     * Called from the Rust JNI thread inside [streamingListener]; that thread
     * is blocked here until the AudioTrack has buffer space, which gives us
     * backpressure for free: inference can never run faster than the speaker.
     *
     * Cancel + pause are polled at the chunk granularity. Pause uses
     * Thread.sleep because this is invoked off the coroutine context.
     */
    private fun writeToTrackBlocking(data: ByteArray): Boolean {
        val t = audioTrack ?: return false
        if (t.state != AudioTrack.STATE_INITIALIZED) return false
        if (SupertonicTTS.isCancelled()) return false

        var offset = 0
        while (offset < data.size) {
            if (SupertonicTTS.isCancelled()) return false
            // Pause: hold inference thread (and therefore the JNI callback) until resumed.
            while (!isPlaying && !SupertonicTTS.isCancelled()) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
            }
            if (SupertonicTTS.isCancelled()) return false

            val toWrite = (data.size - offset).coerceAtMost(AUDIO_WRITE_CHUNK_SIZE)
            val written = try {
                t.write(data, offset, toWrite, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack write exception", e)
                return false
            }
            if (written <= 0) {
                Log.w(TAG, "AudioTrack write returned $written, aborting chunk")
                return false
            }
            offset += written
            framesWritten += written / 2   // 16-bit mono: 2 bytes per frame
        }
        return true
    }

    /**
     * Cyrillic-content override for the language code: if the text is
     * predominantly Russian letters, force the "ru" path so we get the
     * accent dictionary and number-to-words spellout even when the UI
     * picker is on something else.
     */
    private fun autoDetectRussian(text: String, declared: String): String {
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in 'Ѐ'..'ӿ' -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        return if (cyrillic > latin && cyrillic >= 4) "ru" else declared
    }

    // Cache the (rate, durationMs) -> zeroed-buffer mapping. silenceBytes is
    // called between every pair of consecutive sentences with the same
    // (80 ms, sample rate) parameters, so without caching a fresh 7-15 KB
    // ByteArray was allocated per inter-sentence gap — multiple MB of GC
    // pressure on a long book.
    @Volatile private var cachedSilenceRate: Int = -1
    @Volatile private var cachedSilenceDurationMs: Int = -1
    @Volatile private var cachedSilenceBuffer: ByteArray? = null

    /**
     * Returns a zeroed PCM-16 mono buffer of the requested duration (in ms).
     *
     * The returned buffer is shared — callers must treat it as read-only.
     * AudioTrack.write() only reads from the input array, so handing the same
     * array to the channel repeatedly is safe.
     */
    private fun silenceBytes(durationMs: Int): ByteArray {
        val rate = lastTrackRate.takeIf { it > 0 } ?: SupertonicTTS.getAudioSampleRate()
        val existing = cachedSilenceBuffer
        if (existing != null && cachedSilenceRate == rate && cachedSilenceDurationMs == durationMs) {
            return existing
        }
        val samples = (rate.toLong() * durationMs / 1000L).toInt().coerceAtLeast(0)
        val fresh = ByteArray(samples * 2)
        cachedSilenceRate = rate
        cachedSilenceDurationMs = durationMs
        cachedSilenceBuffer = fresh
        return fresh
    }

    /**
     * Wait until the AudioTrack head has consumed the data we wrote — so we
     * don't transition to "stopped" while the speaker is still playing the
     * last syllable.
     */
    private suspend fun drainAudioTrack() {
        val t = audioTrack ?: return
        if (t.state != AudioTrack.STATE_INITIALIZED) return
        var lastHead = -1
        var stableTicks = 0
        // Stable means "head hasn't moved for ~150ms" — playback caught up.
        while (currentCoroutineContext().isActive && isSynthesizing) {
            if (SupertonicTTS.isCancelled()) return
            val head = try { t.playbackHeadPosition } catch (_: Exception) { return }
            if (head == lastHead) {
                stableTicks++
                if (stableTicks >= 3) return
            } else {
                stableTicks = 0
                lastHead = head
            }
            delay(50)
        }
    }

    override fun onProgress(sessionId: Long, current: Int, total: Int) {}
    override fun onAudioChunk(sessionId: Long, data: ByteArray) {}

    fun play() {
        resumeOnFocusGain = false
        if (!isPlaying) {
            if (requestAudioFocus()) {
                isPlaying = true
                try {
                    if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                        audioTrack?.play()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing audio track", e)
                }
                notifyListenerState(true)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForegroundService(getString(R.string.notif_playing), true)
            }
        }
    }

    fun pause() {
        resumeOnFocusGain = false
        // a start still getting ready, or between chapters: nothing to hold — stop it,
        // the place is kept and "play" continues from it
        if (starting || (!isSynthesizing && isPlaying)) { stopServicePlayback(); return }
        if (isPlaying) {
            isPlaying = false
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.pause()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing audio track", e)
            }
            notifyListenerState(false)
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification(getString(R.string.notif_paused))
        }
    }

    fun stopPlayback(removeNotification: Boolean = true) {
        synchronized(this) {
            isPlaying = false
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.pause()
                    audioTrack?.flush()
                }
            } catch (_: Exception) { }
            // Deliberately keep the AudioTrack instance alive across stops so
            // the next synthesizeAndPlay reuses it without the ~500 ms re-init
            // delay observed on Oppo/OnePlus OEM ROMs. The track is released
            // only in onDestroy().
        }
        resumeOnFocusGain = false
        notifyListenerState(false)
        abandonAudioFocus()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (removeNotification) {
            notifyListenerPlaybackStopped()
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun stopServicePlayback() {
        playGen.incrementAndGet()
        starting = false
        isPlaying = false
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.pause()
            }
        } catch (_: Exception) {}

        serviceScope.launch {
            SupertonicTTS.setCancelled(true)
            isSynthesizing = false
            synthesisJob?.cancelAndJoin()
            stopPlayback()
        }
    }

    private fun notifyListenerState(playing: Boolean) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onStateChanged(playing, audioTrack != null || isSynthesizing, isSynthesizing)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerProgress(current: Int, total: Int) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onProgress(current, total)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun continueWithNextChapter(lang: String, stylePath: String, steps: Int) {
        val gen = playGen.get()
        serviceScope.launch {
            val next = try {
                withContext(Dispatchers.IO) { com.brahmadeo.supertonic.tts.utils.BookSession.nextChapter(this@PlaybackService) }
            } catch (e: Exception) {
                Log.e(TAG, "Next chapter failed", e); null
            }
            if (gen != playGen.get()) return@launch      // paused while the chapter was loading
            if (next == null) { stopPlayback(); return@launch }
            // same preparation as for a chapter opened by hand (MainActivity.prepareTextForTts)
            val prepared = if (lang.lowercase().startsWith("ko") || next.endsWith(" .")) next else "$next ."
            // the service owns "the current text": stamp it, so a player screen that was in the
            // background notices the change when it comes back (and does not write the old one back)
            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit().putString("last_text", prepared)
                .putLong("last_text_time", System.currentTimeMillis()).apply()
            notifyListenerTextChanged()
            synthesizeAndPlay(prepared, lang, stylePath, liveSpeed, steps, RESUME_INDEX)
        }
    }

    private fun notifyListenerTextChanged() {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onTextChanged()
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerPlaybackStopped() {
        com.brahmadeo.supertonic.tts.utils.EngineIdle.schedule(this)
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onPlaybackStopped()
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerExportComplete(success: Boolean, path: String) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onExportComplete(success, path)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            return audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> stopServicePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    resumeOnFocusGain = true
                    isPlaying = false
                    try {
                        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                            audioTrack?.pause()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pausing on focus loss", e)
                    }
                    notifyListenerState(false)
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    audioTrack?.setVolume(0.2f)
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    audioTrack?.setVolume(1.0f)
                } catch (_: Exception) {}
                if (resumeOnFocusGain) play()
            }
        }
    }

    fun exportAudio(text: String, lang: String, stylePath: String, speed: Float, steps: Int, outputFile: File) {
        serviceScope.launch {
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            
            stopPlayback(removeNotification = false)
            SupertonicTTS.setCancelled(false)
            isSynthesizing = true
            notifyListenerState(false)
            startForegroundService(getString(R.string.notif_exporting), false)
            
            synthesisJob = launch(Dispatchers.IO) {
                var exportSuccess = false
                try {
                    val sentences = textNormalizer.splitIntoSentences(text, lang)
                    if (sentences.isEmpty()) {
                        Log.w(TAG, "Export: No sentences found")
                        return@launch
                    }

                    val outputStream = ByteArrayOutputStream()
                    for ((index, sentence) in sentences.withIndex()) {
                        if (!isActive || SupertonicTTS.isCancelled()) break
                        
                        withContext(Dispatchers.Main) {
                            notifyListenerProgress(index + 1, sentences.size)
                        }

                        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                        val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                        val normalizedText = textNormalizer.normalize(sentence, lang, isAdvancedEnabled)

                        val audioData = SupertonicTTS.generateAudio(normalizedText, lang, stylePath, speed, 0.0f, steps, VOLUME_BOOST_FACTOR, null)
                        if (audioData != null && audioData.isNotEmpty()) {
                            outputStream.write(audioData)
                        } else if (SupertonicTTS.isCancelled()) {
                            break
                        }
                    }
                    
                    if (isActive && !SupertonicTTS.isCancelled() && outputStream.size() > 0) {
                        WavUtils.saveWav(outputFile, outputStream.toByteArray(), SupertonicTTS.getAudioSampleRate())
                        exportSuccess = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Export failed", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        isSynthesizing = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        notifyListenerExportComplete(exportSuccess, outputFile.absolutePath)
                        notifyListenerState(false)
                    }
                }
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun startForegroundService(status: String, showControls: Boolean) {
        val notification = buildNotification(status, showControls)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }

    // ---- synthesis speed in the notification (diagnostics, like the system engine) ----
    private var rtfOnSum = 0.0; private var rtfOnCount = 0
    private var rtfOffSum = 0.0; private var rtfOffCount = 0

    /** RTF = seconds of audio per second of synthesis; averaged separately for screen on / off. */
    private fun recordReaderRtf(audioBytes: Long, synthNanos: Long) {
        val rate = SupertonicTTS.getAudioSampleRate().coerceAtLeast(1)
        val audio = audioBytes / 2.0 / rate
        val synth = synthNanos / 1e9
        if (audio < 0.2 || synth < 0.02) return
        val rtf = audio / synth
        val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        if (screenOn) { rtfOnSum += rtf; rtfOnCount++ } else { rtfOffSum += rtf; rtfOffCount++ }
        fun avg(sum: Double, n: Int) = if (n == 0) "—" else String.format(java.util.Locale.US, "%.2f", sum / n)
        if ((rtfOnCount + rtfOffCount) % 3 == 0 && isPlaying) {
            try {
                updateNotification(getString(R.string.fg_speed_status, avg(rtfOnSum, rtfOnCount), avg(rtfOffSum, rtfOffCount)))
            } catch (e: Exception) { }
        }
        try {
            java.io.File(filesDir, "rtf.log").appendText(String.format(java.util.Locale.US,
                "%tT  reader RTF %.2f  screen %s%n", java.util.Date(), rtf, if (screenOn) "on" else "off"))
        } catch (e: Exception) { }
    }

    private fun updateNotification(status: String) {
        val notificationManager = attributionContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status, true))
    }

    private fun buildNotification(status: String, showControls: Boolean): android.app.Notification {
        val activityIntent = Intent(this, com.brahmadeo.supertonic.tts.PlaybackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("is_resume", true)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // the phrase being heard on top, the book under it (as Moon+ Reader shows it); the
        // speed diagnostics, if any, go to the small header line
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(nowPhrase.ifEmpty { nowBook.ifEmpty { getString(R.string.app_name) } })
            .setContentText(if (nowPhrase.isNotEmpty()) nowBook else status)
            .setSubText(if (nowPhrase.isNotEmpty() && status.contains('×')) status else null)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(appIcon())
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // the buttons go straight to this service (there is no media-button receiver in the app,
        // so the old MediaButtonReceiver intents did nothing)
        fun action(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (showControls) {
            builder.addAction(android.R.drawable.ic_media_previous, getString(R.string.player_prev), action("TERA_PREV", 11))
            if (isPlaying) builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_paused), action("TERA_PAUSE", 12))
            else builder.addAction(android.R.drawable.ic_media_play, getString(R.string.notif_playing), action("TERA_PLAY", 13))
            builder.addAction(android.R.drawable.ic_media_next, getString(R.string.player_next), action("TERA_NEXT", 14))
            builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), action("STOP_PLAYBACK", 15))
            builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DEFAULT (but silent), not LOW: Huawei and others hide "silent" notifications from
            // the lock screen and the always-on display — and the reading card with them
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_reading), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            try { (attributionContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).deleteNotificationChannel("supertonic_playback") } catch (_: Exception) {}
            val manager = attributionContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        serviceScope.cancel()
        abandonAudioFocus()
    }
}