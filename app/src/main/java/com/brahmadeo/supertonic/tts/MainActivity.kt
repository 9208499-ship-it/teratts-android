package com.brahmadeo.supertonic.tts

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.service.IPlaybackListener
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.ui.DownloadScreen
import com.brahmadeo.supertonic.tts.ui.MainScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.EbookManager
import com.brahmadeo.supertonic.tts.utils.EbookParser
import com.brahmadeo.supertonic.tts.utils.HistoryManager
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.brahmadeo.supertonic.tts.utils.QueueManager
import com.brahmadeo.supertonic.tts.viewmodel.MainViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var ebookParser: EbookParser

    // Data — Supertonic 3 supports 31 languages + an "na" fallback for unknown.
    private val languages = mapOf(
        R.string.lang_russian to "ru",
        R.string.lang_english to "en"
    )

    // Service
    private var playbackService: IPlaybackService? = null
    private var isBound = false

    private val playbackListener = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                viewModel.miniPlayerIsPlaying.value = isPlaying
                viewModel.isSynthesizing.value = isSynthesizing
                if (hasContent || isSynthesizing) {
                    viewModel.showMiniPlayer.value = true
                    val lastText = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("last_text", "")
                    if (!lastText.isNullOrEmpty()) {
                        viewModel.miniPlayerTitle.value = lastText
                    }
                } else {
                    viewModel.showMiniPlayer.value = false
                }
            }
        }
        override fun onProgress(current: Int, total: Int) { }
        override fun onPlaybackStopped() {
            runOnUiThread {
                viewModel.showMiniPlayer.value = false
                viewModel.miniPlayerIsPlaying.value = false
            }
        }
        override fun onExportComplete(success: Boolean, path: String) { }
        override fun onTextChanged() {
            // the next chapter started: the input follows, so "Synthesize" does not go back to the old one
            runOnUiThread {
                val t = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("last_text", "") ?: ""
                if (t.isNotEmpty()) viewModel.inputText.value = t
            }
        }
        override fun onSpokenPosition(index: Int, fraction: Float) { }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            isBound = true
            try {
                playbackService?.setListener(playbackListener)
                checkResumeState()
            } catch (e: Exception) { e.printStackTrace() }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val ebookLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val localPath = EbookManager.importBook(this, it)
            if (localPath != null) {
                val intent = Intent(this, EbookOutlineActivity::class.java).apply {
                    putExtra(EbookOutlineActivity.EXTRA_URI, localPath)
                }
                ebookOutlineLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Failed to import book", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val ebookOutlineLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("MainActivity", "ebookOutlineLauncher result: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(EbookOutlineActivity.EXTRA_TEXT)
            Log.d("MainActivity", "Received text length: ${text?.length ?: 0}")
            if (!text.isNullOrEmpty()) {
                // Reset state before loading new ebook text
                viewModel.inputText.value = ""
                val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                startService(stopIntent)
                
                viewModel.inputText.value = prepareTextForTts(text, viewModel.currentLang.value)
                Toast.makeText(this, "Chapter loaded", Toast.LENGTH_SHORT).show()
                // "Continue where I stopped" in the outline: start reading at once (it resumes at the place)
                if (result.data?.getBooleanExtra("auto_play", false) == true) generateAndPlay(viewModel.inputText.value)
            } else {
                Log.e("MainActivity", "Received empty or null text from ebook activity")
            }
        }
    }

    private fun prepareTextForTts(text: String?, lang: String): String {
        if (text.isNullOrEmpty()) return ""
        val trimmed = text.trim()
        
        // Append " ." to prevent diffusion model from cutting off abruptly at the end
        // RESTRICTED for Korean
        if (lang.lowercase().startsWith("ko")) {
            return trimmed
        }
        
        return if (trimmed.endsWith(" .")) trimmed else "$trimmed ."
    }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                viewModel.inputText.value = selectedText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(this)

        loadPreferences()
        checkNotificationPermission()

        val bindIntent = Intent(this, PlaybackService::class.java)
        bindService(bindIntent, connection, BIND_AUTO_CREATE)

        ebookParser = EbookParser(this)
        LexiconManager.load(this)
        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.load(this)
        QueueManager.initialize(this)

        // Wipe v1/v2 leftovers from prior versions of the app so we don't waste storage.
        AssetManager.cleanupOldVersions(this)

        // Single unified model (Supertonic 3): download on first launch, initialize otherwise.
        if (!AssetManager.isReady(this)) {
            startDownload()
        } else {
            initializeEngine()
        }

        handleIntent(intent)

        // Launching the app opens the player with the last book at its place (paused);
        // the settings screen is one "Back" away.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN && AssetManager.isReady(this) &&
            !getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("last_text", "").isNullOrEmpty()) {
            startActivity(Intent(this, PlaybackActivity::class.java)
                .putExtra("is_resume", true)
                .putExtra("no_autoplay", true))
        }

        setContent {
            SupertonicTheme(voiceFile = viewModel.selectedVoiceFile.value) {
                if (viewModel.isDownloading.value) {
                    DownloadScreen(
                        status = viewModel.downloadStatus.value,
                        progress = viewModel.downloadProgress.floatValue,
                        error = viewModel.downloadError.value,
                        onRetry = { startDownload() }
                    )
                } else {
                    if (viewModel.showQueueDialog.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.showQueueDialog.value = false },
                            title = { Text(getString(R.string.playback_active_title)) },
                            text = { Text(getString(R.string.playback_active_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    addToQueue(viewModel.queueDialogText)
                                    viewModel.showQueueDialog.value = false
                                }) { Text(getString(R.string.add_to_queue)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    playNow(viewModel.queueDialogText)
                                    viewModel.showQueueDialog.value = false
                                }) { Text(getString(R.string.play_now)) }
                            }
                        )
                    }

                    if (viewModel.showModelDeleteDialog.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.showModelDeleteDialog.value = false },
                            title = { Text(getString(R.string.model_delete_title)) },
                            text = { Text(getString(R.string.model_delete_message)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        AssetManager.delete(this@MainActivity)
                                        viewModel.showModelDeleteDialog.value = false
                                        Toast.makeText(this@MainActivity, getString(R.string.model_deleted_msg), Toast.LENGTH_SHORT).show()
                                        // Restart download flow so the app remains usable.
                                        startDownload()
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text(getString(R.string.delete)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.showModelDeleteDialog.value = false }) { Text(getString(R.string.cancel)) }
                            }
                        )
                    }

                    // Get localized placeholder and languages
                    val placeholder = remember(viewModel.currentLang.value) {
                        getLocalizedResource(this@MainActivity, viewModel.currentLang.value, R.string.default_input_text)
                    }
                    val localizedLanguages = remember(viewModel.currentLang.value) {
                        languages.mapKeys { getLocalizedResource(this@MainActivity, viewModel.currentLang.value, it.key) }
                    }

                    MainScreen(
                        inputText = viewModel.inputText.value,
                        onInputTextChange = { 
                            viewModel.inputText.value = it
                            saveStringPref("last_text", it)
                        },
                        placeholderText = placeholder,
                        isInitializing = viewModel.isInitializing.value,
                        isSynthesizing = viewModel.isSynthesizing.value,
                        onSynthesizeClick = {
                            val textToPlay = viewModel.inputText.value.ifEmpty { placeholder }
                            generateAndPlay(textToPlay)
                        },

                        languages = localizedLanguages,
                        currentLangCode = viewModel.currentLang.value,
                        onLangChange = {
                            viewModel.currentLang.value = it
                            saveStringPref("selected_lang", it)
                            val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET_ENGINE" }
                            startService(resetIntent)
                        },

                        voices = viewModel.voiceFiles,
                        selectedVoiceFile = viewModel.selectedVoiceFile.value,
                        onVoiceChange = {
                            if (viewModel.selectedVoiceFile.value != it) {
                                viewModel.selectedVoiceFile.value = it
                                saveStringPref("selected_voice", it)
                                // no engine reload: the voice goes with every phrase, the models stay the same
                            }
                        },

                        isMixingEnabled = viewModel.isMixingEnabled.value,
                        onMixingEnabledChange = { 
                            viewModel.isMixingEnabled.value = it
                            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
                                putBoolean(
                                    "is_mixing_enabled",
                                    it
                                )
                            }
                        },
                        selectedVoiceFile2 = viewModel.selectedVoiceFile2.value,
                        onVoice2Change = {
                            viewModel.selectedVoiceFile2.value = it
                            saveStringPref("selected_voice_2", it)
                        },
                        mixAlpha = viewModel.mixAlpha.floatValue,
                        onMixAlphaChange = { 
                            viewModel.mixAlpha.floatValue = it
                            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
                                putFloat(
                                    "mix_alpha",
                                    it
                                )
                            }
                        },

                        onStopClick = { try { playbackService?.stop() } catch (e: Exception) { } },
                        speed = viewModel.currentSpeed.floatValue,
                        onSpeedChange = { viewModel.currentSpeed.floatValue = it },
                        steps = viewModel.currentSteps.intValue,
                        onStepsChange = {
                            viewModel.currentSteps.intValue = it
                            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
                                putInt(
                                    "diffusion_steps",
                                    it
                                )
                            }
                        },

                        isAdvancedNormalizationEnabled = viewModel.isAdvancedNormalizationEnabled.value,
                        onAdvancedNormalizationEnabledChange = {
                            viewModel.isAdvancedNormalizationEnabled.value = it
                            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
                                putBoolean(
                                    "is_advanced_normalization",
                                    it
                                )
                            }
                        },

                        onResetClick = {
                            viewModel.inputText.value = ""
                            val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                            startService(stopIntent)
                        },
                        onSavedAudioClick = { startActivity(Intent(this, SavedAudioActivity::class.java)) },
                        onHistoryClick = { historyLauncher.launch(Intent(this, HistoryActivity::class.java)) },
                        onQueueClick = { startActivity(Intent(this, QueueActivity::class.java)) },
                        onLexiconClick = { startActivity(Intent(this, LexiconActivity::class.java)) },
                        onTtsSettingsClick = { openSystemTtsSettings() },
                        onDeleteModelClick = { viewModel.showModelDeleteDialog.value = true },
                        onOpenEbookClick = {
                            try {
                                if (EbookManager.getRecentBooks(this).isEmpty()) {
                                    ebookLauncher.launch(arrayOf("*/*"))
                                } else {
                                    val intent = Intent(this, EbookLibraryActivity::class.java)
                                    ebookOutlineLauncher.launch(intent)
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to open ebook library", e)
                                ebookLauncher.launch(arrayOf("*/*"))
                            }
                        },

                        canResume = viewModel.canResume.value,
                        onResumeClick = {
                            val intent = Intent(this, PlaybackActivity::class.java)
                            intent.putExtra("is_resume", true)
                            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            startActivity(intent)
                        },

                        showMiniPlayer = viewModel.showMiniPlayer.value,
                        miniPlayerTitle = viewModel.miniPlayerTitle.value,
                        miniPlayerIsPlaying = viewModel.miniPlayerIsPlaying.value,
                        onMiniPlayerClick = {
                            val intent = Intent(this, PlaybackActivity::class.java)
                            intent.putExtra("is_resume", true)
                            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            startActivity(intent)
                        },
                        onMiniPlayerPlayPauseClick = {
                             if (playbackService?.isServiceActive == true) {
                                try {
                                    if (viewModel.miniPlayerIsPlaying.value) playbackService?.pause() else playbackService?.play()
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkResumeState()
        startKeepAlive()
        offerAccentDictionary()
    }

    /**
     * Once, when the models are in place but no accent dictionary is installed:
     * offer the recommended (silero) dictionary instead of hiding it two menus deep.
     */
    private fun offerAccentDictionary() {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("dict_offer_shown", false)) return
        if (!com.brahmadeo.supertonic.tts.utils.AssetManager.isReady(this)) return
        if (com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.isInstalled(this)) return
        prefs.edit().putBoolean("dict_offer_shown", true).apply()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_offer_title)
            .setMessage(getString(R.string.dict_offer_msg,
                com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.SILERO_DICT_SIZE_MB))
            .setPositiveButton(R.string.dict_offer_download) { _, _ ->
                startActivity(Intent(this, LexiconActivity::class.java)
                    .putExtra(LexiconActivity.EXTRA_AUTO_DOWNLOAD_URL,
                        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.SILERO_DICT_URL)
                    .putExtra(LexiconActivity.EXTRA_AUTO_DOWNLOAD_NAME,
                        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.SILERO_DICT_NAME))
            }
            .setNegativeButton(R.string.dict_offer_later, null)
            .show()
    }

    /**
     * Keep the TTS engine in the foreground (SmartVoice-style ongoing notification).
     * Started from the open app it is always allowed; from the background Huawei /
     * Android 12+ may refuse, and with the screen off the engine would then be
     * throttled. Turned off with the button in the notification.
     */
    private fun startKeepAlive() {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean(com.brahmadeo.supertonic.tts.service.SupertonicTextToSpeechService.PREF_KEEP_ALIVE, true)) return
        try {
            val intent = android.content.Intent(this, com.brahmadeo.supertonic.tts.service.SupertonicTextToSpeechService::class.java)
                .setAction(com.brahmadeo.supertonic.tts.service.SupertonicTextToSpeechService.ACTION_KEEP_ALIVE)
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            android.util.Log.w("TeraTTS", "keep-alive start failed: ${e.message}")
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        viewModel.inputText.value = prefs.getString("last_text", "") ?: ""
        viewModel.currentLang.value = prefs.getString("selected_lang", MainViewModel.DEFAULT_LANG) ?: MainViewModel.DEFAULT_LANG
        viewModel.selectedVoiceFile.value = prefs.getString("selected_voice", MainViewModel.DEFAULT_VOICE) ?: MainViewModel.DEFAULT_VOICE
        viewModel.selectedVoiceFile2.value = prefs.getString("selected_voice_2", MainViewModel.DEFAULT_VOICE_2) ?: MainViewModel.DEFAULT_VOICE_2
        // voice mixing is no longer offered: make sure it is off
        viewModel.isMixingEnabled.value = false
        if (prefs.getBoolean("is_mixing_enabled", false)) prefs.edit().putBoolean("is_mixing_enabled", false).apply()
        viewModel.mixAlpha.floatValue = prefs.getFloat("mix_alpha", 0.5f)
        viewModel.currentSpeed.floatValue = prefs.getFloat("speed", MainViewModel.DEFAULT_SPEED)
        viewModel.currentSteps.intValue = prefs.getInt("diffusion_steps", MainViewModel.DEFAULT_STEPS)
        viewModel.isAdvancedNormalizationEnabled.value = prefs.getBoolean("is_advanced_normalization", false)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getLocalizedResource(context: Context, lang: String, resId: Int): String {
        val locale = java.util.Locale.forLanguageTag(lang)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocales(android.os.LocaleList(locale))
        val localizedContext = context.createConfigurationContext(config)
        return localizedContext.resources.getString(resId)
    }

    private fun saveStringPref(key: String, value: String) {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit(commit = true) {
            putString(key, value)
        }
    }

    private fun openSystemTtsSettings() {
        // Android exposes the dedicated TTS settings screen via an
        // undocumented action. If the device's launcher activity isn't
        // present (some custom OEM ROMs), fall back to the generic
        // Accessibility settings — the user can navigate to TTS from there.
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, getString(R.string.tts_settings_unavailable), Toast.LENGTH_LONG).show()
    }

    private fun startDownload() {
        viewModel.isDownloading.value = true
        viewModel.downloadError.value = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AssetManager.download(this@MainActivity) { status, progress ->
                    runOnUiThread {
                        viewModel.downloadStatus.value = status
                        viewModel.downloadProgress.floatValue = progress
                    }
                }
                withContext(Dispatchers.Main) {
                    viewModel.isDownloading.value = false
                    initializeEngine()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.downloadError.value = e.message ?: "Unknown error"
                    Log.e("MainActivity", "Download failed", e)
                }
            }
        }
    }

    private fun initializeEngine() {
        val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"

        if (SupertonicTTS.isInitialized(modelPath)) {
            Log.i("MainActivity", "Engine already initialized, skipping reload")
            viewModel.isInitializing.value = false
            setupVoicesMap(viewModel.currentLang.value)
            return
        }

        viewModel.isInitializing.value = true

        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                setupVoicesMap(viewModel.currentLang.value)
            }

            com.brahmadeo.supertonic.tts.utils.AssetManager.ensureHomosolver(applicationContext)
            if (SupertonicTTS.initialize(modelPath, libPath)) {
                withContext(Dispatchers.Main) {
                    viewModel.isInitializing.value = false
                }
            }
        }
    }

    private fun setupVoicesMap(lang: String) {
        viewModel.voiceFiles.clear()
        val voiceResources = emptyMap<String, Int>() // TeraTTS voices are listed by file name (ru_f1, ru_m5, …)

        voiceResources.forEach { (filename, resId) ->
            viewModel.voiceFiles[getLocalizedResource(this, lang, resId)] = filename
        }

        // Check dynamic dir for default listing
        val voiceDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        if (voiceDir.exists()) {
            val files = voiceDir.listFiles { _, name -> name.endsWith(".json") }
            files?.forEach { file ->
                if (!voiceResources.containsKey(file.name)) {
                    val friendlyName = file.name.removeSuffix(".json")
                    viewModel.voiceFiles[friendlyName] = file.name
                }
            }
        }
    }

    private fun generateAndPlay(text: String) {
        if (!AssetManager.isReady(this)) {
            startDownload()
            return
        }

        if (viewModel.isInitializing.value) return

        val voiceDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        var stylePath = File(voiceDir, viewModel.selectedVoiceFile.value).absolutePath
        if (!File(stylePath).exists()) {
             startDownload()
             return
        }

        if (viewModel.isMixingEnabled.value) {
            val stylePath2 = File(voiceDir, viewModel.selectedVoiceFile2.value).absolutePath
            if (File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;${viewModel.mixAlpha.floatValue}"
            }
        }
        
        val v1Name = viewModel.voiceFiles.entries.find { it.value == viewModel.selectedVoiceFile.value }?.key ?: "Voice 1"
        val v2Name = viewModel.voiceFiles.entries.find { it.value == viewModel.selectedVoiceFile2.value }?.key ?: "Voice 2"
        val voiceName = if (viewModel.isMixingEnabled.value) "Mixed: $v1Name + $v2Name" else v1Name

        HistoryManager.saveItem(this, text, voiceName)

        try {
            if (playbackService?.isServiceActive == true) {
                viewModel.queueDialogText = text
                viewModel.showQueueDialog.value = true
            } else {
                launchPlaybackActivity(text, stylePath)
            }
        } catch (_: Exception) {
            launchPlaybackActivity(text, stylePath)
        }
    }

    private fun addToQueue(text: String) {
        if (!AssetManager.isReady(this)) {
            startDownload()
            return
        }

        if (viewModel.isInitializing.value) return

        val voiceDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        var stylePath = File(voiceDir, viewModel.selectedVoiceFile.value).absolutePath
        if (viewModel.isMixingEnabled.value) {
            val stylePath2 = File(voiceDir, viewModel.selectedVoiceFile2.value).absolutePath
            stylePath = "$stylePath;$stylePath2;${viewModel.mixAlpha.floatValue}"
        }

        try {
            playbackService?.addToQueue(
                text,
                viewModel.currentLang.value,
                stylePath,
                viewModel.currentSpeed.floatValue,
                viewModel.currentSteps.intValue,
                0
            )
            Toast.makeText(this, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playNow(text: String) {
        if (!AssetManager.isReady(this)) {
            startDownload()
            return
        }

        if (viewModel.isInitializing.value) return

        val voiceDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        var stylePath = File(voiceDir, viewModel.selectedVoiceFile.value).absolutePath
        if (viewModel.isMixingEnabled.value) {
            val stylePath2 = File(voiceDir, viewModel.selectedVoiceFile2.value).absolutePath
            stylePath = "$stylePath;$stylePath2;${viewModel.mixAlpha.floatValue}"
        }
        launchPlaybackActivity(text, stylePath)
    }

    private fun launchPlaybackActivity(text: String, stylePath: String) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TEXT, text)
            putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
            putExtra(PlaybackActivity.EXTRA_SPEED, viewModel.currentSpeed.floatValue)
            putExtra(PlaybackActivity.EXTRA_STEPS, viewModel.currentSteps.intValue)
            putExtra(PlaybackActivity.EXTRA_LANG, viewModel.currentLang.value)
        }
        startActivity(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                viewModel.inputText.value = prepareTextForTts(sharedText, viewModel.currentLang.value)
            }
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.getQueryParameter("text")
            if (!text.isNullOrEmpty()) {
                viewModel.inputText.value = prepareTextForTts(text, viewModel.currentLang.value)
            }
        }
    }

    private fun checkResumeState() {
        if (viewModel.isDownloading.value) return

        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val lastText = prefs.getString("last_text", null)
        val isPlayingPref = prefs.getBoolean("is_playing", false)

        if (lastText.isNullOrEmpty()) {
            viewModel.canResume.value = false
            return
        }

        // If service is already active, we just sync the mini player state
        try {
            if (playbackService != null && playbackService?.isServiceActive == true) {
                runOnUiThread {
                    viewModel.showMiniPlayer.value = true
                    viewModel.miniPlayerTitle.value = lastText
                    viewModel.canResume.value = false // Mini player handles it
                }
                return
            }
        } catch (e: Exception) { }

        viewModel.canResume.value = isPlayingPref
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                playbackService?.removeListener(playbackListener)
            } catch (_: Exception) { }
            unbindService(connection)
            isBound = false
        }
    }
}
