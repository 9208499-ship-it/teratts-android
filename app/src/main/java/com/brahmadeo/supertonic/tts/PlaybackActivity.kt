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

class PlaybackActivity : ComponentActivity() {

    private var playbackService: IPlaybackService? = null
    private var isBound = false

    // Reactive State
    private var sentencesState = mutableStateOf<List<String>>(emptyList())
    private var currentIndexState = mutableIntStateOf(-1)
    private var isPlayingState = mutableStateOf(false)
    private var isServiceActiveState = mutableStateOf(false)
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

        override fun onTextChanged() {
            // the next chapter started by itself: show its text
            runOnUiThread {
                val newText = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("last_text", "") ?: ""
                if (newText.isNotEmpty() && newText != currentText) {
                    currentText = newText
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
                    } else {
                        // Not playing in service, but user wants to resume: 
                        // Start playback from the saved index
                        playFromIndex(currentIndexState.intValue)
                    }
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

        if (intent.getBooleanExtra("is_resume", false) && currentText.isEmpty()) {
             val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
             currentText = prefs.getString("last_text", "") ?: ""
             currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
             currentSpeed = prefs.getFloat("last_speed", 1.0f)
             currentSteps = prefs.getInt("last_steps", 5)
             currentLang = prefs.getString("last_lang", "en") ?: "en"
             currentIndexState.intValue = prefs.getInt("last_index", 0)
        }

        setupList(currentText)

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
        if (intent.getBooleanExtra("is_resume", false)) {
            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val newText = prefs.getString("last_text", "") ?: ""
            if (newText != currentText) {
                currentText = newText
                currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
                currentSpeed = prefs.getFloat("last_speed", 1.0f)
                currentSteps = prefs.getInt("last_steps", 5)
                currentLang = prefs.getString("last_lang", "en") ?: "en"
                currentIndexState.intValue = prefs.getInt("last_index", 0)
                setupList(currentText)
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

    private fun saveState() {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putString("last_text", currentText)
                .putString("last_voice_path", currentVoicePath)
                .putFloat("last_speed", currentSpeed)
                .putInt("last_steps", currentSteps)
                .putString("last_lang", currentLang)
                .putBoolean("is_playing", true)
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
