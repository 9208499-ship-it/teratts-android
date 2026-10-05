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
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.menu_download_binary)) },
                                onClick = {
                                    showMenu = false
                                    onDownloadAccentDictBinaryClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.menu_download_text)) },
                                onClick = {
                                    showMenu = false
                                    onDownloadAccentDictTextClick()
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

            item { RolesSection() }
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

/** Reading by roles (built-in reader): switch + voices for male and female lines. */
@Composable
private fun RolesSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.brahmadeo.supertonic.tts.utils.RolePrefs
    var enabled by remember { mutableStateOf(prefs.enabled(context)) }
    var male by remember { mutableStateOf(prefs.maleVoice(context)) }
    var female by remember { mutableStateOf(prefs.femaleVoice(context)) }
    var narrator by remember { mutableStateOf(prefs.narratorVoice(context)) }
    var perCharacter by remember { mutableStateOf(prefs.charactersEnabled(context)) }
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
            RoleVoicePicker(stringResource(AppR.string.roles_narrator), narrator, voices,
                mainLabel = stringResource(AppR.string.roles_narrator_main)) { narrator = it; prefs.setNarratorVoice(context, it) }
            RoleVoicePicker(stringResource(AppR.string.roles_male), male, voices) { male = it; prefs.setMaleVoice(context, it) }
            RoleVoicePicker(stringResource(AppR.string.roles_female), female, voices) { female = it; prefs.setFemaleVoice(context, it) }
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
                    Text(if (current.isEmpty() && mainLabel != null) mainLabel else current.removeSuffix(".json"))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (mainLabel != null) {
                        DropdownMenuItem(text = { Text(mainLabel) }, onClick = { open = false; onPick("") })
                    }
                    for (v in voices) {
                        DropdownMenuItem(
                            text = { Text(v.removeSuffix(".json")) },
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
