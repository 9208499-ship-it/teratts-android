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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
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
    exportNote: String? = null,
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
    title: String = "",               // shown in the top bar (the chapter's first line)
    // a book: its contents and the chapters around this one (null — not a book)
    onToc: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    // sleep timer: minutes left, SleepTimer.CHAPTER_END, or null (off); set: minutes / CHAPTER_END / 0
    sleepLeft: Int? = null,
    onSleepTimer: (Int) -> Unit = {},
    // a double tap on a word of the book page: its stress, for the user's dictionary
    onWordDoubleTap: (String, String?, String?) -> Unit = { _, _, _ -> },
    // search the whole book (null — not a book)
    onSearch: (() -> Unit)? = null,
    // the chapter's number in the book and how many there are ("3/37"), null — not a book / not known yet
    chapterNumber: Pair<Int, Int>? = null,
    // the share of the whole book read at a share (0..1) of this chapter, null — not known
    bookShare: ((Float) -> Float?)? = null,
    // stop reading aloud but stay with the text (the reader goes on by eye)
    onStopReading: () -> Unit = onStopClick,
    // all the settings / straight to "Голос и модель"
    onSettings: () -> Unit = {},
    onVoiceSettings: () -> Unit = onSettings,
    // the reading's sheet (⚙ on the panel): the voice now (its file), choosing a voice / the model / the roles
    voiceNow: String = "",
    onVoicePick: (String) -> Unit = {},
    onEngine: (String) -> Unit = {},
    onRoles: (Boolean) -> Unit = {},
    // ★: phrase i to the book's quotes; the list of the quotes
    onQuote: ((Int) -> Unit)? = null,
    onQuotes: (() -> Unit)? = null,
    // read by eye: the phrase at the top after scrolling / turning a page by hand (kept as the book's place)
    onEyePlace: (Int) -> Unit = {},
    // bumped when a place in this chapter is chosen (search, contents, a quote): the screen goes to it
    jumpSeq: Int = 0
) {
    // ⚙ while reading: the sheet of the reading's settings
    var sheetOpen by remember { mutableStateOf(false) }
    // the page under the text: plain, paper, parchment, dark (chosen in the reading's sheet)
    val pageCtx = androidx.compose.ui.platform.LocalContext.current
    // the type, and the user's own page picture (taken again when it is changed)
    remember { BookFont.load(pageCtx) }
    val skinVersion = com.brahmadeo.supertonic.tts.utils.Skins.version.intValue
    remember(skinVersion) { PageStyles.loadCustom(pageCtx); skinVersion }
    var pageStyle by remember { mutableStateOf(PageStyles.get(pageCtx)) }
    // the user's picture for the page: chosen in the reading sheet
    val pagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && com.brahmadeo.supertonic.tts.utils.Skins.importPage(pageCtx, uri)) {
            PageStyles.loadCustom(pageCtx)
            pageStyle = PageStyles.CUSTOM; PageStyles.set(pageCtx, PageStyles.CUSTOM)
        } else if (uri != null) android.widget.Toast.makeText(pageCtx, pageCtx.getString(AppR.string.skin_bad_picture), android.widget.Toast.LENGTH_SHORT).show()
    }
    // the book as pages to turn (or the ribbon), how a page turns, whether it rustles
    var pageMode by remember { mutableStateOf(PageMode.paged(pageCtx)) }
    var pageAnim by remember { mutableStateOf(PageMode.anim(pageCtx)) }
    var pageSound by remember { mutableStateOf(PageMode.sound(pageCtx)) }
    // the pages' own "phrase at the top" and "show phrase i" (the paged view registers them)
    var pagedTop by remember { mutableStateOf<(() -> Int)?>(null) }
    var pagedJump by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    // the book text, scrollable by finger, is the main view (karaoke is the second one)
    var showList by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val n = sentences.size
    val index = currentIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
    val book = remember(text, sentences) { bookPageOf(text, sentences) }
    val layouts = remember(book) { HashMap<Int, TextLayoutResult>() }

    // followVoice: the text scrolls after the voice until the user scrolls it by hand;
    // then "Play" starts from the top visible phrase (as in Moon+ Reader)
    // another chapter (from the contents or the search) follows the voice / the found place again
    var followVoice by remember(text) { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) followVoice = false }
    // read by eye: the phrase at the top after scrolling / turning by hand — the place shown (%, the
    // line, ★) follows it, not the voice's place; and where the pages stand (kept through a rotation)
    var eyeAt by remember(text) { mutableStateOf<Int?>(null) }
    var pageAnchor by remember(text) { mutableIntStateOf(-1) }
    val eyePlace: (Int) -> Unit = { i -> eyeAt = i; onEyePlace(i) }
    // the chapter cut into pages, kept while the voice reads in the ribbon (cutting it again on every
    // stop made the screen freeze for a moment)
    val pageCache = remember(book) { PageCache() }
    // a place chosen in this chapter: shown even after scrolling by hand
    LaunchedEffect(jumpSeq) { if (jumpSeq > 0) { followVoice = true; pageAnchor = -1; eyeAt = null } }
    // what is being heard right now: the sentence inside the phrase (a phrase may be long and run
    // over several short paragraphs — following only its start let the voice go on below the
    // control panel, out of sight)
    val heard: IntRange? = if (book != null && index in sentences.indices) {
        val r = book.ranges.getOrNull(index)
        if (r == null) null
        else if (currentFraction < 0f) r
        else com.brahmadeo.supertonic.tts.utils.SpokenProgress.sentenceAt(sentences[index], currentFraction)
            ?.let { mapSub(book.text, r, sentences[index], it) } ?: r
    } else null
    val heardKey = heard?.first ?: -1
    LaunchedEffect(currentIndex, heardKey, showList, followVoice, book, isServiceActive || isPlaying) {
        if (!showList || !followVoice || currentIndex !in sentences.indices || (pageMode && book != null && !(isServiceActive || isPlaying))) return@LaunchedEffect
        if (book == null) { listState.animateScrollToItem(currentIndex); return@LaunchedEffect }
        val target = heard ?: return@LaunchedEffect
        val p1 = book.paraAt(target.first)
        val p2 = book.paraAt(target.last)
        fun lineY(p: Int, abs: Int, bottom: Boolean): Float? {
            val lay = layouts[p] ?: return null
            val len = lay.layoutInput.text.length
            val line = lay.getLineForOffset((abs - book.paragraphs[p].first).coerceIn(0, (len - 1).coerceAtLeast(0)))
            return if (bottom) lay.getLineBottom(line) else lay.getLineTop(line)
        }
        val info = listState.layoutInfo
        val view = info.viewportEndOffset - info.viewportStartOffset
        val item1 = info.visibleItemsInfo.firstOrNull { it.index == p1 }
        val item2 = info.visibleItemsInfo.firstOrNull { it.index == p2 }
        val top = lineY(p1, target.first, false)
        val bottom = lineY(p2, target.last, true)
        // turn the page only when what is heard leaves the screen (or comes close to its lower
        // edge — a few lines are kept free above the panel), as a reader does
        if (item1 != null && item2 != null && top != null && bottom != null &&
            item1.offset + top >= info.viewportStartOffset &&
            item2.offset + bottom <= info.viewportEndOffset - view / 8) return@LaunchedEffect
        val pad = view / 8
        listState.animateScrollToItem(p1, ((top ?: 0f).toInt() - pad).coerceAtLeast(0))
    }
    val playFrom: (Int) -> Unit = { i -> followVoice = true; onItemClick(i) }
    // the phrase at the top of the screen (after scrolling by hand)
    fun topVisibleChunk(): Int {
        // pages: the first phrase of the page shown
        pagedTop?.let { if (pageMode && showList && book != null) return it() }
        if (book == null) return listState.firstVisibleItemIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
        val p = listState.firstVisibleItemIndex.coerceIn(0, book.paragraphs.size - 1)
        val local = layouts[p]?.getOffsetForPosition(Offset(0f, listState.firstVisibleItemScrollOffset.toFloat())) ?: 0
        return book.chunkAt(book.paragraphs[p].first + local).coerceIn(0, (n - 1).coerceAtLeast(0))
    }
    // ⏪ ⏩ a paragraph back / on: the start of this paragraph (or of the one before, if already
    // there), the start of the next one; plain text without paragraphs — five phrases
    fun paraStep(d: Int): Int {
        if (n == 0) return 0
        val pc = book?.paraOfChunk ?: return (index + 5 * d).coerceIn(0, n - 1)
        val p = pc.getOrElse(index) { 0 }
        if (d > 0) {
            for (i in index + 1 until n) if (pc.getOrElse(i) { p } > p) return i
            return n - 1
        }
        var first = index
        while (first > 0 && pc.getOrElse(first - 1) { -1 } == p) first--
        if (first < index) return first
        if (first == 0) return 0
        val pp = pc.getOrElse(first - 1) { 0 }
        var s = first - 1
        while (s > 0 && pc.getOrElse(s - 1) { -1 } == pp) s--
        return s
    }

    // time left: remaining characters at the current pace
    var sliderSpeed by remember(speed) { mutableStateOf(speed) }
    val remainingChars = remember(sentences, index) { sentences.drop(index).sumOf { it.length } }
    val remainingSeconds = (remainingChars / (CHARS_PER_SECOND * sliderSpeed.coerceAtLeast(0.3f))).toInt()

    // As in Moon+ Reader: the text takes the whole screen. Not reading aloud — a tap shows the bars
    // (title above; below: the place in the book and the buttons, 🔊 first); reading aloud — the
    // narrow panel of the voice stays below and a tap on the text reads from there.
    val speaking = isServiceActive || isPlaying
    // read by eye: where the ribbon was left after scrolling by hand is the book's place (the next
    // opening of the book comes back to it, as after listening)
    LaunchedEffect(listState, speaking, book) {
        if (speaking) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving && !followVoice && !(pageMode && book != null)) eyePlace(topVisibleChunk())
        }
    }
    LaunchedEffect(speaking) { if (speaking) { pageAnchor = -1; eyeAt = null } }
    var chrome by remember { mutableStateOf(false) }
    // reading starts: the bars go; it stops (■, the sleep timer, a long pause): they come back, so
    // "play" is at hand at once
    var wasSpeaking by remember { mutableStateOf(speaking) }
    LaunchedEffect(speaking) {
        if (speaking) chrome = false else if (wasSpeaking) chrome = true
        wasSpeaking = speaking
    }
    // the place shown: the voice's, or, read by eye after scrolling, the phrase at the top
    val shown = if (!speaking && !followVoice) (eyeAt ?: index) else index
    // a long press on the text: read from here / who says this
    var longFor by remember { mutableStateOf<Int?>(null) }
    val tapText: (Int) -> Unit = { i -> if (speaking) playFrom(i) else chrome = !chrome }
    // reading by eye: the screen stays on; listening: it may go dark
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(speaking) {
        view.keepScreenOn = !speaking
        onDispose { view.keepScreenOn = false }
    }
    // the place: share of this chapter (by characters where the text is laid out) and of the whole book
    val chapterShare = run {
        val r = book?.ranges?.getOrNull(shown)
        if (n == 0) 0f else if (book != null && r != null) r.first.toFloat() / book.text.length.coerceAtLeast(1) else shown.toFloat() / n
    }
    // how the chapter is voiced: from the prepared file or now (only when preparing is in play)
    val source = com.brahmadeo.supertonic.tts.utils.BookPrep.source.value
    val sourceShort = source?.let {
        if (it == stringResource(AppR.string.src_file)) stringResource(AppR.string.src_file_short) else stringResource(AppR.string.src_live_short)
    }
    val placeLabel = listOfNotNull(
        chapterNumber?.let { "${it.first}/${it.second}" },
        (bookShare?.invoke(chapterShare) ?: chapterShare).let { String.format(java.util.Locale.getDefault(), "%.1f %%", it * 100f) },
        sourceShort
    ).joinToString("  ·  ")
    val startReading: () -> Unit = {
        if (isServiceActive) onPlayPauseClick()
        else if (n > 0) playFrom(if (followVoice) index else topVisibleChunk())
    }

    longFor?.let { i ->
        AlertDialog(
            onDismissRequest = { longFor = null },
            text = {
                Column {
                    Text(sentences.getOrNull(i)?.take(200) ?: "", style = MaterialTheme.typography.bodyMedium)
                    if (onQuote != null) TextButton(onClick = { longFor = null; onQuote(i) }) { Text(stringResource(AppR.string.player_quote)) }
                }
            },
            confirmButton = { TextButton(onClick = { longFor = null; playFrom(i) }) { Text(stringResource(AppR.string.player_read_here)) } },
            dismissButton = { TextButton(onClick = { longFor = null; onSentenceLongClick(i) }) { Text(stringResource(AppR.string.player_who_says)) } }
        )
    }

    if (sheetOpen) {
        ReadingSheet(
            onDismiss = { sheetOpen = false },
            speed = sliderSpeed,
            onSpeedChange = { v -> sliderSpeed = v; onSpeedChange(v) },
            sleepLeft = sleepLeft,
            onSleepTimer = onSleepTimer,
            voiceNow = voiceNow,
            onVoicePick = onVoicePick,
            onEngine = onEngine,
            onRoles = onRoles,
            onQuote = onQuote?.let { q -> { q(if (speaking || followVoice) index else topVisibleChunk()) } },
            onQuotes = onQuotes,
            onToc = onToc,
            onSearch = onSearch,
            onCharacters = onCharactersClick,
            showList = showList,
            onToggleList = { showList = !showList },
            onSettings = onSettings,
            onVoiceSettings = onVoiceSettings,
            speaking = speaking,
            pageStyle = pageStyle,
            onPageStyle = { s -> pageStyle = s; PageStyles.set(pageCtx, s) },
            hasCustomPage = com.brahmadeo.supertonic.tts.utils.Skins.version.intValue >= 0 && com.brahmadeo.supertonic.tts.utils.Skins.hasPage(pageCtx),
            onPickPage = { pagePicker.launch(arrayOf("image/*")) },
            fontSize = BookFont.size.floatValue, onFontSize = { v -> BookFont.setSize(pageCtx, v) },
            fontFamily = BookFont.family.value, onFontFamily = { f -> BookFont.setFamily(pageCtx, f) },
            paged = pageMode, onPaged = { v -> pageMode = v; PageMode.setPaged(pageCtx, v) },
            pageAnim = pageAnim, onPageAnim = { a -> pageAnim = a; PageMode.setAnim(pageCtx, a) },
            pageSound = pageSound, onPageSound = { v -> pageSound = v; PageMode.setSound(pageCtx, v) }
        )
    }

    PageTheme(pageStyle) {
    Scaffold(
        topBar = {
            if (chrome && !speaking) {
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
                        PlayerMoreMenu(
                            showList = showList, onToggleList = { showList = !showList },
                            onExport = if (!isExporting) onExportClick else null,
                            onVoiceSettings = onVoiceSettings, onSettings = onSettings, onQuotes = onQuotes
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            }
        }
    ) { paddingValues ->
        // reading aloud: always the ribbon (it scrolls after the voice, the phrase heard is lit); pages — by eye
        val paged = pageMode && showList && book != null && !speaking
        Box(
            modifier = Modifier
                .fillMaxSize()
                // pages: under the bars (the top bar lies over the page, as in readers)
                .padding(if (paged) PaddingValues(bottom = paddingValues.calculateBottomPadding())
                    else PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding()))
        ) {
            if (paged && book != null) {
                PagedBookView(
                    book = book, sentences = sentences, index = index,
                    fraction = currentFraction,
                    heardAt = if (pageAnchor >= 0) pageAnchor else heard?.first ?: book.ranges.getOrNull(index)?.first ?: -1,
                    speaking = speaking, followVoice = followVoice, onFollow = { followVoice = it },
                    anim = pageAnim, pageStyle = pageStyle,
                    // only the line of the place under the text: the bars, when shown, lie over the page
                    reserve = 28.dp,
                    cache = pageCache, onAnchor = { a -> pageAnchor = a },
                    onBlankTap = { chrome = !chrome },
                    footer = if (!speaking && !chrome) placeLabel else null,
                    onTap = { i -> tapText(i) }, onLongPress = { i -> longFor = i }, onWord = onWordDoubleTap,
                    onNextChapter = onNextChapter,
                    register = { t, j -> pagedTop = t; pagedJump = j },
                    onPageShown = eyePlace,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- the text ----
                Box(modifier = Modifier.weight(1f).fillMaxWidth().pageTexture(if (paged) PageStyles.PLAIN else pageStyle).padding(horizontal = 20.dp)) {
                    if (paged) {
                        // the pages are drawn under this column (PagedBookView)
                    } else if (showList && book != null) {
                        // a phrase may run over several short paragraphs (dialogue lines)
                        val curParas = if (n == 0) IntRange.EMPTY else
                            book.ranges[index]?.let { book.paraAt(it.first)..book.paraAt(it.last) }
                                ?: book.paraOfChunk[index]..book.paraOfChunk[index]
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTapGestures(onTap = { if (!(isServiceActive || isPlaying)) chrome = !chrome })
                            }
                        ) {
                            items(book.paragraphs.size) { p ->
                                BookParagraph(
                                    book = book, p = p, sentences = sentences,
                                    current = if (p in curParas) index else -1,
                                    fraction = if (p in curParas) currentFraction else -1f,
                                    onLayout = { layouts[p] = it },
                                    onTap = { i -> tapText(i) },
                                    onLongPress = { i -> longFor = i },
                                    onWord = onWordDoubleTap
                                )
                            }
                        }
                        ToCurrentButton(!followVoice && speaking, listState.isScrollInProgress) { followVoice = true }
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
                                    tapText(i)
                                }, onLongClick = { longFor = i })
                            }
                        }
                        ToCurrentButton(!followVoice && speaking, listState.isScrollInProgress) { followVoice = true }
                    } else {
                        KaraokeText(sentences, index, onClick = { if (speaking) showList = true else chrome = !chrome },
                            onLongClick = { longFor = index }, fraction = currentFraction)
                    }
                    // the bars hidden: only the place, small, in the corner (as Moon+ does)
                    if (!speaking && !chrome && !paged) {
                        Text(
                            placeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp)
                        )
                    }
                }

                if (speaking || chrome) {
                    // ---- the place and the buttons: one narrow panel ----
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 6.dp)) {
                            var seeking by remember { mutableStateOf<Float?>(null) }
                            val fraction = if (n > 1) shown.toFloat() / (n - 1) else 0f
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // ‹ › around the line: the chapter before / after, as in Moon+
                                if (onPrevChapter != null) {
                                    IconButton(onClick = onPrevChapter, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = stringResource(AppR.string.player_prev_chapter))
                                    }
                                }
                                if (speaking) {
                                    Text(
                                        "-" + formatTime(remainingSeconds),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Slider(
                                    value = seeking ?: fraction,
                                    onValueChange = { seeking = it },
                                    onValueChangeFinished = {
                                        seeking?.let { v ->
                                            if (n > 1) {
                                                val i = Math.round(v * (n - 1))
                                                // reading: go on from there; not: just show that place
                                                if (speaking) playFrom(i) else {
                                                    followVoice = false
                                                    eyePlace(i)
                                                    val jump = pagedJump
                                                    if (pageMode && showList && jump != null) jump(i)
                                                    else onItemClickSilently(i, book, layouts, listState, scope)
                                                }
                                            }
                                        }
                                        seeking = null
                                    },
                                    enabled = n > 1,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                )
                                Text(
                                    placeLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (onNextChapter != null) {
                                    IconButton(onClick = onNextChapter, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = stringResource(AppR.string.player_next_chapter))
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (speaking) {
                                    // the voice (after eReader Prestigio): ⚙ the reading's sheet, ⏪ paragraph back,
                                    // ⏮ phrase back, play/pause, ⏭ phrase on, ⏩ paragraph on, ■ stop (stay with the text)
                                    IconButton(onClick = { sheetOpen = true }, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.Settings, contentDescription = stringResource(AppR.string.sheet_title))
                                    }
                                    SkipButton(
                                        icon = Icons.Default.FastRewind,
                                        description = stringResource(AppR.string.player_prev_para),
                                        enabled = index > 0,
                                        onClick = { playFrom(paraStep(-1)) },
                                        onLongClick = { playFrom(paraStep(-1)) }
                                    )
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
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                    SkipButton(
                                        icon = Icons.Default.SkipNext,
                                        description = stringResource(AppR.string.player_next),
                                        enabled = index < n - 1,
                                        onClick = { playFrom((index + 1).coerceAtMost(n - 1)) },
                                        onLongClick = { playFrom((index + 5).coerceAtMost(n - 1)) }
                                    )
                                    SkipButton(
                                        icon = Icons.Default.FastForward,
                                        description = stringResource(AppR.string.player_next_para),
                                        enabled = index < n - 1,
                                        onClick = { playFrom(paraStep(1)) },
                                        onLongClick = { playFrom(paraStep(1)) }
                                    )
                                    IconButton(onClick = onStopReading, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.Stop, contentDescription = stringResource(AppR.string.player_stop_reading))
                                    }
                                } else {
                                    // the reader: read aloud, contents, search, characters, sleep timer
                                    IconButton(onClick = startReading) {
                                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(AppR.string.player_read_aloud),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                    if (onToc != null) {
                                        IconButton(onClick = onToc) {
                                            Icon(Icons.Default.Toc, contentDescription = stringResource(AppR.string.player_toc))
                                        }
                                    }
                                    if (onSearch != null) {
                                        IconButton(onClick = onSearch) {
                                            Icon(Icons.Default.Search, contentDescription = stringResource(AppR.string.search_title))
                                        }
                                    }
                                    IconButton(onClick = onCharactersClick) {
                                        Icon(Icons.Default.People, contentDescription = stringResource(AppR.string.characters_title))
                                    }
                                    SleepButton(sleepLeft, onSleepTimer)
                                    // the reading's sheet here too (the page, the voice…; all the settings from it)
                                    IconButton(onClick = { sheetOpen = true }) {
                                        Icon(Icons.Default.Settings, contentDescription = stringResource(AppR.string.sheet_title))
                                    }
                                }
                            }
                            // the sleep timer while it is on: how long is left, +15 min, off
                            if (speaking && sleepLeft != null) SleepStrip(sleepLeft, onSleepTimer)
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
                                        text = exportNote ?: "$exportCurrent / $exportTotal",
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
    } // PageTheme
}

