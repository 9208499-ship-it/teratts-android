package com.brahmadeo.supertonic.tts.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R as AppR
import com.brahmadeo.supertonic.tts.ui.components.IndeterminateWavyProgressIndicator
import com.brahmadeo.supertonic.tts.ui.components.WavyCircularProgressIndicator

/** Average pace of Russian speech at 1.0×, characters per second (for the time left). */
private const val CHARS_PER_SECOND = 14f

private fun formatTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
}

/**
 * The player: the sentence being read in large type (karaoke style, neighbours
 * faded), time left in the chapter, a seek bar, big play/pause and phrase
 * back/forward (hold: 5 phrases), speed. The full sentence list is one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    sentences: List<String>,
    currentIndex: Int,
    isPlaying: Boolean,
    isServiceActive: Boolean,
    isExporting: Boolean,
    exportCurrent: Int,
    exportTotal: Int,
    onBackClick: () -> Unit,
    onItemClick: (Int) -> Unit,
    onPlayPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onExportClick: () -> Unit,
    onCancelExportClick: () -> Unit,
    speed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
    onPrevClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onCharactersClick: () -> Unit = {}
) {
    var showList by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val n = sentences.size
    val index = currentIndex.coerceIn(0, (n - 1).coerceAtLeast(0))

    LaunchedEffect(currentIndex, showList) {
        if (showList && currentIndex in sentences.indices) listState.animateScrollToItem(currentIndex)
    }

    // time left: remaining characters at the current pace
    var sliderSpeed by remember(speed) { mutableStateOf(speed) }
    val remainingChars = remember(sentences, index) { sentences.drop(index).sumOf { it.length } }
    val remainingSeconds = (remainingChars / (CHARS_PER_SECOND * sliderSpeed.coerceAtLeast(0.3f))).toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(AppR.string.playback_now_playing), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(AppR.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showList = !showList }) {
                        Icon(
                            if (showList) Icons.Default.Subject else Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(AppR.string.player_toggle_list)
                        )
                    }
                    IconButton(onClick = onCharactersClick) {
                        Icon(Icons.Default.People, contentDescription = stringResource(AppR.string.characters_title))
                    }
                    if (!isPlaying) {
                        IconButton(onClick = onExportClick, enabled = !isExporting) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                    if (isServiceActive || isPlaying) {
                        IconButton(onClick = onStopClick) {
                            Icon(Icons.Default.Close, contentDescription = "Stop")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                // ---- the text ----
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showList) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(sentences) { i, sentence ->
                                SentenceItem(text = sentence, isActive = i == currentIndex, onClick = {
                                    onItemClick(i)
                                    showList = false
                                })
                            }
                        }
                    } else {
                        KaraokeText(sentences, index, onClick = { showList = true })
                    }
                }

                // ---- time left and seek bar ----
                Text(
                    text = "-" + formatTime(remainingSeconds),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                var seeking by remember { mutableStateOf<Float?>(null) }
                val fraction = if (n > 1) index.toFloat() / (n - 1) else 0f
                Slider(
                    value = seeking ?: fraction,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { if (n > 1) onItemClick(Math.round(it * (n - 1))) }
                        seeking = null
                    },
                    enabled = n > 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(seeking?.let { Math.round(it * (n - 1)) } ?: index) + 1} / $n",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // ---- controls ----
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BigSkipButton(
                        icon = Icons.Default.SkipPrevious,
                        description = stringResource(AppR.string.player_prev),
                        enabled = index > 0,
                        onClick = { onItemClick((index - 1).coerceAtLeast(0)) },
                        onLongClick = { onItemClick((index - 5).coerceAtLeast(0)) }
                    )
                    FilledIconButton(
                        onClick = onPlayPauseClick,
                        shape = CircleShape,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    BigSkipButton(
                        icon = Icons.Default.SkipNext,
                        description = stringResource(AppR.string.player_next),
                        enabled = index < n - 1,
                        onClick = { onItemClick((index + 1).coerceAtMost(n - 1)) },
                        onLongClick = { onItemClick((index + 5).coerceAtMost(n - 1)) }
                    )
                }

                // ---- speed ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = sliderSpeed,
                        onValueChange = { sliderSpeed = Math.round(it * 10f) / 10f },
                        onValueChangeFinished = { onSpeedChange(sliderSpeed) },
                        valueRange = 0.5f..2.5f,
                        steps = 19,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(
                        String.format(java.util.Locale.US, "%.1fx", sliderSpeed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Export Overlay
            if (isExporting) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Card(
                            modifier = Modifier
                                .width(300.dp)
                                .padding(16.dp),
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Saving Audio...",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                if (exportTotal > 0) {
                                    val progress = exportCurrent.toFloat() / exportTotal
                                    Box(contentAlignment = Alignment.Center) {
                                        WavyCircularProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.size(80.dp),
                                            strokeWidth = 6.dp,
                                            waveAmplitude = 3.dp
                                        )
                                        Text(
                                            text = "${(progress * 100).toInt()}%",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "$exportCurrent / $exportTotal chunks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    IndeterminateWavyProgressIndicator(
                                        modifier = Modifier.size(80.dp),
                                        strokeWidth = 6.dp
                                    )
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                TextButton(
                                    onClick = onCancelExportClick,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text(stringResource(AppR.string.cancel_action))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The sentence being read, large; the previous and next ones faded. Tap: the full list. */
@Composable
private fun KaraokeText(sentences: List<String>, index: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (sentences.isEmpty()) return@Column
        val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        sentences.getOrNull(index - 1)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = faded, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(20.dp))
        }
        Text(
            sentences[index],
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        sentences.getOrNull(index + 1)?.let {
            Spacer(modifier = Modifier.height(20.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = faded, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A large round phrase-skip button; a long press jumps five phrases. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigSkipButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(44.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}

@Composable
fun SentenceItem(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surface
    )
    val contentColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = text,
                style = if (isActive) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
