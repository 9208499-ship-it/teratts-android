package com.brahmadeo.supertonic.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.content.Context
import android.os.Build
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

class SupertonicTextToSpeechService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Job? = null

    private val attributionContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("supertonic_playback")
        } else {
            this
        }
    }

    companion object {
        // TeraTTS outputs full-scale speech; the 2.5× boost tuned for Supertonic clipped it
        const val VOLUME_BOOST_FACTOR = 1.0f
        /** First chunk of every utterance is cut near this length (see fastStart). */
        const val FAST_START_CHARS = 40
        private const val FG_CHANNEL = "teratts_reading"
        const val ACTION_KEEP_ALIVE = "ru.tolyos.teratts.KEEP_ALIVE"
        const val ACTION_STOP_KEEP_ALIVE = "ru.tolyos.teratts.STOP_KEEP_ALIVE"
        const val PREF_KEEP_ALIVE = "keep_alive_foreground"
        /** Huawei PowerGenie leaves apps holding a wake lock with this tag alone. */
        const val WAKELOCK_TAG = "AudioMix"
        private const val FG_ID = 4711
        /** Passed to the engine as-is; it answers with a long pause. */
        const val SCENE_BREAK = "⁂"

        // ISO-639-2/3 language codes Android may pass us → our internal 2-letter Supertonic codes.
        private val LANG_PREFIX_MAP: Map<String, String> = mapOf(
            // TeraTTS v2 speaks Russian and English only
            "ru" to "ru", "rus" to "ru",
            "en" to "en", "eng" to "en"
        )

        // Reverse map: our 2-letter codes → preferred ISO-639-3 form to advertise to Android (with country).
        private val ANDROID_LOCALE_TRIPLES: List<Triple<String, String, String>> = listOf(
            Triple("ru", "rus", "RUS"),  // first = fallback
            Triple("en", "eng", "USA")
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("SupertonicTTS", "Service created")
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.load(this)
        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.load(this)
        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.load(this)

        initJob = serviceScope.launch(Dispatchers.IO) {
            val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
            SupertonicTTS.initialize(modelPath, libPath)
            // Prewarm (see PlaybackService for rationale). Idempotent — if
            // PlaybackService was up first and warmed, this is a no-op.
            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val voiceFile = prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
            val stylePath = File(filesDir,
                "${AssetManager.MODEL_VERSION}/voice_styles/$voiceFile").absolutePath
            SupertonicTTS.prewarm(stylePath)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // the system is short of memory and nothing is being read: give the models back
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !foregroundActive) {
            com.brahmadeo.supertonic.tts.utils.EngineIdle.releaseNow(this)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(leaveForegroundRunnable)
        leaveForeground()
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val language = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val supported = LANG_PREFIX_MAP.keys.any { language.startsWith(it) }
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED

        return if (AssetManager.isReady(this)) {
            if (!country.isNullOrEmpty()) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_MISSING_DATA
        }
    }

    override fun onGetLanguage(): Array<String> {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val selectedLang = prefs.getString("selected_lang", "ru") ?: "ru"
        val triple = ANDROID_LOCALE_TRIPLES.find { it.first == selectedLang }
            ?: ANDROID_LOCALE_TRIPLES.first() // fall back to Russian
        return arrayOf(triple.second, triple.third, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        if (!voiceName.contains("-supertonic-")) return TextToSpeech.ERROR
        val styleName = voiceName.substringAfter("-supertonic-")
        val file = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles/$styleName.json")
        return if (file.exists()) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val selected = prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
        val voiceName = if (selected.endsWith(".json")) selected.substringBeforeLast(".") else selected
        val prefix = normalizeLanguage(lang)
        return "$prefix-supertonic-$voiceName"
    }

    override fun onGetVoices(): List<Voice> {
        val voicesList = mutableListOf<Voice>()
        // built-in voices first, then anything else in the voice folder (custom voices like my_voice.json)
        val styleDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        val onDisk = styleDir.listFiles { _, n -> n.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
        val voiceNames = (AssetManager.VOICES + AssetManager.SUPERTONIC_VOICES.map { "st3_$it" } + onDisk).distinct()
        if (!AssetManager.isReady(this)) return voicesList

        ANDROID_LOCALE_TRIPLES.forEach { (twoLetter, _, _) ->
            val locale = Locale.forLanguageTag(twoLetter)
            voiceNames.forEach { name ->
                voicesList.add(
                    Voice(
                        "$twoLetter-supertonic-$name",
                        locale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        false,
                        setOf()
                    )
                )
            }
        }
        return voicesList
    }

    override fun onStop() {
        SupertonicTTS.setCancelled(true)
    }

    private fun normalizeLanguage(lang: String?): String {
        if (lang == null) return "ru"
        val l = lang.lowercase(Locale.ROOT)
        return LANG_PREFIX_MAP.entries.firstOrNull { l.startsWith(it.key) }?.value ?: "ru"
    }

    /**
     * Override the requested language when the actual text content tells us
     * otherwise. Apps like Moon+ Reader sometimes don't set the language
     * field, so we'd get whatever the system locale is — and a Russian
     * audiobook would land in our English path, missing Cyrillic stress
     * marks and number-to-words spellout.
     *
     * Counts Cyrillic vs Latin letters and overrides only when Cyrillic is
     * the clear majority. Threshold tuned to avoid flipping on isolated
     * proper nouns inside an English text ("Pushkin", "Tolstoy").
     */
    private fun detectLanguage(text: String, requested: String): String {
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in 'Ѐ'..'ӿ' -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        return if (cyrillic > latin && cyrillic >= 4) "ru" else requested
    }

    private val textNormalizer = com.brahmadeo.supertonic.tts.utils.TextNormalizer()

    /**
     * Readers hand the engine the next utterance only when the current one is
     * almost played out (~0.5 s of audio left in the system queue). A long first
     * sentence then takes longer than that to synthesize and leaves a gap, so the
     * first sentence is cut at a comma (or a space) near FAST_START_CHARS: its audio
     * starts quickly and the rest is synthesized while it plays.
     */
    /**
     * Split an utterance into (sentence, endsParagraph). Readers may send several
     * paragraphs separated by line breaks; scene-break ornaments ("* * *") become
     * SCENE_BREAK. A one-sentence utterance is most likely a reader speaking
     * sentence by sentence, so it gets no paragraph pause (it would slow every
     * sentence down); multi-sentence utterances are paragraphs.
     */
    private fun paragraphUnits(rawText: String, lang: String): List<Pair<String, Boolean>> {
        val units = mutableListOf<Pair<String, Boolean>>()
        for (p in rawText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (isSceneBreak(p)) { units.add(SCENE_BREAK to false); continue }
            val ss = textNormalizer.splitIntoSentences(p, lang).filter { it.isNotBlank() }
            ss.forEachIndexed { i, s -> units.add(s to (i == ss.lastIndex)) }
        }
        if (units.count { it.first != SCENE_BREAK } < 2 && units.isNotEmpty()) {
            units[units.lastIndex] = units.last().first to false
        }
        // fast start: cut the first sentence so audio begins quickly
        val first = units.firstOrNull() ?: return units
        if (first.first == SCENE_BREAK) return units
        val parts = fastStart(listOf(first.first))
        if (parts.size == 2) {
            units[0] = parts[1] to first.second
            units.add(0, parts[0] to false)
        }
        return units
    }

    private fun isSceneBreak(p: String): Boolean {
        val marks = p.count { !it.isWhitespace() }
        val ornament = p.all { it.isWhitespace() || it in "*⁂#~—–-_=•·◆◇❖§" }
        return ornament && (marks >= 3 || '⁂' in p || '❖' in p)
    }

    private fun fastStart(sentences: List<String>): List<String> {
        val first = sentences.firstOrNull() ?: return sentences
        if (first.length <= FAST_START_CHARS + 15) return sentences
        val window = first.substring(0, FAST_START_CHARS)
        var cut = window.indexOfLast { it == ',' || it == ';' || it == ':' || it == '—' }
        cut = if (cut >= 15) cut + 1 else window.lastIndexOf(' ')
        if (cut < 15) return sentences
        val head = first.substring(0, cut).trim()
        val tail = first.substring(cut).trim()
        if (head.isEmpty() || tail.isEmpty()) return sentences
        return listOf(head, tail) + sentences.drop(1)
    }

    /**
     * Keeps the CPU running while an utterance is synthesized. With the screen
     * off the phone may otherwise suspend or slow the CPU between phrases, which
     * turned into long pauses in readers.
     */
    private val synthWakeLock: android.os.PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            // "AudioMix" is on Huawei PowerGenie's hard-coded whitelist of wake-lock
            // tags (dontkillmyapp.com/huawei); any other tag gets the app throttled or
            // killed once the screen is off. We are an audio app, so it's also apt.
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    // ---- foreground mode while reading ------------------------------------
    // With the screen locked the phone puts background processes into a CPU
    // group with a small quota (EMUI is especially strict). A wake lock keeps the
    // CPU awake, but not fast. As a foreground service (ongoing notification, as
    // SmartVoice/RHVoice do) the engine gets full CPU while a reader is speaking.
    // Starting it from the background is allowed when battery optimisation is
    // off for the app; otherwise this quietly stays a background service.
    private var foregroundActive = false
    /** Kept in the foreground permanently (started from the open app), not just while reading. */
    private var persistentForeground = false

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KEEP_ALIVE -> {
                persistentForeground = true
                enterForeground()
            }
            ACTION_STOP_KEEP_ALIVE -> {
                // off until the app is opened again
                persistentForeground = false
                leaveForeground()
                stopSelf()
            }
        }
        return START_STICKY
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val leaveForegroundRunnable = Runnable { if (!persistentForeground) leaveForeground() }

    private fun enterForeground() {
        mainHandler.removeCallbacks(leaveForegroundRunnable)
        if (foregroundActive) return
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(FG_CHANNEL) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(FG_CHANNEL, "TeraTTS", android.app.NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
            val notification = buildNotification(statusText ?: getString(com.brahmadeo.supertonic.tts.R.string.fg_reading_aloud))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FG_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(FG_ID, notification)
            }
            foregroundActive = true
        } catch (e: Exception) {
            Log.w("TeraTTS", "Foreground mode not allowed right now: ${e.message}")
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = android.app.PendingIntent.getService(
            this, 1,
            android.content.Intent(this, SupertonicTextToSpeechService::class.java).setAction(ACTION_STOP_KEEP_ALIVE),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return androidx.core.app.NotificationCompat.Builder(this, FG_CHANNEL)
            .setSmallIcon(com.brahmadeo.supertonic.tts.R.mipmap.ic_launcher)
            .setContentTitle("TeraTTS")
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(com.brahmadeo.supertonic.tts.R.string.fg_turn_off), stopIntent)
            .build()
    }

    // ---- synthesis speed shown in the notification (diagnostics) ----------
    private var statusText: String? = null
    private var rtfOnSum = 0.0
    private var rtfOnCount = 0
    private var rtfOffSum = 0.0
    private var rtfOffCount = 0

    /** RTF = seconds of audio per second of synthesis; >1 keeps up, <1 falls behind. */
    private fun recordRtf(rtf: Double, screenOn: Boolean, speed: Float) {
        if (screenOn) { rtfOnSum += rtf; rtfOnCount++ } else { rtfOffSum += rtf; rtfOffCount++ }
        fun avg(sum: Double, n: Int) = if (n == 0) "—" else String.format(java.util.Locale.US, "%.2f", sum / n)
        val text = getString(com.brahmadeo.supertonic.tts.R.string.fg_speed_status,
            avg(rtfOnSum, rtfOnCount), avg(rtfOffSum, rtfOffCount))
        statusText = text
        if (foregroundActive) {
            try {
                getSystemService(android.app.NotificationManager::class.java).notify(FG_ID, buildNotification(text))
            } catch (e: Exception) { }
        }
        try {
            java.io.File(filesDir, "rtf.log").appendText(String.format(java.util.Locale.US,
                "%tT  RTF %.2f  speed %.2f  screen %s  cores %s%n", java.util.Date(), rtf, speed, if (screenOn) "on" else "off",
                if (com.brahmadeo.supertonic.tts.SupertonicTTS.fastCoresOnly) "fast" else "all"))
        } catch (e: Exception) { }
    }

    private fun leaveForeground() {
        if (!foregroundActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w("TeraTTS", "stopForeground failed: ${e.message}")
        }
        foregroundActive = false
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        synthWakeLock.acquire(2 * 60 * 1000L)
        enterForeground()
        try {
            synthesizeLocked(request, callback)
        } finally {
            if (synthWakeLock.isHeld) synthWakeLock.release()
            // stay foreground between sentences; drop the notification after a minute of silence
            mainHandler.postDelayed(leaveForegroundRunnable, 60_000L)
            // and let go of the models a few minutes after the last phrase
            com.brahmadeo.supertonic.tts.utils.EngineIdle.schedule(this)
        }
    }

    private fun synthesizeLocked(request: SynthesisRequest, callback: SynthesisCallback) {
        // The synthesis itself runs on this thread (plus ONNX Runtime's pool).
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (e: Exception) { }
        SupertonicTTS.setCancelled(false)
        runBlocking {
            withTimeoutOrNull(5000) {
                initJob?.join()
            }
        }
        val rawText = request.charSequenceText?.toString() ?: return
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)
        callback.start(SupertonicTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val requestedVoice = request.voiceName
        val requestedLang = detectLanguage(rawText, normalizeLanguage(request.language))
        val prefs = attributionContext.getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)

        val voiceFile = if (requestedVoice != null && requestedVoice.contains("-supertonic-")) {
            val fileName = requestedVoice.substringAfter("-supertonic-")
            // Sanitize fileName to prevent path traversal
            File(fileName).name + ".json"
        } else {
            prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
        }

        val voiceStyleDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        var stylePath = File(voiceStyleDir, voiceFile).absolutePath

        // Ensure stylePath is within the intended directory
        if (!File(stylePath).canonicalPath.startsWith(voiceStyleDir.canonicalPath)) {
            stylePath = File(voiceStyleDir, "ru_f1.json").absolutePath
        }

        val isMixing = prefs.getBoolean("is_mixing_enabled", false)
        if (isMixing) {
            val voice2 = prefs.getString("selected_voice_2", "ru_m5.json") ?: "ru_m5.json"
            val stylePath2 = File(voiceStyleDir, voice2).absolutePath
            val alpha = prefs.getFloat("mix_alpha", 0.5f)
            if (File(stylePath).exists() && File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;$alpha"
            }
        }

        val steps = prefs.getInt("diffusion_steps", 5)

        if (SupertonicTTS.getSoC() == -1) {
            val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
            SupertonicTTS.initialize(modelPath, libPath)
        }

        // Streaming + queue pipeline, mirroring PlaybackService.
        // Producer (Rust callback) -> Channel<ByteArray> -> Consumer (audioAvailable).
        //
        // Without this, onSynthesizeText used to wait for an entire sentence
        // of audio (3-5 s) before handing anything over to Android's TTS
        // system. With clients like Moon+ Reader that means a multi-second
        // gap at the start of every block. Now bytes go to audioAvailable
        // chunk-by-chunk as soon as the vocoder produces them, and the
        // 50-chunk buffer lets the producer race ahead while Android plays.
        val ttsChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 50)
        // speed measurement for the log: audio produced vs time spent
        var audioBytes = 0L
        val synthStarted = System.nanoTime()
        val streamingListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray) {
                audioBytes += data.size
                if (SupertonicTTS.isCancelled()) return
                // Block on send instead of busy-waiting. See PlaybackService
                // for the same pattern + rationale (no CPU burn vs the old
                // 50 Hz trySend poll loop).
                try {
                    runBlocking { ttsChannel.send(data) }
                } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                    // Producer closed the channel — fine.
                } catch (_: InterruptedException) {
                    // Caller interrupted us — return cleanly.
                }
            }
        }

        val consumerJob = serviceScope.launch(Dispatchers.IO) {
            // AUDIO priority for the thread that drains PCM chunks into
            // Android's TTS callback — same rationale as in PlaybackService.
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_AUDIO
                )
            } catch (_: Throwable) {
                // Best-effort.
            }
            for (data in ttsChannel) {
                if (SupertonicTTS.isCancelled()) break
                var offset = 0
                while (offset < data.size) {
                    val length = 4096.coerceAtMost(data.size - offset)
                    callback.audioAvailable(data, offset, length)
                    offset += length
                }
            }
        }

        var success = true
        try {
            val units = paragraphUnits(rawText, requestedLang)
            for ((index, unit) in units.withIndex()) {
                val (sentence, paragraphEnd) = unit
                if (SupertonicTTS.isCancelled()) { success = false; break }

                val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                val normalizedText = if (sentence == SCENE_BREAK) sentence
                    else textNormalizer.normalize(sentence, requestedLang, isAdvancedEnabled)
                // U+2029 tells the engine to add the paragraph pause after this sentence
                var toSynthesize = if (paragraphEnd) normalizedText + "\u2029" else normalizedText
                // U+2063: last sentence of this utterance (engine trims the pause by the
                // latency the next utterance will add anyway)
                if (index == units.lastIndex) toSynthesize += "\u2063"

                SupertonicTTS.generateAudio(
                    toSynthesize, requestedLang, stylePath, effectiveSpeed, 0.0f,
                    steps, VOLUME_BOOST_FACTOR, streamingListener
                )

                if (SupertonicTTS.isCancelled()) { success = false; break }
            }
        } finally {
            // RTF = seconds of audio per second of synthesis (>1 keeps up, <1 falls behind).
            // Measured before waiting for playback, so it reflects synthesis speed only.
            val synthSeconds = (System.nanoTime() - synthStarted) / 1e9
            val audioSeconds = audioBytes / 2.0 / SupertonicTTS.getAudioSampleRate()
            if (synthSeconds > 0.05 && audioSeconds > 0.2) {
                val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
                recordRtf(audioSeconds / synthSeconds, screenOn, effectiveSpeed)
                android.util.Log.i("TeraTTS", String.format(java.util.Locale.US,
                    "RTF %.2f (%.1f s audio in %.1f s), speed %.2f, screen %s",
                    audioSeconds / synthSeconds, audioSeconds, synthSeconds, effectiveSpeed,
                    if (screenOn) "on" else "off"))
            }
            ttsChannel.close()
            runBlocking { consumerJob.join() }
        }
        if (success) callback.done() else callback.error()
    }
}