/** One piece of a paragraph on a page: its lines [first..last], where they start in its layout, how tall. */
private class PageFrag(val p: Int, val first: Int, val last: Int, val top: Int, val height: Int)

/** A page: its pieces and where it starts in the chapter's text. */
private class BookPageOut(val frags: List<PageFrag>, val start: Int)

/** The pages of one chapter for one size of the screen (kept while the ribbon is shown). */
/**
 * «К текущему месту»: shown when the text was scrolled away from the voice, and gone by itself after
 * a few seconds of no scrolling (it covered the text: pallado, 4PDA) — back on the next scroll.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ToCurrentButton(show: Boolean, scrolling: Boolean, onClick: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(show, scrolling) {
        visible = show
        if (show && !scrolling) { kotlinx.coroutines.delay(5000); visible = false }
    }
    if (visible) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) { Text(stringResource(AppR.string.player_to_current)) }
    }
}

private class PageCache {
    var key: Any? = null
    var pages: List<BookPageOut> = emptyList()
}

/** The chapter cut into pages of [heightPx] (the same type the page is drawn with). */
private fun paginate(
    book: BookPage, measure: (Int) -> TextLayoutResult, heightPx: Int, gapPx: Int
): List<BookPageOut> {
    val pages = ArrayList<BookPageOut>()
    var cur = ArrayList<PageFrag>()
    var start = 0
    var y = 0
    fun close() { if (cur.isNotEmpty()) { pages.add(BookPageOut(cur, start)); cur = ArrayList(); y = 0 } }
    for (p in book.paragraphs.indices) {
        val lay = measure(p)
        val lines = lay.lineCount
        if (cur.isNotEmpty()) y += gapPx
        var a = 0
        while (a < lines) {
            var b = a
            while (b < lines && (lay.getLineBottom(b) - lay.getLineTop(a)).toInt() <= heightPx - y) b++
            if (b == a) {
                if (cur.isEmpty()) b = a + 1 else { close(); continue }
            }
            if (cur.isEmpty()) start = book.paragraphs[p].first + lay.getLineStart(a)
            val top = lay.getLineTop(a).toInt()
            val h = (lay.getLineBottom(b - 1) - lay.getLineTop(a)).toInt()
            cur.add(PageFrag(p, a, b - 1, top, h))
            y += h
            a = b
            if (a < lines) close()
        }
    }
    close()
    return pages
}

