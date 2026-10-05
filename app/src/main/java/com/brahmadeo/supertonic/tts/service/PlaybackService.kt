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
            this@PlaybackService.play()
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

        override fun getCurrentIndex(): Int {
            return currentSentenceIndex
        }
    }

    private val listeners = RemoteCallbackList<IPlaybackListener>()

    fun setListener(listener: IPlaybackListener?) {
        if (listener != null) {
            listeners.register(listener)
            try {
                listener.onStateChanged(isPlaying, audioTrack != null || isSynthesizing, isSynthesizing)
                listener.onProgress(currentSentenceIndex, -1)
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
    private val textNormalizer = TextNormalizer()
    private var resumeOnFocusGain = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
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

    private var currentSentenceIndex: Int = 0

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
        const val CHANNEL_ID = "supertonic_playback"
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
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Supertonic:PlaybackWakeLock").apply {
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
                override fun onPlay() { this@PlaybackService.play() }
                override fun onPause() { this@PlaybackService.pause() }
                override fun onStop() { this@PlaybackService.stopPlayback() }
            })
            isActive = true
        }

        val modelPath = File(filesDir, "${com.brahmadeo.supertonic.tts.utils.AssetManager.MODEL_VERSION}/onnx").absolutePath
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
        SupertonicTTS.initialize(modelPath, libPath)
        // Prewarm: synthesize a throwaway "." in the background so XNNPACK
        // JITs its kernels and ORT lays out activation buffers before the
        // user's first real request. Saves ~300-700 ms off the first audible
        // word on weak SoCs. Best-effort; idempotent (SupertonicTTS guards
        // against double-prewarming).
        serviceScope.launch(Dispatchers.IO) {
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
        } else if (intent?.action == "RESET_ENGINE") {
            SupertonicTTS.release()
            val modelPath = File(filesDir, "${com.brahmadeo.supertonic.tts.utils.AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
            SupertonicTTS.initialize(modelPath, libPath)
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

    fun synthesizeAndPlay(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        serviceScope.launch {
            // Cancel any in-flight synthesis, but keep the AudioTrack alive so the
            // next sentence can stream straight in without a re-init delay.
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }

            val rate = SupertonicTTS.getAudioSampleRate()
            ensureAudioTrack(rate)
            try { audioTrack?.flush() } catch (_: Exception) {}

            isSynthesizing = true
            isPlaying = true
            SupertonicTTS.setCancelled(false)

            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            startForegroundService(getString(R.string.notif_synthesizing), false)
            notifyListenerState(false)

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

            synthesisJob = launch(Dispatchers.IO) {
                val sentences = textNormalizer.splitIntoSentences(text, effectiveLang)
                val totalSentences = sentences.size
                val validStartIndex = if (startIndex in 0 until totalSentences) startIndex else 0

                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)

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
                        writeToTrackBlocking(data)
                    }
                }

                var producedSentences = 0
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

                        withContext(Dispatchers.Main) {
                            currentSentenceIndex = index
                            notifyListenerProgress(index, totalSentences)
                        }

                        val normalizedText = textNormalizer.normalize(sentences[index], effectiveLang, isAdvancedEnabled)

                        // Streaming: each finished chunk inside generateAudio is
                        // pushed via streamingListener.onAudioChunk into the
                        // channel, where the consumer above picks it up.
                        val result = SupertonicTTS.generateAudio(
                            normalizedText, effectiveLang, stylePath, speed, 0.0f, steps,
                            VOLUME_BOOST_FACTOR, streamingListener
                        )

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
                            if (!statePromotedToPlaying) {
                                statePromotedToPlaying = true
                                withContext(Dispatchers.Main) {
                                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                    notifyListenerState(true)
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

                withContext(Dispatchers.Main) {
                    if (isSynthesizing && isActive) {
                        val wasCancelled = SupertonicTTS.isCancelled()
                        isSynthesizing = false
                        if (!wasCancelled) {
                            notifyListenerProgress(totalSentences, totalSentences)
                        }
                        notifyListenerState(true)
                        if (!wasCancelled) {
                            val nextItem = QueueManager.next()
                            if (nextItem != null) {
                                SupertonicTTS.reset()
                                synthesizeAndPlay(nextItem.text, nextItem.lang, nextItem.stylePath, nextItem.speed, nextItem.steps, nextItem.startIndex)
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

    private fun notifyListenerPlaybackStopped() {
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
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun startForegroundService(status: String, showControls: Boolean) {
        val notification = buildNotification(status, showControls)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))

        if (showControls) {
            if (isPlaying) {
                builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_paused),
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE))
            } else {
                builder.addAction(android.R.drawable.ic_media_play, getString(R.string.yes), // No play string in resources, reusing yes for now or just generic
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY))
            }
        } else {
             builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel),
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
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