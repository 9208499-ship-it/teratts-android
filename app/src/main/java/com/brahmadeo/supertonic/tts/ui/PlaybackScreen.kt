package com.brahmadeo.supertonic.tts.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
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
 * The chapter laid out as a book page: its own paragraphs, and for every spoken
 * phrase (chunk) where it lies in the text. null when the phrases cannot be found
 * in the text — then the player falls back to the list of phrases.
 */
private class BookPage(val text: String, val paragraphs: List<IntRange>, val ranges: List<IntRange?>) {
    private val starts = IntArray(paragraphs.size) { paragraphs[it].first }
    val paraOfChunk: IntArray = IntArray(ranges.size).also { a ->
        var last = 0
        for (i in ranges.indices) { ranges[i]?.let { last = paraAt(it.first) }; a[i] = last }
    }
    /** chunk indices per paragraph: tap on a paragraph without phrases → the next one */
    fun paraAt(abs: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= abs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
    /** the phrase at this place of the text (or the nearest one after it) */
    fun chunkAt(abs: Int): Int {
        var best = -1
        for (i in ranges.indices) {
            val r = ranges[i] ?: continue
            if (abs in r) return i
            if (r.first > abs) return if (best >= 0 && abs - (ranges[best]?.last ?: 0) < r.first - abs) best else i
            best = i
        }
        return best.coerceAtLeast(0)
    }
}

private fun bookPageOf(text: String, sentences: List<String>): BookPage? {
    if (text.isBlank() || sentences.isEmpty()) return null
    val ranges = try {
        com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.locateRanges(text, sentences)
    } catch (e: Throwable) { return null }
    if (ranges.count { it == null } > sentences.size / 5) return null
    val paras = ArrayList<IntRange>()
    var s = 0
    for (i in 0..text.length) {
        if (i == text.length || text[i] == '\n') {
            var a = s
            var b = i - 1
            while (a <= b && text[a].isWhitespace()) a++
            while (b >= a && text[b].isWhitespace()) b--
            if (a <= b) paras.add(a..b)
            s = i + 1
        }
    }
    if (paras.isEmpty()) return null
    return BookPage(text, paras, ranges)
}

/** The part [sub] of phrase [chunk] (lying at [r] in [text]) — found in the text by letters. */
private fun mapSub(text: String, r: IntRange, chunk: String, sub: IntRange): IntRange? {
    fun lettersBefore(end: Int): Int {
        var n = 0
        for (k in 0 until end.coerceAtMost(chunk.length)) if (chunk[k].isLetterOrDigit()) n++
        return n
    }
    val a = lettersBefore(sub.first)
    val b = lettersBefore(sub.last + 1)
    if (b <= a) return null
    var cnt = 0
    var start = -1
    for (i in r) {
        if (i >= text.length) break
        if (text[i].isLetterOrDigit()) {
            if (cnt == a && start < 0) start = i
            cnt++
            if (cnt == b) return start..i
        }
    }
    return if (start >= 0) start..r.last else null
}

/**
 * The player, laid out like a reader app: the chapter as a book page (paragraphs,
 * serif type, the phrase being read shaded, the page turns after the voice), a
 * thin progress line with the time left, one compact row of controls. Tap a place
 * in the text: read from there; long press: who says this line. The karaoke view
 * (one big phrase) is one button away.
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
    onCharactersClick: () -> Unit = {},
    onSentenceLongClick: (Int) -> Unit = {},
    currentFraction: Float = -1f,     // share of the current phrase heard; < 0 = no word highlight
    text: String = "",                // the whole chapter, for the book page
    title: String = ""                // shown in the top bar (the chapter's first line)
) {
    // the book text, scrollable by finger, is the main view (karaoke is the second one)
    var showList by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val n = sentences.size
    val index = currentIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
    val book = remember(text, sentences) { bookPageOf(text, sentences) }
    val layouts = remember(book) { HashMap<Int, TextLayoutResult>() }

    // followVoice: the text scrolls after the voice until the user scrolls it by hand;
    // then "Play" starts from the top visible phrase (as in Moon+ Reader)
    var followVoice by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) followVoice = false }
    LaunchedEffect(currentIndex, showList, followVoice, book) {
        if (!showList || !followVoice || currentIndex !in sentences.indices) return@LaunchedEffect
        if (book == null) { listState.animateScrollToItem(currentIndex); return@LaunchedEffect }
        // turn the page only when the phrase leaves the screen, as a reader does
        val p = book.paraOfChunk[currentIndex]
        val pr = book.paragraphs[p]
        val r = book.ranges[currentIndex]
        val lay = layouts[p]
        var top = 0f
        var bottom = 0f
        if (r != null && lay != null) {
            val len = lay.layoutInput.text.length
            top = lay.getLineTop(lay.getLineForOffset((r.first - pr.first).coerceIn(0, (len - 1).coerceAtLeast(0))))
            bottom = lay.getLineBottom(lay.getLineForOffset((r.last - pr.first).coerceIn(0, (len - 1).coerceAtLeast(0))))
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == p }
        if (item != null && lay != null && item.offset + top >= info.viewportStartOffset &&
            item.offset + bottom <= info.viewportEndOffset - 24) return@LaunchedEffect
        val pad = (info.viewportEndOffset - info.viewportStartOffset) / 8
        listState.animateScrollToItem(p, (top.toInt() - pad).coerceAtLeast(0))
    }
    val playFrom: (Int) -> Unit = { i -> followVoice = true; onItemClick(i) }
    // the phrase at the top of the screen (after scrolling by hand)
    fun topVisibleChunk(): Int {
        if (book == null) return listState.firstVisibleItemIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
        val p = listState.firstVisibleItemIndex.coerceIn(0, book.paragraphs.size - 1)
        val local = layouts[p]?.getOffsetForPosition(Offset(0f, listState.firstVisibleItemScrollOffset.toFloat())) ?: 0
        return book.chunkAt(book.paragraphs[p].first + local).coerceIn(0, (n - 1).coerceAtLeast(0))
    }

    // time left: remaining characters at the current pace
    var sliderSpeed by remember(speed) { mutableStateOf(speed) }
    val remainingChars = remember(sentences, index) { sentences.drop(index).sumOf { it.length } }
    val remainingSeconds = (remainingChars / (CHARS_PER_SECOND * sliderSpeed.coerceAtLeast(0.3f))).toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title.ifEmpty { stringResource(AppR.string.playback_now_playing) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(AppR.string.back))
                    }
                },
                actions = {
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
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- the text ----
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                    if (showList && book != null) {
                        // a phrase may run over several short paragraphs (dialogue lines)
                        val curParas = if (n == 0) IntRange.EMPTY else
                            book.ranges[index]?.let { book.paraAt(it.first)..book.paraAt(it.last) }
                                ?: book.paraOfChunk[index]..book.paraOfChunk[index]
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(book.paragraphs.size) { p ->
                                BookParagraph(
                                    book = book, p = p, sentences = sentences,
                                    current = if (p in curParas) index else -1,
                                    fraction = if (p in curParas) currentFraction else -1f,
                                    onLayout = { layouts[p] = it },
                                    onTap = { i -> playFrom(i) },
                                    onLongPress = { i -> onSentenceLongClick(i) }
                                )
                            }
                        }
                        if (!followVoice) {
                            ExtendedFloatingActionButton(
                                onClick = { followVoice = true },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            ) { Text(stringResource(AppR.string.player_to_current)) }
                        }
                    } else if (showList) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(sentences) { i, sentence ->
                                SentenceItem(text = sentence, isActive = i == currentIndex,
                                    spokenFraction = if (i == currentIndex) currentFraction else -1f, onClick = {
                                    playFrom(i)
                                }, onLongClick = { onSentenceLongClick(i) })
                            }
                        }
                        if (!followVoice) {
                            ExtendedFloatingActionButton(
                                onClick = { followVoice = true },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            ) { Text(stringResource(AppR.string.player_to_current)) }
                        }
                    } else {
                        KaraokeText(sentences, index, onClick = { showList = true }, onLongClick = { onSentenceLongClick(index) },
                            fraction = currentFraction)
                    }
                }

                // ---- progress and controls: one compact panel, as in a reader app ----
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 6.dp)) {
                        var seeking by remember { mutableStateOf<Float?>(null) }
                        val fraction = if (n > 1) index.toFloat() / (n - 1) else 0f
                        val shown = (seeking?.let { Math.round(it * (n - 1)) } ?: index)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "-" + formatTime(remainingSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = seeking ?: fraction,
                                onValueChange = { seeking = it },
                                onValueChangeFinished = {
                                    seeking?.let { if (n > 1) playFrom(Math.round(it * (n - 1))) }
                                    seeking = null
                                },
                                enabled = n > 1,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(
                                "${shown + 1}/$n",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpeedButton(sliderSpeed, onChange = { sliderSpeed = it }, onDone = { onSpeedChange(sliderSpeed) })
                            SkipButton(
                                icon = Icons.Default.SkipPrevious,
                                description = stringResource(AppR.string.player_prev),
                                enabled = index > 0,
                                onClick = { playFrom((index - 1).coerceAtLeast(0)) },
                                onLongClick = { playFrom((index - 5).coerceAtLeast(0)) }
                            )
                            FilledIconButton(
                                onClick = {
                                    if (!isPlaying && showList && !followVoice && n > 0) {
                                        // scrolled by hand: start from the top phrase on screen
                                        playFrom(topVisibleChunk())
                                    } else onPlayPauseClick()
                                },
                                shape = CircleShape,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            SkipButton(
                                icon = Icons.Default.SkipNext,
                                description = stringResource(AppR.string.player_next),
                                enabled = index < n - 1,
                                onClick = { playFrom((index + 1).coerceAtMost(n - 1)) },
                                onLongClick = { playFrom((index + 5).coerceAtMost(n - 1)) }
                            )
                            IconButton(onClick = { showList = !showList }) {
                                Icon(
                                    if (showList) Icons.Default.Subject else Icons.AutoMirrored.Filled.List,
                                    contentDescription = stringResource(AppR.string.player_toggle_list)
                                )
                            }
                        }
                    }
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

/** One paragraph of the book page; the phrase being read is shaded, the sentence heard — darker. */
@Composable
private fun BookParagraph(
    book: BookPage,
    p: Int,
    sentences: List<String>,
    current: Int,
    fraction: Float,
    onLayout: (TextLayoutResult) -> Unit,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val pr = book.paragraphs[p]
    val shade = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val strong = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val ann = buildAnnotatedString {
        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 1.5.em))) {
            append(book.text.substring(pr.first, pr.last + 1))
        }
        val r = book.ranges.getOrNull(current)
        if (r != null && r.last >= pr.first && r.first <= pr.last) {
            fun shadeRange(a: Int, b: Int, color: androidx.compose.ui.graphics.Color) {
                val s = maxOf(a, pr.first) - pr.first
                val e = minOf(b, pr.last) + 1 - pr.first
                if (e > s) addStyle(SpanStyle(background = color), s, e)
            }
            // while reading: just the sentence being heard, as in Moon+; paused: the whole phrase
            val heard = if (fraction < 0f) null else
                com.brahmadeo.supertonic.tts.utils.SpokenProgress.sentenceAt(sentences[current], fraction)
                    ?.let { mapSub(book.text, r, sentences[current], it) }
            if (heard != null) shadeRange(heard.first, heard.last, strong) else shadeRange(r.first, r.last, shade)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    Text(
        ann,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Serif,
            fontSize = 19.sp,
            lineHeight = 1.45.em
        ),
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it; onLayout(it) },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(book, p) {
                detectTapGestures(
                    onTap = { pos -> layout?.let { tap(book.chunkAt(pr.first + it.getOffsetForPosition(pos))) } },
                    onLongPress = { pos -> layout?.let { long(book.chunkAt(pr.first + it.getOffsetForPosition(pos))) } }
                )
            }
    )
}

