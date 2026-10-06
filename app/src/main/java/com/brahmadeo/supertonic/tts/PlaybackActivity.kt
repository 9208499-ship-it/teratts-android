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
                    val isActive = playbackService?.isServiceActive == true
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
        if (intent.getBooleanExtra("is_resume", false)) {
            // the place reached by the voice in this text, a minute back — shown and lit before "Play"
            com.brahmadeo.supertonic.tts.utils.BookSession.adopt(this, currentText)
            PlaybackService.resumeIndex(this, currentText, sentencesState.value, currentSpeed)?.let {
                currentIndexState.intValue = it
            }
        }

        setContent {
            SupertonicTheme(voiceFile = currentVoicePath) {
                PlaybackScreen(
                    sentences = sentencesState.value,
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
        if (intent.getBooleanExtra("is_resume", false) || newer) {
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
        sentencesState.value = sentences
    }

    private fun handlePlayPause() {
        try {
            if (isPlayingState.value) {
                playbackService?.stop() // Or pause if implemented
            } else if (isServiceActiveState.value) {
                playFromIndex(currentIndexState.intValue)
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
            val (result, line) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val r = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.applyAliases(
                    com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.analyze(text,
                        com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Prior(roster?.genders ?: emptyMap(),
                            com.brahmadeo.supertonic.tts.utils.SpeakerOverrides.all(ctx, scope))),
                    com.brahmadeo.supertonic.tts.utils.CharacterVoices.aliases(ctx, scope))
                val rg = com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(text, sentences)[index]
                // the whole line (a span) this phrase belongs to
                val span = if (rg == null) null else r.spans.firstOrNull { it.start <= rg.last && it.end > rg.first &&
                    it.role != com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role.NARRATOR }
                r to span?.let { text.substring(it.start, it.end) }
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
                .setTitle(getString(R.string.speaker_who) + "\n«" + line.trim().take(80) + "»")
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
