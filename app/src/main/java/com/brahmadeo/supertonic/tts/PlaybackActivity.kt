package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.RemoteException
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.brahmadeo.supertonic.tts.service.IPlaybackListener
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.ui.PlaybackScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit
import kotlinx.coroutines.launch

class PlaybackActivity : ComponentActivity() {

    private var playbackService: IPlaybackService? = null
    private var isBound = false

    // Reactive State
    private var sentencesState = mutableStateOf<List<String>>(emptyList())
    private var textState = mutableStateOf("")   // the chapter as it is, for the book page
    private var isBookState = mutableStateOf(false)  // the text is a chapter of the open book
    private var sleepLeftState = mutableStateOf<Int?>(null)  // sleep timer, see SleepTimer.left

    /** The book's contents opened from the player: the chosen chapter is read here. */
    private val tocLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra(EbookOutlineActivity.EXTRA_TEXT) ?: return@registerForActivityResult
        // "continue where I stopped" in the contents: its place; a chapter: its beginning
        openChapter(text, atPlace = result.data?.getBooleanExtra("open_player", false) == true)
    }

    /** Search across the book: the chosen place is opened (and read from, if it was reading). */
    private val searchLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val chapter = result.data?.getIntExtra(BookSearchActivity.EXTRA_CHAPTER, -1) ?: -1
        val offset = result.data?.getIntExtra(BookSearchActivity.EXTRA_OFFSET, 0) ?: 0
        if (chapter < 0) return@registerForActivityResult
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { com.brahmadeo.supertonic.tts.utils.BookSession.chapterAt(this@PlaybackActivity, chapter) } catch (e: Exception) { null }
            }
            if (text != null) openChapterAt(text, offset)
        }
    }

    private fun openSearch() {
        com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this, currentText)
        if (com.brahmadeo.supertonic.tts.utils.BookSession.currentPath(this) == null) {
            Toast.makeText(this, getString(R.string.search_no_book), Toast.LENGTH_SHORT).show(); return
        }
        searchLauncher.launch(Intent(this, BookSearchActivity::class.java))
    }

    /** A chapter shown at a found place ([offset] in its text): that phrase lit, read from it if it was reading. */
    private fun openChapterAt(raw: String, offset: Int) {
        val wasPlaying = isPlayingState.value
        val t = raw.trim()
        val prepared = if (currentLang.lowercase().startsWith("ko") || t.endsWith(" .")) t else "$t ."
        currentText = prepared
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit()
            .putString("last_text", prepared)
            .putLong("last_text_time", System.currentTimeMillis().also { textLoadedAt = it }).apply()
        setupList(prepared)
        val ranges = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(prepared, sentencesState.value)
        val idx = ranges.indexOfLast { it != null && it.first <= offset }.coerceAtLeast(0)
        currentIndexState.intValue = idx
        if (wasPlaying) playFromIndex(idx)
        else try { if (playbackService?.isServiceActive == true) playbackService?.stop() } catch (_: Exception) {}
    }

    // ---- a word's stress, from a double tap on the book page ----
    private val stressWordState = mutableStateOf<String?>(null)
    private var stressPaused = false     // reading was paused for the dialog: go on after it

    private fun askStress(word: String) {
        stressPaused = isPlayingState.value
        if (stressPaused) try { playbackService?.pause() } catch (_: Exception) {}
        stressWordState.value = word
    }

    private fun stressDone(saved: Boolean) {
        stressWordState.value = null
        if (!stressPaused) return
        stressPaused = false
        try {
            // saved: the phrase is made again, now with the new stress; the "listen" button
            // stopped the reading — start from the phrase too; otherwise just go on
            if (!saved && playbackService?.isServiceActive == true) playbackService?.play()
            else playFromIndex(currentIndexState.intValue.coerceAtLeast(0))
        } catch (_: Exception) {}
    }

    private fun openToc() {
        val path = com.brahmadeo.supertonic.tts.utils.BookSession.currentPath(this) ?: return
        tocLauncher.launch(Intent(this, EbookOutlineActivity::class.java).putExtra(EbookOutlineActivity.EXTRA_URI, path))
    }

    /** Show [raw] (a chapter of the book) in the player; keep reading if it was reading. */
    private fun openChapter(raw: String, atPlace: Boolean) {
        val wasPlaying = isPlayingState.value
        val t = raw.trim()
        val prepared = if (currentLang.lowercase().startsWith("ko") || t.endsWith(" .")) t else "$t ."
        currentText = prepared
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit()
            .putString("last_text", prepared)
            .putLong("last_text_time", System.currentTimeMillis().also { textLoadedAt = it }).apply()
        setupList(prepared)
        currentIndexState.intValue = if (atPlace) {
            PlaybackService.resumeIndex(this, prepared, sentencesState.value, currentSpeed) ?: 0
        } else 0
        if (wasPlaying || atPlace) playFromIndex(currentIndexState.intValue)
        else try { if (playbackService?.isServiceActive == true) playbackService?.stop() } catch (_: Exception) {}
    }

    /** The chapter before ([dir] = -1) or after (+1) the one on screen. */
    private fun stepChapter(dir: Int) {
        com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this, currentText)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { com.brahmadeo.supertonic.tts.utils.BookSession.stepChapter(this@PlaybackActivity, dir) } catch (e: Exception) { null }
            }
            if (text == null) Toast.makeText(this@PlaybackActivity, getString(R.string.player_no_chapter), Toast.LENGTH_SHORT).show()
            else openChapter(text, atPlace = false)
        }
    }
    private var currentIndexState = mutableIntStateOf(-1)
    private var isPlayingState = mutableStateOf(false)
    private var isServiceActiveState = mutableStateOf(false)
    /** When this screen got its text; a newer "last_text" from the service wins on resume. */
    private var textLoadedAt = 0L
    private var isExportingState = mutableStateOf(false)
    private var exportCurrentState = mutableIntStateOf(0)
    private var exportTotalState = mutableIntStateOf(0)

    // State persistence
    private var currentText = ""
    private var currentVoicePath = ""
    private var currentSpeed = 1.0f
    private var currentSteps = 5
    private var currentLang = "en"

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_VOICE_PATH = "extra_voice_path"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_LANG = "extra_lang"
    }

    /** Share of the current phrase already heard: lights the word being spoken. */
    private val spokenFractionState = mutableFloatStateOf(0f)

    private val playbackListenerStub = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                isPlayingState.value = isPlaying
                isServiceActiveState.value = isPlaying || isSynthesizing
            }
        }

        override fun onProgress(current: Int, total: Int) {
            runOnUiThread {
                if (isExportingState.value) {
                    exportCurrentState.intValue = current
                    exportTotalState.intValue = total
                } else {
                    currentIndexState.intValue = current
                    updateIndexState(current)
                    if (total > 0 && current !in 0 until total) {
                        clearState()
                    }
                }
            }
        }

        override fun onPlaybackStopped() {
            runOnUiThread {
                isPlayingState.value = false
                isServiceActiveState.value = false
            }
        }

        override fun onSpokenPosition(index: Int, fraction: Float) {
            runOnUiThread {
                if (index != currentIndexState.intValue) currentIndexState.intValue = index
                spokenFractionState.floatValue = fraction
            }
        }

        override fun onTextChanged() {
            // the next chapter started by itself: show its text
            runOnUiThread {
                val newText = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("last_text", "") ?: ""
                if (newText.isNotEmpty() && newText != currentText) {
                    currentText = newText
                    textLoadedAt = System.currentTimeMillis()
                    setupList(currentText)
                    currentIndexState.intValue = 0
                }
            }
        }

        override fun onExportComplete(success: Boolean, path: String) {
            runOnUiThread {
                if (!isExportingState.value) return@runOnUiThread
                isExportingState.value = false
                if (success) {
                    Toast.makeText(this@PlaybackActivity, getString(R.string.saved_to_fmt, path), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@PlaybackActivity, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            try {
                playbackService?.setListener(playbackListenerStub)
                isBound = true

                if (intent.getBooleanExtra("is_resume", false)) {
                    // a book just opened at its place: whatever the service was playing is not it
                    val isActive = playbackService?.isServiceActive == true && !intent.getBooleanExtra("fresh", false)
                    if (isActive) {
                        val serviceIndex = playbackService?.getCurrentIndex() ?: -1
                        if (serviceIndex != -1) {
                            currentIndexState.intValue = serviceIndex
                        }
                    } else if (!intent.getBooleanExtra("no_autoplay", false)) {
                        // Not playing in service, but user wants to resume:
                        // Start playback from the saved index
                        playFromIndex(currentIndexState.intValue)
                    }
                    // opened at app launch: show the book at its place and wait for "Play"
                    restoreState()
                } else {
                    startPlaybackFromIntent()
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        currentText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        currentVoicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: ""
        currentSpeed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        currentSteps = intent.getIntExtra(EXTRA_STEPS, 5)
        currentLang = intent.getStringExtra(EXTRA_LANG) ?: "en"
        textLoadedAt = System.currentTimeMillis()

        if (intent.getBooleanExtra("is_resume", false) && currentText.isEmpty()) {
             val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
             currentText = prefs.getString("last_text", "") ?: ""
             currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
             currentSpeed = prefs.getFloat("last_speed", 1.0f)
             currentSteps = prefs.getInt("last_steps", 5)
             currentLang = prefs.getString("last_lang", "en") ?: "en"
             currentIndexState.intValue = prefs.getInt("last_index", 0)
             textLoadedAt = prefs.getLong("last_text_time", 0L)
        }

        setupList(currentText)
        // a book opened at its place: it is now "the text in the player" (the app's next
        // launch and the lock-screen Play continue it, not what was read before)
        if (intent.getBooleanExtra("fresh", false) && currentText.isNotEmpty()) {
            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit()
                .putString("last_text", currentText).putLong("last_text_time", textLoadedAt)
                .putString("last_voice_path", currentVoicePath).putFloat("last_speed", currentSpeed)
                .putInt("last_steps", currentSteps).putString("last_lang", currentLang).apply()
        }
        if (intent.getBooleanExtra("is_resume", false)) {
            // the place reached by the voice in this text, a minute back — shown and lit before "Play"
            com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this, currentText)
            PlaybackService.resumeIndex(this, currentText, sentencesState.value, currentSpeed)?.let {
                currentIndexState.intValue = it
            }
        }

        setContent {
            // the sleep timer's minutes left, refreshed while the screen is shown
            LaunchedEffect(Unit) {
                while (true) {
                    sleepLeftState.value = com.brahmadeo.supertonic.tts.utils.SleepTimer.left(this@PlaybackActivity)
                    kotlinx.coroutines.delay(5000)
                }
            }
            SupertonicTheme(voiceFile = currentVoicePath) {
                PlaybackScreen(
                    sentences = sentencesState.value,
                    text = textState.value,
                    title = textState.value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "",
                    currentIndex = currentIndexState.intValue,
                    isPlaying = isPlayingState.value,
                    isServiceActive = isServiceActiveState.value,
                    isExporting = isExportingState.value,
                    exportCurrent = exportCurrentState.intValue,
                    exportTotal = exportTotalState.intValue,
                    onBackClick = { finish() },
                    onItemClick = { index -> playFromIndex(index) },
                    onPlayPauseClick = { handlePlayPause() },
                    onStopClick = { handleStop() },
                    onExportClick = { startExport() },
                    speed = currentSpeed,
                    onSpeedChange = { v ->
                        currentSpeed = v
                        try { playbackService?.setSpeed(v) } catch (e: Exception) { }
                        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit()
                            .putFloat("last_speed", v).putFloat("speed", v).apply()
                    },
                    onSentenceLongClick = { i -> showSpeakerDialog(i) },
                    currentFraction = if (isPlayingState.value) spokenFractionState.floatValue else -1f,
                    onToc = if (isBookState.value) ({ openToc() }) else null,
                    onPrevChapter = if (isBookState.value) ({ stepChapter(-1) }) else null,
                    onNextChapter = if (isBookState.value) ({ stepChapter(1) }) else null,
                    sleepLeft = sleepLeftState.value,
                    onWordDoubleTap = { w -> askStress(w) },
                    onSearch = if (isBookState.value) ({ openSearch() }) else null,
                    onSleepTimer = { m ->
                        com.brahmadeo.supertonic.tts.utils.SleepTimer.set(this@PlaybackActivity, m)
                        sleepLeftState.value = com.brahmadeo.supertonic.tts.utils.SleepTimer.left(this@PlaybackActivity)
                    },
                    onCharactersClick = {
                        saveState()
                        startActivity(android.content.Intent(this@PlaybackActivity, CharactersActivity::class.java))
                    },
                    onPrevClick = { if (currentIndexState.intValue > 0) playFromIndex(currentIndexState.intValue - 1) },
                    onNextClick = {
                        if (currentIndexState.intValue < sentencesState.value.size - 1) playFromIndex(currentIndexState.intValue + 1)
                    },
                    onCancelExportClick = {
                        try { playbackService?.stop() } catch (e: Exception) {}
                        if (isExportingState.value) {
                            isExportingState.value = false
                            Toast.makeText(this@PlaybackActivity, "Audio saving cancelled", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                stressWordState.value?.let { w ->
                    com.brahmadeo.supertonic.tts.ui.StressDialog(
                        word = w,
                        initial = remember(w) {
                            com.brahmadeo.supertonic.tts.utils.LexiconManager.stressOf(this@PlaybackActivity, w).takeIf { it >= 0 }
                                ?: w.lowercase().indexOf('ё')
                        },
                        onListen = { stressed ->
                            startService(Intent(this@PlaybackActivity, PlaybackService::class.java)
                                .setAction(PlaybackService.ACTION_PREVIEW)
                                .putExtra("text", stressed).putExtra("voice", currentVoicePath).putExtra("speed", currentSpeed))
                        },
                        onSave = { stressed ->
                            com.brahmadeo.supertonic.tts.utils.LexiconManager.putStress(this@PlaybackActivity, w, stressed)
                            Toast.makeText(this@PlaybackActivity, getString(R.string.stress_saved, stressed.replace(Regex("\\+(.)"), "$1\u0301")), Toast.LENGTH_SHORT).show()
                            stressDone(saved = true)
                        },
                        onDismiss = { stressDone(saved = false) }
                    )
                }
            }
        }

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        val prefs0 = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        // the service moved on (the next chapter) while this screen was in the background
        val newer = prefs0.getLong("last_text_time", 0L) > textLoadedAt
        // only when the service really moved on (a newer text): a book just opened from the
        // library ("is_resume" + its own text) used to be replaced here by the old last_text
        if (newer) {
            val prefs = prefs0
            val newText = prefs.getString("last_text", "") ?: ""
            if (newText != currentText) {
                currentText = newText
                currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
                currentSpeed = prefs.getFloat("last_speed", 1.0f)
                currentSteps = prefs.getInt("last_steps", 5)
                currentLang = prefs.getString("last_lang", "en") ?: "en"
                currentIndexState.intValue = prefs.getInt("last_index", 0)
                textLoadedAt = prefs.getLong("last_text_time", 0L)
                setupList(currentText)
                if (playbackService?.isServiceActive != true) {
                    com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this, currentText)
                    PlaybackService.resumeIndex(this, currentText, sentencesState.value, currentSpeed)
                        ?.let { currentIndexState.intValue = it }
                }
            }
        }

        if (isBound && playbackService != null) {
            try {
                playbackService?.setListener(playbackListenerStub)
                val serviceIndex = playbackService?.getCurrentIndex() ?: -1
                if (serviceIndex != -1) {
                    currentIndexState.intValue = serviceIndex
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }

    private fun setupList(text: String) {
        val normalizer = TextNormalizer()
        val sentences = normalizer.splitIntoSentences(text, currentLang)
        textState.value = text
        // a chapter of the open book (seen before): contents and chapter arrows are offered
        com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this, text)
        isBookState.value = com.brahmadeo.supertonic.tts.utils.BookSession.scopeFor(this, text).isNotEmpty()
        sentencesState.value = sentences
    }

    private fun handlePlayPause() {
        try {
            if (isPlayingState.value) {
                // a real pause: the sound stops at once and goes on from the same word
                playbackService?.pause()
            } else if (isServiceActiveState.value) {
                playbackService?.play()
            } else {
                if (currentIndexState.intValue >= 0) {
                    playFromIndex(currentIndexState.intValue)
                } else {
                    startPlaybackFromIntent()
                }
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun handleStop() {
        try {
            playbackService?.stop()
        } catch (e: RemoteException) { }
        clearState()
        finish()
    }

    private fun startPlaybackFromIntent() {
        if (currentText.isEmpty()) return
        saveState()
        try {
            // continue where this text was left off (or from the start if it is new / finished)
            playbackService?.synthesizeAndPlay(currentText, currentLang, currentVoicePath, currentSpeed, currentSteps,
                com.brahmadeo.supertonic.tts.service.PlaybackService.RESUME_INDEX)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun playFromIndex(index: Int) {
        if (currentText.isEmpty()) return
        saveState()
        try {
            playbackService?.synthesizeAndPlay(currentText, currentLang, currentVoicePath, currentSpeed, currentSteps, index)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    /** Long press on a phrase: "who says this?" — saved for the book and applied right away. */
    private fun showSpeakerDialog(index: Int) {
        val text = currentText
        val sentences = sentencesState.value
        if (index !in sentences.indices) return
        val ctx = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val scope = com.brahmadeo.supertonic.tts.utils.BookSession.scopeFor(ctx, text)
            val roster = com.brahmadeo.supertonic.tts.utils.BookRoster.cached(ctx, scope)
            val (result, line, now) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val r = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.applyAliases(
                    com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.analyze(text,
                        com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Prior(roster?.genders ?: emptyMap(),
                            com.brahmadeo.supertonic.tts.utils.SpeakerOverrides.all(ctx, scope))),
                    com.brahmadeo.supertonic.tts.utils.CharacterVoices.aliases(ctx, scope))
                val rg = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(text, sentences)[index]
                // the whole line (a span) this phrase belongs to
                val span = if (rg == null) null else r.spans.firstOrNull { it.start <= rg.last && it.end > rg.first &&
                    it.role != com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.NARRATOR }
                Triple(r, span?.let { text.substring(it.start, it.end) }, span?.speaker)
            }
            if (line == null) {
                android.widget.Toast.makeText(ctx, getString(R.string.speaker_no_line), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val hero = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.HERO
            val names = (result.characters.map { it.name } + (roster?.ranking ?: emptyList())).distinct()
            val labels = names.map { if (it == hero) getString(R.string.characters_hero) else it } +
                listOf(getString(R.string.speaker_unknown), getString(R.string.speaker_narrator))
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.speaker_who) + "\n«" + line.trim().take(80) + "»\n" +
                    getString(R.string.speaker_now, when (now) { null -> getString(R.string.speaker_unknown)
                        hero -> getString(R.string.characters_hero); else -> now }))
                .setItems(labels.toTypedArray()) { _, which ->
                    val choice = when {
                        which < names.size -> names[which]
                        which == names.size -> com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.FORCE_UNKNOWN
                        else -> com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.FORCE_NARRATOR
                    }
                    com.brahmadeo.supertonic.tts.utils.SpeakerOverrides.set(ctx, scope, line, choice)
                    playFromIndex(index)   // apply right away
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun saveState() {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putString("last_text", currentText)
                .putString("last_voice_path", currentVoicePath)
                .putFloat("last_speed", currentSpeed)
                .putInt("last_steps", currentSteps)
                .putString("last_lang", currentLang)
                .putBoolean("is_playing", true)
                .putLong("last_text_time", System.currentTimeMillis().also { textLoadedAt = it })
        }
    }

    private fun updateIndexState(index: Int) {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putInt("last_index", index)
        }
    }

    private fun clearState() {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putBoolean("is_playing", false)
        }
    }

    private fun restoreState() {
        try {
            if (playbackService?.isServiceActive == false) {
                 playbackListenerStub.onStateChanged(false, true, false)
            }
        } catch (e: RemoteException) { }
    }

    private fun startExport() {
        if (currentText.isEmpty()) {
            Toast.makeText(this, "No text to save", Toast.LENGTH_SHORT).show()
            return
        }

        exportCurrentState.intValue = 0
        exportTotalState.intValue = sentencesState.value.size
        isExportingState.value = true

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Supertonic_TTS_$timestamp.wav"
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val appDir = File(musicDir, "Supertonic Audio")
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, filename)

        try {
            playbackService?.exportAudio(currentText, currentLang, currentVoicePath, currentSpeed, currentSteps, file.absolutePath)
        } catch (e: RemoteException) {
            e.printStackTrace()
            isExportingState.value = false
            Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                playbackService?.removeListener(playbackListenerStub)
            } catch (e: Exception) { }
            unbindService(connection)
            isBound = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