/** Speed: a small "1.0×" button; tap — a slider and quick values. */
@Composable
private fun SpeedButton(speed: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(String.format(java.util.Locale.US, "%.1f×", speed), style = MaterialTheme.typography.titleSmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; onDone() }) {
            Column(modifier = Modifier.width(290.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    stringResource(AppR.string.speed_label) + "  " + String.format(java.util.Locale.US, "%.1f×", speed),
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = speed,
                    onValueChange = { onChange(Math.round(it * 10f) / 10f) },
                    onValueChangeFinished = onDone,
                    valueRange = 0.5f..2.5f,
                    steps = 19
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    for (v in listOf(1.0f, 1.3f, 1.6f, 2.0f)) {
                        TextButton(onClick = { onChange(v); onDone(); open = false }) {
                            Text(String.format(java.util.Locale.US, "%.1f", v))
                        }
                    }
                }
            }
        }
    }
}

/** A phrase-skip button; a long press jumps five phrases. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkipButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(32.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}

/** [text] with the word being spoken at [fraction] lit (karaoke); fraction < 0 → plain. */
@Composable
private fun withSpokenWord(text: String, fraction: Float): androidx.compose.ui.text.AnnotatedString {
    if (fraction < 0f) return androidx.compose.ui.text.AnnotatedString(text)
    // the sentence being heard, shaded — calmer than a jumping word
    val r = com.brahmadeo.supertonic.tts.utils.SpokenProgress.sentenceAt(text, fraction) ?: return androidx.compose.ui.text.AnnotatedString(text)
    val lit = androidx.compose.ui.text.SpanStyle(
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    )
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        addStyle(lit, r.first, r.last + 1)
    }
}

/** The sentence being read, large; the previous and next ones faded. Tap: the full list. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun KaraokeText(sentences: List<String>, index: Int, onClick: () -> Unit, onLongClick: () -> Unit = {}, fraction: Float = -1f) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
            withSpokenWord(sentences[index], fraction),
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

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SentenceItem(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    spokenFraction: Float = -1f
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                text = if (isActive) withSpokenWord(text, spokenFraction) else androidx.compose.ui.text.AnnotatedString(text),
                style = if (isActive) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