/**
 * The chapter as pages to turn with a finger (as in paper-book readers): the same paragraphs, stress,
 * taps and highlight as the ribbon, cut to the screen. While the voice reads, the page turns after it;
 * a turn by hand (a swipe, or a tap at the left / right edge) leaves the voice where it is. The page
 * slides away or is turned over like a leaf; a turn by hand rustles (if so chosen). At the end of the
 * chapter one more swipe opens the next one.
 */
@Composable
private fun PagedBookView(
    book: BookPage,
    sentences: List<String>,
    index: Int,
    fraction: Float,
    heardAt: Int,
    speaking: Boolean,
    followVoice: Boolean,
    onFollow: (Boolean) -> Unit,
    anim: String,
    pageStyle: String,
    reserve: androidx.compose.ui.unit.Dp,
    footer: String?,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onWord: (String, String?, String?) -> Unit,
    onNextChapter: (() -> Unit)?,
    register: (top: (() -> Int)?, jump: ((Int) -> Unit)?) -> Unit,
    onPageShown: (Int) -> Unit = {},
    cache: PageCache = PageCache(),
    onAnchor: (Int) -> Unit = {},
    onBlankTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dens = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    // the type chosen in the reading sheet (size, with serifs or without)
    val style = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookFont.fontFamily(), fontSize = BookFont.size.floatValue.sp, lineHeight = 1.45.em)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bg = MaterialTheme.colorScheme.background
    Column(modifier = modifier.background(bg).pageTexture(pageStyle).windowInsetsPadding(WindowInsets.statusBars)) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val padX = with(dens) { 20.dp.roundToPx() }
            val padTop = with(dens) { 12.dp.roundToPx() }
            val padBottom = with(dens) { 6.dp.roundToPx() }
            val gap = with(dens) { 10.dp.roundToPx() }
            val wPx = constraints.maxWidth - 2 * padX
            val hPx = constraints.maxHeight - padTop - padBottom
            val pageKey = listOf(wPx, hPx, style, dens.density, dens.fontScale)
            val pages: List<BookPageOut> = if (wPx <= 0 || hPx <= 0) emptyList()
            else if (cache.key == pageKey) cache.pages
            else remember(book, wPx, hPx, style, dens) {
                paginate(book, { p ->
                    val r = book.paragraphs[p]
                    measurer.measure(
                        buildAnnotatedString {
                            withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 1.5.em))) { append(book.text.substring(r.first, r.last + 1)) }
                        },
                        style = style,
                        constraints = Constraints(maxWidth = wPx)
                    )
                }, hPx, gap).also { cache.key = pageKey; cache.pages = it }
            }
            val starts = remember(pages) { IntArray(pages.size) { pages[it].start } }
            fun pageOf(abs: Int): Int {
                var lo = 0; var hi = starts.size - 1; var ans = 0
                while (lo <= hi) { val m = (lo + hi) ushr 1; if (starts[m] <= abs) { ans = m; lo = m + 1 } else hi = m - 1 }
                return ans
            }
            val extra = if (onNextChapter != null) 1 else 0
            if (pages.isNotEmpty()) key(pages) {
                val pager = rememberPagerState(initialPage = pageOf(heardAt.coerceAtLeast(0))) { pages.size + extra }
                val scope = rememberCoroutineScope()
                // a swipe by hand: the pages no longer follow the voice (the button brings them back)
                val dragged by pager.interactionSource.collectIsDraggedAsState()
                LaunchedEffect(dragged) { if (dragged) onFollow(false) }
                // the voice moved on to another page: turn it (silently)
                LaunchedEffect(heardAt, followVoice, speaking) {
                    if (!followVoice || heardAt < 0) return@LaunchedEffect
                    val t = pageOf(heardAt)
                    if (t != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(t)
                }
                // a page turned by hand rustles; the page after the last one opens the next chapter
                var lastSettled by remember { mutableStateOf(pager.settledPage) }
                LaunchedEffect(pager.settledPage) {
                    val s = pager.settledPage
                    val lastSettled0 = lastSettled
                    if (s != lastSettled && !speaking) PageSound.play(ctx)
                    lastSettled = s
                    if (s != lastSettled0 && !speaking && s < pages.size) { onAnchor(starts[s]); onPageShown(book.chunkAt(starts[s])) }
                    if (s >= pages.size) onNextChapter?.invoke()
                }
                DisposableEffect(pages) {
                    register(
                        { book.chunkAt(starts[pager.currentPage.coerceIn(0, pages.size - 1)]) },
                        { i -> book.ranges.getOrNull(i)?.let { r -> scope.launch { pager.scrollToPage(pageOf(r.first)) } } }
                    )
                    onDispose { register(null, null) }
                }
                val curParas = book.ranges.getOrNull(index)?.let { book.paraAt(it.first)..book.paraAt(it.last) } ?: IntRange.EMPTY
                HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                    val o = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (anim == PageMode.FLIP && o > 0f) 1f else 0f)
                            .graphicsLayer {
                                if (anim == PageMode.FLIP) {
                                    if (o > 0f) {
                                        // the leaf being turned: it stays, swinging round its left edge
                                        translationX = o * size.width
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        rotationY = -90f * o.coerceIn(0f, 1f)
                                        cameraDistance = 14f * density
                                    } else if (o < 0f) {
                                        // the page under it: stays where it is
                                        translationX = o * size.width
                                    }
                                }
                            }
                            .background(bg)
                            .pageTexture(pageStyle)
                            // a tap at the left / right edge turns the page (a swipe still goes to the pages)
                            .pointerInput(pager) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val w = size.width
                                    val zone = if (down.position.x < w * 0.16f) -1 else if (down.position.x > w * 0.84f) 1 else 0
                                    if (zone != 0) {
                                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop &&
                                            up.uptimeMillis - down.uptimeMillis < 400) {
                                            up.consume()
                                            val to = pager.currentPage + zone
                                            if (to in 0 until pager.pageCount) {
                                                onFollow(false)
                                                scope.launch { pager.animateScrollToPage(to) }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        if (page >= pages.size) {
                            Text(
                                stringResource(AppR.string.page_next_chapter),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            // a tap on what is not text (between paragraphs, the margins, below the last line):
                            // the bars come and go, as on the text
                            Column(modifier = Modifier.fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures(onTap = { onBlankTap() }) }
                                .padding(horizontal = 20.dp).padding(top = 12.dp)) {
                                val frags = pages[page].frags
                                for ((k, f) in frags.withIndex()) {
                                    if (k > 0) Spacer(modifier = Modifier.height(10.dp))
                                    Box(modifier = Modifier.fillMaxWidth().height(with(dens) { f.height.toDp() }).clipToBounds()) {
                                        Box(modifier = Modifier.layout { m, c ->
                                            val pl = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                            layout(pl.width, c.maxHeight) { pl.place(0, -f.top) }
                                        }) {
                                            BookParagraph(
                                                book = book, p = f.p, sentences = sentences,
                                                current = if (f.p in curParas) index else -1,
                                                fraction = if (f.p in curParas) fraction else -1f,
                                                onLayout = { },
                                                onTap = onTap, onLongPress = onLongPress, onWord = onWord
                                            )
                                        }
                                    }
                                }
                            }
                            // the page's shade while the leaf above it is being turned
                            if (anim == PageMode.FLIP && o < 0f) {
                                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f * (-o).coerceIn(0f, 1f))))
                            }
                        }
                    }
                }
                if (!followVoice && speaking) {
                    ExtendedFloatingActionButton(
                        onClick = { onFollow(true) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    ) { Text(stringResource(AppR.string.player_to_current)) }
                }
                // under the text: the page of the chapter and the place in the book (the panel covers it)
                if (footer != null) {
                    Text(
                        "${(pager.currentPage + 1).coerceAtMost(pages.size)} / ${pages.size}  ·  $footer",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomCenter).offset(y = reserve - 18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(reserve))
    }
}

