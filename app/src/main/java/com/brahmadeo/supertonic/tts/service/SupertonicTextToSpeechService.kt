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
        const val FAST_START_CHARS = 70

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

    override fun onDestroy() {
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
    private fun fastStart(sentences: List<String>): List<String> {
        val first = sentences.firstOrNull() ?: return sentences
        if (first.length <= FAST_START_CHARS + 20) return sentences
        val window = first.substring(0, FAST_START_CHARS)
        var cut = window.indexOfLast { it == ',' || it == ';' || it == ':' || it == '—' }
        cut = if (cut >= 25) cut + 1 else window.lastIndexOf(' ')
        if (cut < 25) return sentences
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
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TeraTTS:synthesis")
            .apply { setReferenceCounted(false) }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        synthWakeLock.acquire(2 * 60 * 1000L)
        try {
            synthesizeLocked(request, callback)
        } finally {
            if (synthWakeLock.isHeld) synthWakeLock.release()
        }
    }

    private fun synthesizeLocked(request: SynthesisRequest, callback: SynthesisCallback) {
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
        val streamingListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray) {
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
            val sentences = fastStart(textNormalizer.splitIntoSentences(rawText, requestedLang))
            for (sentence in sentences) {
                if (SupertonicTTS.isCancelled()) { success = false; break }

                val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                val normalizedText = textNormalizer.normalize(sentence, requestedLang, isAdvancedEnabled)

                SupertonicTTS.generateAudio(
                    normalizedText, requestedLang, stylePath, effectiveSpeed, 0.0f,
                    steps, VOLUME_BOOST_FACTOR, streamingListener
                )

                if (SupertonicTTS.isCancelled()) { success = false; break }
            }
        } finally {
            ttsChannel.close()
            runBlocking { consumerJob.join() }
        }
        if (success) callback.done() else callback.error()
    }
}
