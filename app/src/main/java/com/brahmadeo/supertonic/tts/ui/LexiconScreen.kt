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
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Info
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
    onDeleteClick: (LexiconItem) -> Unit,
    // opened right at a page ("voice" from the reader's panel): back leaves the settings
    initialPage: String? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    // the settings by topic, as in Moon+ Reader: a list of sections, each opens its page
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialPage) }
    val back: () -> Unit = { if (page != null && initialPage == null) page = null else onBackClick() }
    androidx.activity.compose.BackHandler(enabled = page != null && initialPage == null) { page = null }
    val pages = settingsPages()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pages.firstOrNull { it.key == page }?.title ?: stringResource(AppR.string.lexicon_title)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(AppR.string.back))
                    }
                },
                actions = {
                    // the dictionaries' menu belongs to the page of stress
                    if (page == "stress") IconButton(onClick = { showMenu = true }) {
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
            if (page == "stress") ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Default.Add, stringResource(AppR.string.add_term_button)) },
                text = { Text(stringResource(AppR.string.add_term_button)) },
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    ) { paddingValues ->
        if (page == null) {
            // the sections
            LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(pages) { pg ->
                    ListItem(
                        headlineContent = { Text(pg.title) },
                        supportingContent = { Text(pg.desc, style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(pg.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { page = pg.key }
                    )
                }
            }
        } else LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
          when (page) {
          "voice" -> {
            item { MainVoiceRow() }
            item { EngineSection() }
            item { ImportVoiceRow() }
            item { NoiseScaleRow() }
          }
          "reading" -> {
            item { PauseScaleRow() }
            item { ParagraphPauseRow() }
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
            item { MusicSection() }
            item {
                Text(
                    text = stringResource(AppR.string.section_playback),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item { ChunkModeRow(chunkMode = chunkMode, onChange = onChunkModeChange) }
            item {
                PreRollRow(
                    enabled = preRollEnabled,
                    sentences = preRollSentences,
                    onToggle = onPreRollToggle,
                    onSentencesChange = onPreRollSentencesChange
                )
            }
          }
          "roles" -> { item { RolesSection() } }
          "prep" -> { item { PrepSection() } }
          "perf" -> {
            item { KeepReadyRow() }
            item { FastCoresRow() }
            item { SpeedTestRow() }
          }
          "about" -> {
            item { AboutRow() }
            item { SendLogRow() }
          }
          else -> {
            // "stress": the dictionaries and the user's own rules
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
    }
}

/** A section of the settings: its key, title, what is in it, its sign. */
private class SettingsPage(val key: String, val title: String, val desc: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun settingsPages() = listOf(
    SettingsPage("voice", stringResource(AppR.string.settings_voice), stringResource(AppR.string.settings_voice_desc),
        androidx.compose.material.icons.Icons.Default.RecordVoiceOver),
    SettingsPage("reading", stringResource(AppR.string.settings_reading), stringResource(AppR.string.settings_reading_desc),
        androidx.compose.material.icons.Icons.Default.Tune),
    SettingsPage("stress", stringResource(AppR.string.settings_stress), stringResource(AppR.string.settings_stress_desc),
        androidx.compose.material.icons.Icons.Default.Spellcheck),
    SettingsPage("roles", stringResource(AppR.string.settings_roles), stringResource(AppR.string.settings_roles_desc),
        androidx.compose.material.icons.Icons.Default.People),
    SettingsPage("prep", stringResource(AppR.string.settings_prep), stringResource(AppR.string.settings_prep_desc),
        androidx.compose.material.icons.Icons.Default.BatteryChargingFull),
    SettingsPage("perf", stringResource(AppR.string.settings_perf), stringResource(AppR.string.settings_perf_desc),
        androidx.compose.material.icons.Icons.Default.Speed),
    SettingsPage("about", stringResource(AppR.string.settings_about), stringResource(AppR.string.settings_about_desc),
        androidx.compose.material.icons.Icons.Default.Info)
)

/** The main voice (the narrator unless roles say otherwise) — chosen here as on the main screen. */
@Composable
private fun MainVoiceRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
    val voices = remember { com.brahmadeo.supertonic.tts.utils.RolePrefs.availableVoices(context) }
    var current by remember {
        mutableStateOf(com.brahmadeo.supertonic.tts.utils.RolePrefs.forEngine(context, prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"))
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        RoleVoicePicker(stringResource(AppR.string.main_voice_label), current, voices) { v ->
            current = v
            // a grey (unticked) Shtorm recording chosen as the main voice is in use now
            if (com.brahmadeo.supertonic.tts.utils.ShtormVoices.isOff(context, v)) com.brahmadeo.supertonic.tts.utils.ShtormVoices.setOn(context, v, true)
            prefs.edit().putString("selected_voice", v).commit()
        }
    }
}

/**
 * Audio of the open book made beforehand (BookPrep): on / off, when (only on charge, or also with at
 * least half the battery), how much of the book is ready, and freeing the space.
 */
@Composable
private fun PrepSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prep = com.brahmadeo.supertonic.tts.utils.BookPrep
    var on by remember { mutableStateOf(prep.enabled(context)) }
    var mode by remember { mutableStateOf(prep.mode(context)) }
    var tick by remember { mutableIntStateOf(0) }
    val book = remember { com.brahmadeo.supertonic.tts.utils.BookSession.currentPath(context) }
    val status = remember(tick, on, mode) {
        val prefs = context.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val voice = prefs.getString("last_voice_path", "") ?: ""
        if (book != null) prep.status(context, book, voice) else 0 to 0L
    }
    Column {
        PunctuationToggleRow(
            title = stringResource(AppR.string.prep_title),
            description = stringResource(AppR.string.prep_desc),
            checked = on,
            onToggle = { on = it; prep.setEnabled(context, it) }
        )
        if (on) {
            Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                for ((m, label) in listOf(prep.MODE_CHARGING to stringResource(AppR.string.prep_mode_charging),
                        prep.MODE_HALF to stringResource(AppR.string.prep_mode_half))) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { mode = m; prep.setMode(context, m) }
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (book == null) stringResource(AppR.string.prep_no_book)
                    else stringResource(AppR.string.prep_status, status.first, (status.second / (1024 * 1024)).toInt()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row {
                    TextButton(onClick = {
                        if (!prep.conditionsOk(context)) android.widget.Toast.makeText(context,
                            context.getString(AppR.string.prep_wait), android.widget.Toast.LENGTH_LONG).show()
                        prep.kick(context); tick++
                    }) { Text(stringResource(AppR.string.prep_now)) }
                    TextButton(onClick = { prep.deleteAll(context); tick++ }, enabled = status.second > 0) {
                        Text(stringResource(AppR.string.prep_delete))
                    }
                }
            }
        }
    }
}

/** The version, and where the program lives. */
@Composable
private fun AboutRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }
    ListItem(
        headlineContent = { Text("TeraTTS $version") },
        supportingContent = { Text(stringResource(AppR.string.settings_about_text), style = MaterialTheme.typography.bodySmall) }
    )
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

/** The model chosen on this screen, shared by the model section and the check of an imported voice. */
private val engineShown = mutableStateOf("")

/** Starts the engine again with the chosen model (in the background, reading waits for it). */
private fun restartEngine(context: android.content.Context) {
    try {
        context.startService(android.content.Intent(context, com.brahmadeo.supertonic.tts.service.PlaybackService::class.java)
            .setAction("RESET_ENGINE"))
    } catch (_: Exception) { }
}

/**
 * The voice model: TeraTTS (Russian, bundled) or Shtorm PocketTTS RU (speaks in the voice of a
 * short recording; downloaded here on request). The engine restarts with the chosen one.
 */
@Composable
private fun EngineSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val assets = com.brahmadeo.supertonic.tts.utils.AssetManager
    val scope = rememberCoroutineScope()
    remember { engineShown.value = assets.engine(context); 0 }
    var engine by engineShown
    var ready by remember { mutableStateOf(assets.shtormReady(context)) }
    var precision by remember { mutableStateOf(assets.shtormPrecision(context)) }
    var liveliness by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.shtormLiveliness) }
    var brightness by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.shtormBrightness) }
    var commaQuotes by remember { assets.loadCommaQuotes(context); mutableStateOf(assets.commaQuotes) }
    var teacher by remember { mutableStateOf(assets.teacherOn(context)) }
    var teacherFile by remember { mutableStateOf(assets.teacherDownloaded(context)) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun restart() = restartEngine(context)
    fun choose(e: String) {
        if (e == assets.ENGINE_SHTORM && !ready) {
            if (progress != null) return
            error = null; progress = 0f
            scope.launch {
                try {
                    assets.downloadShtorm(context) { p -> scope.launch { progress = p } }
                    ready = assets.shtormReady(context)
                    if (ready) { assets.setEngine(context, e); engine = e; restart() }
                } catch (ex: Exception) {
                    error = context.getString(AppR.string.engine_download_failed, ex.message ?: "?")
                }
                progress = null
            }
            return
        }
        if (engine == e) return
        assets.setEngine(context, e); engine = e; restart()
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(AppR.string.engine_title), style = MaterialTheme.typography.titleSmall)
        for ((id, title, desc) in listOf(
            Triple(assets.ENGINE_TERA, stringResource(AppR.string.engine_tera), stringResource(AppR.string.engine_tera_desc)),
            Triple(assets.ENGINE_SHTORM, stringResource(AppR.string.engine_shtorm), stringResource(
                if (ready) AppR.string.engine_shtorm_desc_ready else AppR.string.engine_shtorm_desc_download))
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
        // TeraTTS: comma replacement and the teacher sampler
        if (engine == assets.ENGINE_TERA) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    .clickable { commaQuotes = !commaQuotes; assets.setCommaQuotes(context, commaQuotes) }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(AppR.string.tera_comma_quotes), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(AppR.string.tera_comma_quotes_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = commaQuotes, onCheckedChange = { commaQuotes = it; assets.setCommaQuotes(context, it) })
            }
            fun switchTeacher(on: Boolean) {
                if (progress != null) return
                error = null; progress = if (on && !teacherFile) 0f else null
                scope.launch {
                    try {
                        assets.setTeacher(context, on) { p -> scope.launch { progress = p } }
                        teacher = assets.teacherOn(context); teacherFile = assets.teacherDownloaded(context)
                        restart()
                    } catch (ex: Exception) {
                        error = context.getString(AppR.string.engine_download_failed, ex.message ?: "?")
                        teacher = assets.teacherOn(context); teacherFile = assets.teacherDownloaded(context)
                    }
                    progress = null
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { switchTeacher(!teacher) }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(AppR.string.tera_teacher), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if (teacherFile) AppR.string.tera_teacher_desc else AppR.string.tera_teacher_desc_download),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = teacher, onCheckedChange = { switchTeacher(it) })
            }
            if (teacherFile && !teacher && progress == null) {
                TextButton(onClick = { assets.deleteTeacher(context); teacherFile = false }) {
                    Text(stringResource(AppR.string.tera_teacher_delete))
                }
            }
        }
        // Shtorm: liveliness — the noise of each frame (takes effect from the next phrase)
        if (engine == assets.ENGINE_SHTORM) {
            Text(stringResource(AppR.string.shtorm_liveliness_title), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(AppR.string.shtorm_liveliness_desc, "%.2f".format(liveliness)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(AppR.string.noise_scale_stable), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = liveliness,
                    onValueChange = { liveliness = Math.round(it * 20f) / 20f },
                    onValueChangeFinished = { com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setShtormLiveliness(context, liveliness) },
                    valueRange = 0.1f..0.7f,
                    steps = 11,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(stringResource(AppR.string.noise_scale_lively), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = {
                liveliness = 0.3f
                com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setShtormLiveliness(context, 0.3f)
            }, enabled = liveliness != 0.3f) { Text(stringResource(AppR.string.shtorm_liveliness_reset)) }
            // brightness: the highs Shtorm's codec softens, given back (from the next phrase)
            Text(stringResource(AppR.string.shtorm_brightness_title), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(AppR.string.shtorm_brightness_desc, brightness.toInt()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(AppR.string.shtorm_brightness_soft), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = Math.round(it).toFloat() },
                    onValueChangeFinished = { com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setShtormBrightness(context, brightness) },
                    valueRange = 0f..12f,
                    steps = 11,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(stringResource(AppR.string.shtorm_brightness_bright), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = {
                brightness = 8f
                com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.setShtormBrightness(context, 8f)
            }, enabled = brightness != 8f) { Text(stringResource(AppR.string.shtorm_brightness_reset)) }
        }
        progress?.let { p ->
            Text(stringResource(AppR.string.engine_downloading, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        // Shtorm's precision: full (32 bits, 302 MB) or faster (8 bits, 76 MB); the other file is
        // downloaded on choosing it, and the unused one deleted
        if (ready && engine == assets.ENGINE_SHTORM) {
            Text(stringResource(AppR.string.shtorm_precision_title), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            for ((p, label) in listOf("32" to stringResource(AppR.string.shtorm_precision_32), "8" to stringResource(AppR.string.shtorm_precision_8))) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp).clickable(enabled = progress == null) {
                        if (precision == p) return@clickable
                        error = null; progress = 0f
                        scope.launch {
                            try {
                                assets.setShtormPrecision(context, p) { v -> scope.launch { progress = v } }
                                restart()
                            } catch (ex: Exception) {
                                error = context.getString(AppR.string.engine_download_failed, ex.message ?: "?")
                            }
                            precision = assets.shtormPrecision(context)
                            progress = null
                        }
                    }
                ) {
                    RadioButton(selected = precision == p, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (ready && engine != assets.ENGINE_SHTORM && progress == null) {
            TextButton(onClick = { assets.deleteShtorm(context); ready = false }) {
                Text(stringResource(AppR.string.engine_shtorm_delete, assets.shtormSizeMb(context)))
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
    // one file (copied into the app, as before) or several (they play one after another)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val msg = try {
            val name = music.importFiles(context, uris)
            tracks = music.tracks(context); chosen = music.track(context)?.id; on = true
            context.getString(AppR.string.music_own_done, name)
        } catch (e: Exception) {
            context.getString(AppR.string.music_own_failed, e.message ?: "?")
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    // a whole folder: its songs one after another (a playlist: the folder its songs lie in)
    val folderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = try {
            val name = music.importFolder(context, uri)
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
                Row {
                    TextButton(onClick = { launcher.launch(arrayOf("audio/*")) }) { Text(stringResource(AppR.string.music_pick)) }
                    TextButton(onClick = { folderLauncher.launch(null) }) { Text(stringResource(AppR.string.music_pick_folder)) }
                }
                Text(stringResource(AppR.string.music_list_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Your own voice: a recording (for Shtorm), a Supertonic .json or the two TeraTTS .npy files. */
@Composable
private fun ImportVoiceRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var checking by remember { mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val msg = try {
            val name = com.brahmadeo.supertonic.tts.utils.VoiceImport.import(context, uris)
            // a recording: listen to it at once, another piece of it can be taken
            if (name.endsWith(".wav")) { checking = name; null }
            else context.getString(AppR.string.voice_import_done, name.removeSuffix(".json"))
        } catch (e: Exception) {
            context.getString(AppR.string.voice_import_failed, e.message ?: "?")
        }
        msg?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    checking?.let { name -> VoiceCheckDialog(name) { checking = null } }
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

private const val VOICE_CHECK_PHRASE =
    "Здравствуйте! Так этот голос будет читать ваши книги. Он улыбнулся, кивнул и, не говоря ни слова, вышел из комнаты."

/**
 * A voice made from a recording: listen to it in Shtorm, take another piece of the recording
 * (start, middle, end) if this one did not come out well, or delete it.
 */
@Composable
private fun VoiceCheckDialog(name: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val assets = com.brahmadeo.supertonic.tts.utils.AssetManager
    val vi = com.brahmadeo.supertonic.tts.utils.VoiceImport
    val scope = rememberCoroutineScope()
    remember { if (engineShown.value.isEmpty()) engineShown.value = assets.engine(context); 0 }
    var engine by engineShown
    var part by remember { mutableStateOf(vi.PART_START) }
    var gender by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.VoiceGender.get(context, name)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val label = name.removeSuffix(".wav")
    fun play() {
        val path = java.io.File(context.filesDir, "${assets.MODEL_VERSION}/voice_styles/$name").absolutePath
        try {
            context.startService(android.content.Intent(context, com.brahmadeo.supertonic.tts.service.PlaybackService::class.java)
                .setAction(com.brahmadeo.supertonic.tts.service.PlaybackService.ACTION_PREVIEW)
                .putExtra("text", VOICE_CHECK_PHRASE).putExtra("voice", path).putExtra("speed", 1f))
        } catch (_: Exception) { }
    }
    fun close() { vi.done(); onClose() }
    LaunchedEffect(name) { if (engine == assets.ENGINE_SHTORM) play() }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(stringResource(AppR.string.voice_check_title, label)) },
        text = {
            Column {
                if (engine == assets.ENGINE_SHTORM) {
                    Text(stringResource(AppR.string.voice_check_desc), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(AppR.string.voice_check_need_shtorm), style = MaterialTheme.typography.bodyMedium)
                    if (assets.shtormReady(context)) {
                        TextButton(onClick = {
                            assets.setEngine(context, assets.ENGINE_SHTORM); engine = assets.ENGINE_SHTORM
                            restartEngine(context); play()
                        }) { Text(stringResource(AppR.string.voice_check_use_shtorm)) }
                    }
                }
                if (vi.canRecut(name)) {
                    Text(stringResource(AppR.string.voice_check_part), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((p, title) in listOf(vi.PART_START to stringResource(AppR.string.voice_part_start),
                                vi.PART_MIDDLE to stringResource(AppR.string.voice_part_middle),
                                vi.PART_END to stringResource(AppR.string.voice_part_end))) {
                            FilterChip(
                                selected = part == p,
                                enabled = !busy,
                                onClick = {
                                    if (part != p) {
                                        busy = true; error = null
                                        scope.launch {
                                            try {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vi.recut(context, name, p) }
                                                part = p
                                                if (engine == assets.ENGINE_SHTORM) play()
                                            } catch (e: Exception) {
                                                error = context.getString(AppR.string.voice_import_failed, e.message ?: "?")
                                            }
                                            busy = false
                                        }
                                    }
                                },
                                label = { Text(title) }
                            )
                        }
                    }
                }
                // the gender: without it the voice is not given to characters (reading by roles)
                Text(stringResource(AppR.string.voice_gender_label), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((g, title) in listOf("m" to stringResource(AppR.string.voice_gender_m),
                            "f" to stringResource(AppR.string.voice_gender_f))) {
                        FilterChip(
                            selected = gender == g,
                            onClick = {
                                gender = if (gender == g) "" else g
                                com.brahmadeo.supertonic.tts.utils.VoiceGender.set(context, name, gender)
                            },
                            label = { Text(title) }
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                if (engine == assets.ENGINE_SHTORM) {
                    TextButton(onClick = { play() }, enabled = !busy) { Text(stringResource(AppR.string.voice_check_play)) }
                }
                TextButton(onClick = { close() }) { Text(stringResource(AppR.string.voice_check_done)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { vi.delete(context, name); close() }) { Text(stringResource(AppR.string.voice_check_delete)) }
        }
    )
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