/** Shows phrase [i] at the top of the screen without reading it (the progress line while reading by eye). */
private fun onItemClickSilently(
    i: Int, book: BookPage?, layouts: Map<Int, TextLayoutResult>,
    listState: androidx.compose.foundation.lazy.LazyListState, scope: kotlinx.coroutines.CoroutineScope
) {
    scope.launch {
        if (book == null) { listState.scrollToItem(i); return@launch }
        val r = book.ranges.getOrNull(i)
        val p = r?.let { book.paraAt(it.first) } ?: book.paraOfChunk.getOrNull(i) ?: return@launch
        val lay = layouts[p]
        val y = if (r != null && lay != null) {
            val len = lay.layoutInput.text.length
            lay.getLineTop(lay.getLineForOffset((r.first - book.paragraphs[p].first).coerceIn(0, (len - 1).coerceAtLeast(0)))).toInt()
        } else 0
        listState.scrollToItem(p, y)
    }
}

/**
 * "More": contents, search, characters (when not on the panel), saving to an audio file, and the
 * big-phrase view instead of the page.
 */
@Composable
private fun PlayerMoreMenu(
    showList: Boolean,
    onToggleList: () -> Unit,
    onToc: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onCharacters: (() -> Unit)? = null,
    onExport: (() -> Unit)?,
    horizontal: Boolean = false,
    onVoiceSettings: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onQuotes: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(if (horizontal) Icons.Default.MoreHoriz else Icons.Default.MoreVert, contentDescription = stringResource(AppR.string.player_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onToc?.let { f -> DropdownMenuItem(text = { Text(stringResource(AppR.string.player_toc)) }, onClick = { open = false; f() }) }
            onSearch?.let { f -> DropdownMenuItem(text = { Text(stringResource(AppR.string.search_title)) }, onClick = { open = false; f() }) }
            onCharacters?.let { f -> DropdownMenuItem(text = { Text(stringResource(AppR.string.characters_title)) }, onClick = { open = false; f() }) }
            onExport?.let { f -> DropdownMenuItem(text = { Text(stringResource(AppR.string.player_save_audio)) }, onClick = { open = false; f() }) }
            DropdownMenuItem(
                text = { Text(stringResource(if (showList) AppR.string.player_view_phrase else AppR.string.player_view_page)) },
                onClick = { open = false; onToggleList() }
            )
            onVoiceSettings?.let { f -> DropdownMenuItem(text = { Text(stringResource(AppR.string.player_voice_settings)) }, onClick = { open = false; f() }) }
            onQuotes?.let { f -> DropdownMenuItem(text = { Text(stringResource(AppR.string.quotes_title)) }, onClick = { open = false; f() }) }
        }
    }
}

