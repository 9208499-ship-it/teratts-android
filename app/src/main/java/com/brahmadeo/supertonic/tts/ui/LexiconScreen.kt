package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R as AppR
import com.brahmadeo.supertonic.tts.utils.LexiconItem
import com.brahmadeo.supertonic.tts.utils.PlaybackPrefs

data class AccentDictBanner(
    val source: String,
    val entries: Int,
    val sizeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiconScreen(
    rules: List<LexiconItem>,
    accentDictBanner: AccentDictBanner?,
    canDownloadAccentDict: Boolean,
    syncLoadEnabled: Boolean,
    lexiconEnabled: Boolean,
    tightQuestionExclamation: Boolean,
    strengthenIntonation: Boolean,
    tightEllipsis: Boolean,
    tightCommasAndPeriods: Boolean,
    forceSpaceBeforePunctuation: Boolean,
    chunkMode: PlaybackPrefs.ChunkMode,
    preRollEnabled: Boolean,
    preRollSentences: Int,
    onBackClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onImportAccentDictClick: () -> Unit,
    onClearAccentDictClick: () -> Unit,
    onDownloadAccentDictTextClick: () -> Unit,
    onDownloadAccentDictBinaryClick: () -> Unit,
    onSyncLoadToggle: (Boolean) -> Unit,
    onLexiconToggle: (Boolean) -> Unit,
    onTightQuestionToggle: (Boolean) -> Unit,
    onDoubleMarksToggle: (Boolean) -> Unit,
    onTightEllipsisToggle: (Boolean) -> Unit,
    onTightCommasPeriodsToggle: (Boolean) -> Unit,
    onForceSpaceBeforePunctToggle: (Boolean) -> Unit,
    onChunkModeChange: (PlaybackPrefs.ChunkMode) -> Unit,
    onPreRollToggle: (Boolean) -> Unit,
    onPreRollSentencesChange: (Int) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (LexiconItem) -> Unit,
    onDeleteClick: (LexiconItem) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(AppR.string.lexicon_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(AppR.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(AppR.string.more))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(AppR.string.menu_import_json)) },
                            onClick = {
                                showMenu = false
                                onImportClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(AppR.string.menu_export_json)) },
                            onClick = {
                                showMenu = false
                                onExportClick()
                            }
                        )
                        HorizontalDivider()
                        if (canDownloadAccentDict) {
                            // one dictionary: silero (Full & co. fixed homographs' stress and
                            // overrode the engine, which picks it by context)
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.menu_download_silero)) },
                                onClick = {
                                    showMenu = false
                                    onDownloadAccentDictBinaryClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(AppR.string.menu_import_dict)) },
                            onClick = {
                                showMenu = false
                                onImportAccentDictClick()
                            }
                        )
                        if (accentDictBanner != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.menu_clear_dict)) },
                                onClick = {
                                    showMenu = false
                                    onClearAccentDictClick()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Default.Add, stringResource(AppR.string.add_term_button)) },
                text = { Text(stringResource(AppR.string.add_term_button)) },
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            if (accentDictBanner != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = accentDictBanner.source,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "%,d entries · %.1f MB".format(
                                        accentDictBanner.entries,
                                        accentDictBanner.sizeBytes / 1_048_576.0
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            IconButton(onClick = onClearAccentDictClick) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(AppR.string.menu_clear_dict),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Master switch for the user lexicon. When off, none of the
            // custom replacement rules below are applied during synthesis —
            // useful for A/B testing whether a rule is doing more harm than
            // good without having to delete it.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(AppR.string.lexicon_master_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(AppR.string.lexicon_master_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = lexiconEnabled,
                            onCheckedChange = onLexiconToggle
                        )
                    }
                }
            }

            // Sync-load toggle. Defaults OFF so the dictionary parses in the
            // background and the first sentence after a cold start may render
            // un-stressed; turning it ON makes Service.onCreate block until
            // the full 165 MB dict is loaded — slower startup, but guaranteed
            // stresses from the very first word.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(AppR.string.sync_load_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(AppR.string.sync_load_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = syncLoadEnabled,
                            onCheckedChange = onSyncLoadToggle
                        )
                    }
                }
            }

            // Punctuation experiments. Each toggle is independent; default OFF
            // preserves the legacy stabilisation rules. Section header sets the
            // shared context so the four switches read as a single feature.
            item {
                Text(
                    text = stringResource(AppR.string.section_punctuation),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                )
            }

            item {
                PunctuationToggleRow(
                    title = stringResource(AppR.string.punct_tight_qe_title),
                    description = stringResource(AppR.string.punct_tight_qe_desc),
                    checked = tightQuestionExclamation,
                    onToggle = onTightQuestionToggle
                )
            }



            item {
                PunctuationToggleRow(
                    title = stringResource(AppR.string.punct_tight_cp_title),
                    description = stringResource(AppR.string.punct_tight_cp_desc),
                    checked = tightCommasAndPeriods,
                    onToggle = onTightCommasPeriodsToggle
                )
            }

            item { EnglishWordsRow() }
            item { ImportVoiceRow() }
            item { SendLogRow() }
            item { KeepReadyRow() }
            item { FastCoresRow() }
            item { EngineSection() }
            item { SpeedTestRow() }
            item { RolesSection() }
            item { MusicSection() }
            item { NoiseScaleRow() }
            item { PauseScaleRow() }
            item { ParagraphPauseRow() }


            // ─── Playback section ────────────────────────────────────────
            // Independent of Lexicon/Punctuation — controls how text is
            // chunked before synthesis and whether AudioTrack waits for a
            // pre-roll buffer before starting playback. Both default to the
            // legacy behavior (DEFAULT chunk size, pre-roll off) so existing
            // users see no change unless they opt in.
            item {
                Text(
                    text = stringResource(AppR.string.section_playback),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }

            item {
                ChunkModeRow(
                    chunkMode = chunkMode,
                    onChange = onChunkModeChange
                )
            }

            item {
                PreRollRow(
                    enabled = preRollEnabled,
                    sentences = preRollSentences,
                    onToggle = onPreRollToggle,
                    onSentencesChange = onPreRollSentencesChange
                )
            }

            if (rules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(AppR.string.lexicon_empty_state),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                item {
                    Text(
                        text = stringResource(AppR.string.custom_rules_header),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }

                items(rules) { rule ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LexiconItemRow(
                            item = rule,
                            onEdit = { onEditClick(rule) },
                            onDelete = { onDeleteClick(rule) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Three-option chunk-size selector with description. Uses a single-choice
 * segmented button row for a compact, idiomatic Material 3 control. The
 * labels reflect typical use cases (notifications vs balanced vs books) so
 * users don't need to know what "chunk limit" means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChunkModeRow(
    chunkMode: PlaybackPrefs.ChunkMode,
    onChange: (PlaybackPrefs.ChunkMode) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
            Text(text = stringResource(AppR.string.chunk_size_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(AppR.string.chunk_size_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            val options = PlaybackPrefs.ChunkMode.values()
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = chunkMode == mode,
                        onClick = { onChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(
                            text = stringResource(
                                when (mode) {
                                    PlaybackPrefs.ChunkMode.SMALL -> AppR.string.chunk_size_small
                                    PlaybackPrefs.ChunkMode.DEFAULT -> AppR.string.chunk_size_default
                                    PlaybackPrefs.ChunkMode.LARGE -> AppR.string.chunk_size_large
                                    PlaybackPrefs.ChunkMode.HUGE -> AppR.string.chunk_size_huge
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pre-roll switch with an inline slider that only appears when the switch is
 * on. Slider range matches PlaybackPrefs (1..5) and reads/writes through the
 * onSentencesChange callback so the activity owns persistence.
 */
@Composable
private fun PreRollRow(
    enabled: Boolean,
    sentences: Int,
    onToggle: (Boolean) -> Unit,
    onSentencesChange: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(AppR.string.preroll_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(AppR.string.preroll_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(AppR.string.preroll_sentences_fmt, sentences),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // valueRange (1, 5) with 3 steps between gives 1,2,3,4,5 stops.
                Slider(
                    value = sentences.toFloat(),
                    onValueChange = { onSentencesChange(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Single-line toggle with title + small description, used four times for the
 * punctuation tweaks. Factored out so all four rows share spacing/colors.
 */
@Composable
private fun PunctuationToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

/** "Pause between paragraphs" slider, seconds. */
@Composable
private fun ParagraphPauseRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var seconds by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.paragraphPause) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(AppR.string.punct_paragraph_pause_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", seconds) + " s",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(AppR.string.punct_paragraph_pause_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = seconds,
                onValueChange = { seconds = Math.round(it * 10f) / 10f },
                onValueChangeFinished = {
                    com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setParagraphPause(context, seconds)
                },
                valueRange = 0f..2f,
                steps = 19
            )
        }
    }
}

/** "Keep the engine ready": permanent foreground with the models in memory (Huawei), or unload when idle. */
/** "Fast cores only": synthesis threads on the fast cluster (compare the speed in the notification). */
@Composable
private fun FastCoresRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cores = com.brahmadeo.supertonic.tts.utils.CpuCores
    val fast = remember { cores.fast() }
    var on by remember(com.brahmadeo.supertonic.tts.utils.SpeedTest.version.intValue) { mutableStateOf(cores.enabled(context)) }
    PunctuationToggleRow(
        title = stringResource(AppR.string.fast_cores_title),
        description = if (fast.isEmpty()) stringResource(AppR.string.fast_cores_same)
                      else stringResource(AppR.string.fast_cores_desc, fast.size, cores.total()),
        checked = on,
        onToggle = { v -> on = v; cores.set(context, v) }
    )
}

/** "Тест скорости": every way of running the engine is timed here, the fastest is kept. */
@Composable
private fun SpeedTestRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val test = com.brahmadeo.supertonic.tts.utils.SpeedTest
    val v = test.version.intValue
    val now = remember(v) { test.currentLabel(context) }
    val last = remember(v) { test.lastResult(context) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable(enabled = !running) {
                if (com.brahmadeo.supertonic.tts.service.PlaybackService.busy() || test.running) {
                    android.widget.Toast.makeText(context, AppR.string.speed_test_busy, android.widget.Toast.LENGTH_LONG).show()
                    return@clickable
                }
                running = true
                dialog = context.getString(AppR.string.speed_test_starting)
                Thread {
                    val text = test.run(context) { msg -> main.post { dialog = msg } }
                    main.post { running = false; dialog = text }
                }.start()
            },
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(text = stringResource(AppR.string.speed_test_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(AppR.string.speed_test_desc, now) +
                    (last?.let { "\n" + stringResource(AppR.string.speed_test_last, it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    dialog?.let { text ->
        AlertDialog(
            onDismissRequest = { if (!running) dialog = null },
            title = { Text(stringResource(AppR.string.speed_test_title)) },
            text = {
                Column {
                    if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    Text(text)
                }
            },
            confirmButton = {
                if (!running) TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}

/**
 * The voice model: TeraTTS (Russian, bundled) or Supertonic 3 (Supertone's new multilingual
 * model, downloaded here on request). The engine restarts with the chosen one.
 */
@Composable
private fun EngineSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val assets = com.brahmadeo.supertonic.tts.utils.AssetManager
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(assets.engine(context)) }
    var ready by remember { mutableStateOf(assets.st3Ready(context)) }
    var fast by remember { mutableStateOf(assets.st3Fast(context)) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun restart() {
        try {
            context.startService(android.content.Intent(context, com.brahmadeo.supertonic.tts.service.PlaybackService::class.java)
                .setAction("RESET_ENGINE"))
        } catch (_: Exception) { }
    }
    fun choose(e: String) {
        if (e == assets.ENGINE_ST3 && !ready) {
            if (progress != null) return
            error = null; progress = 0f
            scope.launch {
                try {
                    assets.downloadSt3(context) { p -> scope.launch { progress = p } }
                    ready = assets.st3Ready(context); fast = assets.st3Fast(context)
                    if (ready) { assets.setEngine(context, e); engine = e; restart() }
                } catch (ex: Exception) {
                    error = context.getString(AppR.string.engine_download_failed, ex.message ?: "?")
                }
                progress = null
            }
            return
        }
        assets.setEngine(context, e); engine = e; restart()
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(AppR.string.engine_title), style = MaterialTheme.typography.titleSmall)
        for ((id, title, desc) in listOf(
            Triple(assets.ENGINE_TERA, stringResource(AppR.string.engine_tera), stringResource(AppR.string.engine_tera_desc)),
            Triple(assets.ENGINE_ST3, stringResource(AppR.string.engine_st3), stringResource(
                if (ready) AppR.string.engine_st3_desc_ready else AppR.string.engine_st3_desc_download))
        )) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { choose(id) }
            ) {
                RadioButton(selected = engine == id, onClick = { choose(id) })
                Column {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        progress?.let { p ->
            Text(stringResource(AppR.string.engine_downloading, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        // downloaded before the sped-up files were there (1.6.21): offer them
        if (ready && !fast && progress == null) {
            TextButton(onClick = {
                error = null; progress = 0f
                scope.launch {
                    try {
                        assets.upgradeSt3(context) { p -> scope.launch { progress = p } }
                        ready = assets.st3Ready(context); fast = assets.st3Fast(context)
                        if (engine == assets.ENGINE_ST3) restart()
                    } catch (ex: Exception) {
                        error = context.getString(AppR.string.engine_download_failed, ex.message ?: "?")
                        ready = assets.st3Ready(context)
                    }
                    progress = null
                }
            }) { Text(stringResource(AppR.string.engine_st3_upgrade)) }
        }
        if (ready && engine != assets.ENGINE_ST3 && progress == null) {
            TextButton(onClick = { assets.deleteSt3(context); ready = false }) {
                Text(stringResource(AppR.string.engine_st3_delete, assets.st3SizeMb(context)))
            }
        }
    }
}

/** Background music under the reading: on/off, the melody (bundled or the user's own file), its volume. */
@Composable
private fun MusicSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val music = com.brahmadeo.supertonic.tts.utils.BackgroundMusic
    var on by remember { mutableStateOf(music.enabled(context)) }
    var tracks by remember { mutableStateOf(music.tracks(context)) }
    var chosen by remember { mutableStateOf(music.track(context)?.id) }
    var volume by remember { mutableStateOf(music.volume(context).toFloat()) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = try {
            val name = music.importOwn(context, uri)
            tracks = music.tracks(context); chosen = music.track(context)?.id; on = true
            context.getString(AppR.string.music_own_done, name)
        } catch (e: Exception) {
            context.getString(AppR.string.music_own_failed, e.message ?: "?")
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    Column {
        PunctuationToggleRow(
            title = stringResource(AppR.string.music_title),
            description = stringResource(AppR.string.music_desc),
            checked = on,
            onToggle = { on = it; music.setEnabled(context, it) }
        )
        if (on) {
            Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                if (tracks.isEmpty()) Text(stringResource(AppR.string.music_none), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (t in tracks) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { chosen = t.id; music.setTrack(context, t.id) }
                    ) {
                        RadioButton(selected = chosen == t.id, onClick = { chosen = t.id; music.setTrack(context, t.id) })
                        Text(if (t.id == com.brahmadeo.supertonic.tts.utils.BackgroundMusic.OWN) stringResource(AppR.string.music_own, t.title) else t.title,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = { launcher.launch(arrayOf("audio/*")) }) { Text(stringResource(AppR.string.music_pick)) }
                Text(stringResource(AppR.string.music_volume, volume.toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = volume,
                    onValueChange = { volume = Math.round(it).toFloat(); music.setVolume(context, volume.toInt()) },
                    valueRange = 0f..60f
                )
            }
        }
    }
}

/** Your own voice: a Supertonic .json or the two TeraTTS .npy files, added to the voice list. */
@Composable
private fun ImportVoiceRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val msg = try {
            val name = com.brahmadeo.supertonic.tts.utils.VoiceImport.import(context, uris)
            context.getString(AppR.string.voice_import_done, name.removeSuffix(".json"))
        } catch (e: Exception) {
            context.getString(AppR.string.voice_import_failed, e.message ?: "?")
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { launcher.launch(arrayOf("*/*")) },
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(text = stringResource(AppR.string.voice_import_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(AppR.string.voice_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Reading crashes in another reader: the log the developer needs, sent by hand. */
@Composable
private fun SendLogRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable {
                try { com.brahmadeo.supertonic.tts.utils.CrashReport.shareLog(context) }
                catch (e: Exception) {
                    android.widget.Toast.makeText(context, e.message ?: "?", android.widget.Toast.LENGTH_LONG).show()
                }
            },
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(text = stringResource(AppR.string.send_log_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(AppR.string.send_log_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EnglishWordsRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.EnglishTranscriber.let { it.load(context); it.enabled }) }
    PunctuationToggleRow(
        title = stringResource(AppR.string.english_ru_title),
        description = stringResource(AppR.string.english_ru_desc),
        checked = on,
        onToggle = { v ->
            on = v
            com.brahmadeo.supertonic.tts.utils.EnglishTranscriber.setEnabled(context, v)
        }
    )
}

@Composable
private fun KeepReadyRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.EngineIdle.keepAlive(context)) }
    PunctuationToggleRow(
        title = stringResource(AppR.string.keep_ready_title),
        description = stringResource(AppR.string.keep_ready_desc),
        checked = on,
        onToggle = { v ->
            on = v
            context.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean(com.brahmadeo.supertonic.tts.utils.EngineIdle.PREF_KEEP_ALIVE, v).apply()
            val svc = com.brahmadeo.supertonic.tts.service.SupertonicTextToSpeechService::class.java
            try {
                if (v) androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, svc).setAction(com.brahmadeo.supertonic.tts.service.SupertonicTextToSpeechService.ACTION_KEEP_ALIVE))
                else context.startService(android.content.Intent(context, svc)
                    .setAction(com.brahmadeo.supertonic.tts.service.SupertonicTextToSpeechService.ACTION_STOP_KEEP_ALIVE))
            } catch (e: Exception) { }
            if (!v) com.brahmadeo.supertonic.tts.utils.EngineIdle.schedule(context)
        }
    )
}

/** Reading by roles (built-in reader): switch + voices for male and female lines. */
@Composable
private fun RolesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.brahmadeo.supertonic.tts.utils.RolePrefs
    var enabled by remember { mutableStateOf(prefs.enabled(context)) }
    var male by remember { mutableStateOf(prefs.maleVoice(context)) }
    var female by remember { mutableStateOf(prefs.femaleVoice(context)) }
    var narrator by remember { mutableStateOf(prefs.narratorVoice(context)) }
    var unknown by remember { mutableStateOf(prefs.unknownVoice(context)) }
    var mainCount by remember { mutableStateOf(prefs.mainCount(context).toFloat()) }
    var perCharacter by remember { mutableStateOf(prefs.charactersEnabled(context)) }
    var evenLoudness by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.VoiceLoudness.enabled(context)) }
    var evenTempo by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.VoiceTempo.enabled(context)) }
    val voices = remember { prefs.availableVoices(context) }
    Column {
        PunctuationToggleRow(
            title = stringResource(AppR.string.roles_title),
            description = stringResource(AppR.string.roles_desc),
            checked = enabled,
            onToggle = { enabled = it; prefs.setEnabled(context, it) }
        )
        if (enabled) {
            PunctuationToggleRow(
                title = stringResource(AppR.string.roles_characters_title),
                description = stringResource(AppR.string.roles_characters_desc),
                checked = perCharacter,
                onToggle = { perCharacter = it; prefs.setCharactersEnabled(context, it) }
            )
            PunctuationToggleRow(
                title = stringResource(AppR.string.roles_loudness_title),
                description = stringResource(AppR.string.roles_loudness_desc),
                checked = evenLoudness,
                onToggle = { evenLoudness = it; com.brahmadeo.supertonic.tts.utils.VoiceLoudness.setEnabled(context, it) }
            )
            PunctuationToggleRow(
                title = stringResource(AppR.string.roles_tempo_title),
                description = stringResource(AppR.string.roles_tempo_desc),
                checked = evenTempo,
                onToggle = { evenTempo = it; com.brahmadeo.supertonic.tts.utils.VoiceTempo.setEnabled(context, it) }
            )
            if (perCharacter) {
                Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                    Text(stringResource(AppR.string.roles_main_count, mainCount.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = mainCount,
                        onValueChange = { mainCount = Math.round(it).toFloat() },
                        onValueChangeFinished = { prefs.setMainCount(context, mainCount.toInt()) },
                        valueRange = 2f..com.brahmadeo.supertonic.tts.utils.RolePrefs.MAX_MAIN_COUNT.toFloat(),
                        steps = com.brahmadeo.supertonic.tts.utils.RolePrefs.MAX_MAIN_COUNT - 3
                    )
                }
            }
            RoleVoicePicker(stringResource(AppR.string.roles_narrator), narrator, voices,
                mainLabel = stringResource(AppR.string.roles_narrator_main)) { narrator = it; prefs.setNarratorVoice(context, it) }
            RoleVoicePicker(stringResource(AppR.string.roles_male), male, voices) { male = it; prefs.setMaleVoice(context, it) }
            RoleVoicePicker(stringResource(AppR.string.roles_female), female, voices) { female = it; prefs.setFemaleVoice(context, it) }
            RoleVoicePicker(stringResource(AppR.string.roles_unknown), unknown, voices,
                mainLabel = stringResource(AppR.string.roles_unknown_main)) { unknown = it; prefs.setUnknownVoice(context, it) }
            TextButton(
                onClick = {
                    context.startActivity(android.content.Intent(context, com.brahmadeo.supertonic.tts.CharactersActivity::class.java))
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text(stringResource(AppR.string.characters_open)) }
        }
    }
}

@Composable
private fun RoleVoicePicker(
    label: String,
    current: String,
    voices: List<String>,
    mainLabel: String? = null,   // non-null: offer "" = the main voice
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { open = true }) {
                    Text(if (current.isEmpty() && mainLabel != null) mainLabel else com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(current))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (mainLabel != null) {
                        DropdownMenuItem(text = { Text(mainLabel) }, onClick = { open = false; onPick("") })
                    }
                    for (v in voices) {
                        DropdownMenuItem(
                            text = { Text(com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(v)) },
                            onClick = { open = false; onPick(v) }
                        )
                    }
                }
            }
        }
    }
}

/** "Pause length" slider: multiplies the pauses the engine adds at punctuation. */
@Composable
private fun NoiseScaleRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var v by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.noiseScale) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(text = stringResource(AppR.string.noise_scale_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(AppR.string.noise_scale_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(AppR.string.noise_scale_stable), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = v,
                    onValueChange = { v = Math.round(it * 20f) / 20f },
                    onValueChangeFinished = { com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setNoiseScale(context, v) },
                    valueRange = 0.4f..1.0f,
                    steps = 11,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(stringResource(AppR.string.noise_scale_lively), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PauseScaleRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scale by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.pauseScale) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(AppR.string.punct_pause_scale_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "×" + String.format(java.util.Locale.US, "%.1f", scale),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(AppR.string.punct_pause_scale_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = scale,
                onValueChange = { scale = Math.round(it * 10f) / 10f },
                onValueChangeFinished = {
                    com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setPauseScale(context, scale)
                },
                valueRange = 0f..2f,
                steps = 19
            )
        }
    }
}

@Composable
fun LexiconItemRow(
    item: LexiconItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.term,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "➜ ${item.replacement}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.isRegex) {
                        Text(
                            text = "Regex",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (!item.ignoreCase) {
                        Text(
                            text = "Case sensitive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LexiconEditDialog(
    item: LexiconItem?,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, Boolean) -> Unit,
    onTest: (String) -> Unit
) {
    var term by remember { mutableStateOf(item?.term ?: "") }
    var replacement by remember { mutableStateOf(item?.replacement ?: "") }
    var ignoreCase by remember { mutableStateOf(item?.ignoreCase ?: true) }
    var isRegex by remember { mutableStateOf(item?.isRegex ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (item == null) AppR.string.add_rule_title else AppR.string.edit_rule_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(stringResource(if (isRegex) AppR.string.regex_pattern_label else AppR.string.term_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(AppR.string.replacement_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(AppR.string.ignore_case_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = ignoreCase,
                        onCheckedChange = { ignoreCase = it }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(AppR.string.regex_mode_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isRegex,
                        onCheckedChange = { isRegex = it }
                    )
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            val emptyFieldsMsg = stringResource(AppR.string.fields_empty_msg)
            Button(
                onClick = {
                    if (term.isBlank() || replacement.isBlank()) {
                        error = emptyFieldsMsg
                    } else {
                        onSave(term.trim(), replacement.trim(), ignoreCase, isRegex)
                    }
                }
            ) {
                Text(stringResource(AppR.string.save_button))
            }
        },
        dismissButton = {
            val enterReplacementMsg = stringResource(AppR.string.enter_replacement_msg)
            Row {
                TextButton(onClick = {
                    if (replacement.isNotBlank()) onTest(replacement) else error = enterReplacementMsg
                }) {
                    Text(stringResource(AppR.string.test_button))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(AppR.string.cancel_action))
                }
            }
        }
    )
}
