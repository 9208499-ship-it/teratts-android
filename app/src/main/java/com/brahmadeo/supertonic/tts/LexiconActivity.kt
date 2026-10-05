package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.ui.LexiconEditDialog
import com.brahmadeo.supertonic.tts.ui.LexiconScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.brahmadeo.supertonic.tts.utils.LexiconItem
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LexiconActivity : ComponentActivity() {

    private val rulesState = mutableStateOf<List<LexiconItem>>(emptyList())
    private val accentDictBannerState = mutableStateOf<com.brahmadeo.supertonic.tts.ui.AccentDictBanner?>(null)
    private val syncLoadState = mutableStateOf(false)
    private val lexiconEnabledState = mutableStateOf(true)
    private val tightQuestionState = mutableStateOf(false)
    private val doubleMarksState = mutableStateOf(false)
    private val tightEllipsisState = mutableStateOf(false)
    private val tightCommasPeriodsState = mutableStateOf(false)
    private val forceSpacePunctState = mutableStateOf(false)
    private val chunkModeState = mutableStateOf(com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.ChunkMode.DEFAULT)
    private val preRollEnabledState = mutableStateOf(false)
    private val preRollSentencesState = mutableStateOf(2)
    private var playbackService: IPlaybackService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { performImport(it) }
    }

    private val importAccentDictLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { performImportAccentDict(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Load initial rules
        refreshRules()

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)

        setContent {
            SupertonicTheme {
                var showEditDialog by remember { mutableStateOf(false) }
                var editingItem by remember { mutableStateOf<LexiconItem?>(null) }

                if (showEditDialog) {
                    LexiconEditDialog(
                        item = editingItem,
                        onDismiss = { showEditDialog = false },
                        onSave = { term, replacement, ignoreCase, isRegex ->
                            saveRule(editingItem, term, replacement, ignoreCase, isRegex)
                            showEditDialog = false
                        },
                        onTest = { replacement ->
                            testPronunciation(replacement)
                        }
                    )
                }

                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val selectedLang = remember { prefs.getString("selected_lang", "ru") ?: "ru" }
                val canDownload = remember { AccentDictionaryManager.hasPrebuiltFor(selectedLang) }

                var showProgress by remember { mutableStateOf(false) }
                var progressLabel by remember { mutableStateOf("") }
                var progressFraction by remember { mutableFloatStateOf(0f) }
                // null = chooser hidden; otherwise carries the format filter
                // (BINARY/TEXT) selected from the overflow menu.
                var chooserFormat by remember {
                    mutableStateOf<AccentDictionaryManager.DictFormat?>(null)
                }

                val connectingLabel = getString(R.string.connecting)
                fun startDownload(url: String, source: String) {
                    showProgress = true
                    progressLabel = connectingLabel
                    progressFraction = 0f
                    CoroutineScope(Dispatchers.IO).launch {
                        val count = AccentDictionaryManager.downloadPrebuilt(
                            this@LexiconActivity, url, source
                        ) { soFar, total ->
                            runOnUiThread {
                                progressFraction = if (total > 0) (soFar.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                                progressLabel = if (total > 0) {
                                    "%.1f / %.1f MB".format(soFar / 1_048_576.0, total / 1_048_576.0)
                                } else {
                                    "%.1f MB".format(soFar / 1_048_576.0)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            showProgress = false
                            if (count > 0) {
                                refreshRules()
                                MaterialAlertDialogBuilder(this@LexiconActivity)
                                    .setTitle(R.string.dict_loaded_title)
                                    .setMessage(getString(R.string.dict_loaded_msg_fmt, count))
                                    .setPositiveButton(R.string.ok, null)
                                    .show()
                            } else {
                                // Show the *specific* reason in a persistent dialog
                                // so the user knows what went wrong (OOM with Full
                                // on small phones is the most common case here).
                                MaterialAlertDialogBuilder(this@LexiconActivity)
                                    .setTitle(R.string.download_failed_title)
                                    .setMessage(downloadErrorMessage(count))
                                    .setPositiveButton(R.string.ok, null)
                                    .show()
                            }
                        }
                    }
                }

                val activeFormat = chooserFormat
                if (activeFormat != null) {
                    // Pull a fresh per-format list; remembering by activeFormat
                    // ensures the dialog reshows the correct subset when the
                    // user toggles between the two menu items.
                    val options = remember(activeFormat) {
                        AccentDictionaryManager.prebuiltOptionsFor(selectedLang, activeFormat)
                    }
                    val title = when (activeFormat) {
                        AccentDictionaryManager.DictFormat.BINARY ->
                            getString(R.string.download_chooser_binary_title)
                        AccentDictionaryManager.DictFormat.TEXT ->
                            getString(R.string.download_chooser_text_title)
                    }
                    AlertDialog(
                        onDismissRequest = { chooserFormat = null },
                        title = { Text(title) },
                        text = {
                            Column {
                                options.forEach { opt ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                chooserFormat = null
                                                startDownload(opt.url, getString(R.string.russian_source_fmt, opt.displayName))
                                            }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(end = 12.dp)) {
                                            Text(opt.displayName, style = MaterialTheme.typography.titleMedium)
                                            Text(opt.subtitle, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { chooserFormat = null }) {
                                Text(getString(R.string.cancel))
                            }
                        }
                    )
                }

                if (showProgress) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(getString(R.string.download_progress_title)) },
                        text = {
                            Column {
                                Text(progressLabel)
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {}
                    )
                }

                LexiconScreen(
                    rules = rulesState.value,
                    accentDictBanner = accentDictBannerState.value,
                    canDownloadAccentDict = canDownload,
                    syncLoadEnabled = syncLoadState.value,
                    lexiconEnabled = lexiconEnabledState.value,
                    tightQuestionExclamation = tightQuestionState.value,
                    strengthenIntonation = doubleMarksState.value,
                    tightEllipsis = tightEllipsisState.value,
                    tightCommasAndPeriods = tightCommasPeriodsState.value,
                    forceSpaceBeforePunctuation = forceSpacePunctState.value,
                    chunkMode = chunkModeState.value,
                    preRollEnabled = preRollEnabledState.value,
                    preRollSentences = preRollSentencesState.value,
                    onBackClick = { finish() },
                    onImportClick = { importLauncher.launch("application/json") },
                    onExportClick = { performExport() },
                    onImportAccentDictClick = { importAccentDictLauncher.launch("*/*") },
                    onDownloadAccentDictTextClick = {
                        chooserFormat = AccentDictionaryManager.DictFormat.TEXT
                    },
                    onDownloadAccentDictBinaryClick = {
                        chooserFormat = AccentDictionaryManager.DictFormat.BINARY
                    },
                    onClearAccentDictClick = { clearAccentDict() },
                    onSyncLoadToggle = { enabled ->
                        AccentDictionaryManager.setSyncLoadEnabled(this@LexiconActivity, enabled)
                        syncLoadState.value = enabled
                    },
                    onLexiconToggle = { enabled ->
                        LexiconManager.setEnabled(this@LexiconActivity, enabled)
                        lexiconEnabledState.value = enabled
                    },
                    onTightQuestionToggle = { enabled ->
                        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setTightQuestionExclamation(this@LexiconActivity, enabled)
                        tightQuestionState.value = enabled
                    },
                    onDoubleMarksToggle = { enabled ->
                        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setStrengthenIntonation(this@LexiconActivity, enabled)
                        doubleMarksState.value = enabled
                    },
                    onTightEllipsisToggle = { enabled ->
                        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setTightEllipsis(this@LexiconActivity, enabled)
                        tightEllipsisState.value = enabled
                    },
                    onTightCommasPeriodsToggle = { enabled ->
                        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setTightCommasAndPeriods(this@LexiconActivity, enabled)
                        tightCommasPeriodsState.value = enabled
                    },
                    onForceSpaceBeforePunctToggle = { enabled ->
                        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setForceSpaceBeforePunctuation(this@LexiconActivity, enabled)
                        forceSpacePunctState.value = enabled
                    },
                    onChunkModeChange = { mode ->
                        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.setChunkMode(this@LexiconActivity, mode)
                        chunkModeState.value = mode
                    },
                    onPreRollToggle = { enabled ->
                        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.setPreRollEnabled(this@LexiconActivity, enabled)
                        preRollEnabledState.value = enabled
                    },
                    onPreRollSentencesChange = { count ->
                        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.setPreRollSentences(this@LexiconActivity, count)
                        preRollSentencesState.value = com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.preRollSentences
                    },
                    onAddClick = {
                        editingItem = null
                        showEditDialog = true
                    },
                    onEditClick = { item ->
                        editingItem = item
                        showEditDialog = true
                    },
                    onDeleteClick = { item ->
                        deleteRule(item)
                    }
                )
            }
        }
    }

    private fun refreshRules() {
        rulesState.value = LexiconManager.load(this)
        AccentDictionaryManager.load(this)
        val meta = AccentDictionaryManager.getMetadata(this)
        accentDictBannerState.value = meta?.let {
            com.brahmadeo.supertonic.tts.ui.AccentDictBanner(
                source = it.source,
                entries = it.entries,
                sizeBytes = it.sizeBytes
            )
        }
        syncLoadState.value = AccentDictionaryManager.isSyncLoadEnabled()
        lexiconEnabledState.value = LexiconManager.isEnabled()
        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.load(this)
        tightQuestionState.value = com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.tightQuestionExclamation
        doubleMarksState.value = com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.strengthenIntonation
        tightEllipsisState.value = com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.tightEllipsis
        tightCommasPeriodsState.value = com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.tightCommasAndPeriods
        forceSpacePunctState.value = com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.forceSpaceBeforePunctuation
        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.load(this)
        chunkModeState.value = com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.chunkMode
        preRollEnabledState.value = com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.preRollEnabled
        preRollSentencesState.value = com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.preRollSentences
    }

    private fun performImportAccentDict(uri: Uri) {
        val count = AccentDictionaryManager.importFromUri(this, uri)
        when {
            count > 0 -> {
                refreshRules()
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.accent_dict_import_title))
                    .setMessage(getString(R.string.accent_dict_import_msg_fmt, count))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
            count == 0 -> {
                Toast.makeText(this, getString(R.string.accent_dict_empty), Toast.LENGTH_SHORT).show()
            }
            else -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.accent_dict_import_failed))
                    .setMessage(downloadErrorMessage(count))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
        }
    }

    /**
     * Map an ERR_* code returned by [AccentDictionaryManager] into a sentence
     * the user can act on. We always include the OOM advice because that's
     * by far the most common failure on phones <= 4 GB RAM trying to load Full.
     */
    private fun downloadErrorMessage(code: Int): String = when (code) {
        AccentDictionaryManager.ERR_OOM -> getString(R.string.err_oom)
        AccentDictionaryManager.ERR_NETWORK -> getString(R.string.err_network)
        AccentDictionaryManager.ERR_TOO_LARGE -> getString(R.string.err_too_large)
        AccentDictionaryManager.ERR_PARSE -> getString(R.string.err_parse)
        AccentDictionaryManager.ERR_IO -> getString(R.string.err_io)
        AccentDictionaryManager.ERR_BUSY -> getString(R.string.err_busy)
        else -> getString(R.string.err_unknown_fmt, code)
    }

    private fun clearAccentDict() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.accent_dict_clear_title))
            .setMessage(getString(R.string.accent_dict_clear_msg))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                AccentDictionaryManager.clear(this)
                refreshRules()
                Toast.makeText(this, getString(R.string.accent_dict_cleared), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveRule(existingItem: LexiconItem?, term: String, replacement: String, ignoreCase: Boolean, isRegex: Boolean) {
        val currentRules = rulesState.value.toMutableList()

        if (existingItem != null) {
            val index = currentRules.indexOfFirst { it.id == existingItem.id }
            if (index != -1) {
                currentRules[index] = existingItem.copy(
                    term = term,
                    replacement = replacement,
                    ignoreCase = ignoreCase,
                    isRegex = isRegex
                )
            }
        } else {
            currentRules.add(LexiconItem(
                term = term,
                replacement = replacement,
                ignoreCase = ignoreCase,
                isRegex = isRegex
            ))
        }

        LexiconManager.save(this, currentRules)
        LexiconManager.reload(this)
        refreshRules()
    }

    private fun deleteRule(item: LexiconItem) {
        val currentRules = rulesState.value.toMutableList()
        currentRules.removeIf { it.id == item.id }
        LexiconManager.save(this, currentRules)
        LexiconManager.reload(this)
        refreshRules()
    }

    private fun performExport() {
        val rules = rulesState.value
        if (rules.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_rules_export), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val jsonArray = JSONArray()
            for (rule in rules) {
                val obj = JSONObject()
                obj.put("id", rule.id)
                obj.put("term", rule.term)
                obj.put("replacement", rule.replacement)
                obj.put("ignoreCase", rule.ignoreCase)
                obj.put("isRegex", rule.isRegex)
                jsonArray.put(obj)
            }

            val fileName = "supertonic_lexicon.json"
            val file = File(cacheDir, fileName)
            file.writeText(jsonArray.toString(2))

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_chooser_title)))

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.export_failed_fmt, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()
            inputStream.close()

            val jsonArray = JSONArray(jsonString)
            val importedItems = mutableListOf<LexiconItem>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.has("term") && obj.has("replacement")) {
                    importedItems.add(LexiconItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        term = obj.getString("term"),
                        replacement = obj.getString("replacement"),
                        ignoreCase = obj.optBoolean("ignoreCase", true),
                        isRegex = obj.optBoolean("isRegex", false)
                    ))
                }
            }

            if (importedItems.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_valid_rules), Toast.LENGTH_SHORT).show()
                return
            }

            var addedCount = 0
            var updatedCount = 0
            val currentRules = LexiconManager.load(this).toMutableList()

            for (imported in importedItems) {
                val existingIndex = currentRules.indexOfFirst { it.term == imported.term }
                if (existingIndex == -1) {
                    currentRules.add(imported)
                    addedCount++
                } else {
                    val existing = currentRules[existingIndex]
                    if (existing.replacement != imported.replacement || existing.ignoreCase != imported.ignoreCase) {
                        // Replace the item with updated values
                        currentRules[existingIndex] = existing.copy(
                            replacement = imported.replacement,
                            ignoreCase = imported.ignoreCase
                        )
                        updatedCount++
                    }
                }
            }

            if (addedCount > 0 || updatedCount > 0) {
                LexiconManager.save(this, currentRules)
                LexiconManager.reload(this)
                refreshRules()

                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.import_complete_title))
                    .setMessage(getString(R.string.import_stats_fmt, addedCount, updatedCount))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            } else {
                Toast.makeText(this, getString(R.string.import_no_changes), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.import_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun testPronunciation(text: String) {
        if (!isBound || playbackService == null) {
            Toast.makeText(this, getString(R.string.engine_error), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val selectedLang = prefs.getString("selected_lang", "ru") ?: "ru"

        if (!AssetManager.isReady(this)) {
            Toast.makeText(this, getString(R.string.assets_not_ready), Toast.LENGTH_LONG).show()
            return
        }

        val voiceFile = prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
        val stylePath = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles/$voiceFile").absolutePath
        val steps = prefs.getInt("diffusion_steps", 5)

        // Use higher steps (10) for test to ensure short words are audible and clear
        val testSteps = 10

        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        
        // Pad the word to increase reliability for the model
        val testMsg = getString(R.string.testing_pronunciation_fmt, cleanText)
        val finalText = "$testMsg."

        Toast.makeText(this, testMsg, Toast.LENGTH_SHORT).show()

        try {
            playbackService?.synthesizeAndPlay(finalText, selectedLang, stylePath, 1.0f, testSteps, 0)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}