/** The word around [i] in [s] ("кто-то" whole), without stress marks and soft hyphens; null if none. */
internal fun wordAt(s: String, i: Int): String? {
    if (s.isEmpty()) return null
    fun isW(c: Char) = c.isLetter() || c == '\u0301' || c == '\u00AD' || c == '+'
    var a = i.coerceIn(0, s.length - 1)
    if (!isW(s[a])) { if (a > 0 && isW(s[a - 1])) a-- else return null }
    var st = a
    while (st > 0 && (isW(s[st - 1]) || (s[st - 1] == '-' && st > 1 && s[st - 2].isLetter()))) st--
    var en = a + 1
    while (en < s.length && (isW(s[en]) || (s[en] == '-' && en + 1 < s.length && s[en + 1].isLetter()))) en++
    return s.substring(st, en).filter { it.isLetter() || it == '-' }.takeIf { w -> w.any { it.isLetter() } }
}

/** The word at [i] and the words right before and after it (only across spaces, not punctuation). */
internal fun wordsAround(s: String, i: Int): Triple<String?, String, String?>? {
    val w = wordAt(s, i) ?: return null
    fun isW(c: Char) = c.isLetter() || c == '\u0301' || c == '\u00AD' || c == '+' || c == '-'
    var a = i.coerceIn(0, s.length - 1)
    if (!isW(s[a]) && a > 0) a--
    var st = a; while (st > 0 && isW(s[st - 1])) st--
    var en = a + 1; while (en < s.length && isW(s[en])) en++
    var j = st - 1
    while (j >= 0 && (s[j] == ' ' || s[j] == '\u00A0')) j--
    val before = if (j in 0 until st - 1 && isW(s[j])) wordAt(s, j) else null
    var k = en
    while (k < s.length && (s[k] == ' ' || s[k] == '\u00A0')) k++
    val after = if (k > en && k < s.length && isW(s[k])) wordAt(s, k) else null
    return Triple(before, w, after)
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
    onLongPress: (Int) -> Unit,
    onWord: (String, String?, String?) -> Unit = { _, _, _ -> }
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
    val word by rememberUpdatedState(onWord)
    Text(
        ann,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = BookFont.fontFamily(),
            fontSize = BookFont.size.floatValue.sp,
            lineHeight = 1.45.em
        ),
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it; onLayout(it) },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(book, p) {
                detectTapGestures(
                    onTap = { pos -> layout?.let { tap(book.chunkAt(pr.first + it.getOffsetForPosition(pos))) } },
                    onLongPress = { pos -> layout?.let { long(book.chunkAt(pr.first + it.getOffsetForPosition(pos))) } },
                    // the word under the finger → "where is the stress?"
                    onDoubleTap = { pos ->
                        layout?.let { l ->
                            val para = book.text.substring(pr.first, pr.last + 1)
                            wordsAround(para, l.getOffsetForPosition(pos))?.let { (b, w, a) -> word(w, b, a) }
                        }
                    }
                )
            }
    )
}

/** Sleep timer: a clock (or the minutes left); tap — 15…60 minutes, to the end of the chapter, off. */
@Composable
private fun SleepButton(left: Int?, onSet: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chapterEnd = com.brahmadeo.supertonic.tts.utils.SleepTimer.CHAPTER_END
    Box {
        IconButton(onClick = { open = true }) {
            when {
                left == null -> Icon(Icons.Default.Timer, contentDescription = stringResource(AppR.string.sleep_title))
                left == chapterEnd -> Text(stringResource(AppR.string.sleep_chapter_short), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                else -> Text("$left′", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in listOf(15, 30, 45, 60)) {
                DropdownMenuItem(text = { Text(stringResource(AppR.string.sleep_minutes_fmt, m)) },
                    onClick = { open = false; onSet(m) })
            }
            DropdownMenuItem(text = { Text(stringResource(AppR.string.sleep_chapter)) },
                onClick = { open = false; onSet(chapterEnd) })
            if (left != null) {
                DropdownMenuItem(text = { Text(stringResource(AppR.string.sleep_off)) },
                    onClick = { open = false; onSet(0) })
            }
        }
    }
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
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(30.dp),
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
