package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R as AppR

/**
 * The reading's own settings, a sheet from below the player (after eReader Prestigio's TTS panel).
 * What is in it depends on what is going on (as in readers: pallado, 4PDA): reading by eye — the look
 * of the book (the page, own picture, the type, pages or ribbon); listening — the voice (the pace,
 * the sleep timer, the voice, the model, the roles). The quotes, the contents, the search — in both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingSheet(
    onDismiss: () -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    sleepLeft: Int?,
    onSleepTimer: (Int) -> Unit,
    voiceNow: String,
    onVoicePick: (String) -> Unit,
    onEngine: (String) -> Unit,
    onRoles: (Boolean) -> Unit,
    onQuote: (() -> Unit)?,
    onQuotes: (() -> Unit)?,
    onToc: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    onCharacters: () -> Unit,
    showList: Boolean,
    onToggleList: () -> Unit,
    onSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    pageStyle: String = PageStyles.PLAIN,
    onPageStyle: (String) -> Unit = {},
    paged: Boolean = false,
    onPaged: (Boolean) -> Unit = {},
    pageAnim: String = PageMode.FLIP,
    onPageAnim: (String) -> Unit = {},
    pageSound: Boolean = true,
    onPageSound: (Boolean) -> Unit = {},
    typeset: Boolean = true,
    onTypeset: (Boolean) -> Unit = {},
    // the voice is reading (its settings) or not (the book's look)
    speaking: Boolean = true,
    hasCustomPage: Boolean = false,
    onPickPage: () -> Unit = {},
    fontSize: Float = BookFont.DEFAULT,
    onFontSize: (Float) -> Unit = {},
    fontFamily: String = BookFont.SERIF,
    onFontFamily: (String) -> Unit = {},
    // Pocket: the fast or the slow model
    onPocketVariant: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val assets = com.brahmadeo.supertonic.tts.utils.AssetManager
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var engine by remember { mutableStateOf(assets.engine(context)) }
    val pocketReady = remember { assets.shtormReady(context) }
    val voices = remember(engine) { com.brahmadeo.supertonic.tts.utils.RolePrefs.availableVoices(context) }
    var voice by remember { mutableStateOf(voiceNow) }
    var voicesOpen by remember { mutableStateOf(false) }
    var roles by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.RolePrefs.enabled(context)) }
    var pace by remember(speed) { mutableStateOf(speed) }
    var size by remember(fontSize) { mutableStateOf(fontSize) }
    fun stem(f: String) = f.substringAfterLast('/').substringBeforeLast('.')

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // ---- at hand: the phrase to the quotes, the quotes, the contents, the search, the characters, the view ----
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (onQuote != null) SheetAction(Icons.Default.Star, stringResource(AppR.string.quote_add_short)) { onQuote() }
                if (onQuotes != null) SheetAction(Icons.Default.FormatQuote, stringResource(AppR.string.quotes_title)) { onDismiss(); onQuotes() }
                if (onToc != null) SheetAction(Icons.Default.Toc, stringResource(AppR.string.player_toc)) { onDismiss(); onToc() }
                if (onSearch != null) SheetAction(Icons.Default.Search, stringResource(AppR.string.sheet_search)) { onDismiss(); onSearch() }
                SheetAction(Icons.Default.People, stringResource(AppR.string.characters_title)) { onDismiss(); onCharacters() }
                SheetAction(Icons.Default.Subject, stringResource(if (showList) AppR.string.sheet_view_phrase else AppR.string.sheet_view_page)) {
                    onDismiss(); onToggleList()
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (!speaking) {
            // ---- the page under the text: the app's ones or the user's own picture ----
            Text(stringResource(AppR.string.page_style_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for ((st, name) in listOf(PageStyles.PLAIN to AppR.string.page_style_plain, PageStyles.PAPER to AppR.string.page_style_paper,
                        PageStyles.PARCHMENT to AppR.string.page_style_parchment, PageStyles.DARK to AppR.string.page_style_dark)) {
                    FilterChip(selected = pageStyle == st, onClick = { onPageStyle(st) }, label = { Text(stringResource(name)) })
                }
                if (hasCustomPage) FilterChip(selected = pageStyle == PageStyles.CUSTOM, onClick = { onPageStyle(PageStyles.CUSTOM) },
                    label = { Text(stringResource(AppR.string.page_style_custom)) })
                FilterChip(selected = false, onClick = { onPickPage() },
                    label = { Text(stringResource(if (hasCustomPage) AppR.string.page_style_custom_change else AppR.string.page_style_custom_pick)) })
            }
            // ---- the type: its size and kind ----
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(AppR.string.font_title) + "  " + String.format(java.util.Locale.US, "%.0f", size),
                style = MaterialTheme.typography.titleSmall)
            Slider(
                value = size,
                onValueChange = { size = Math.round(it).toFloat() },
                onValueChangeFinished = { onFontSize(size) },
                valueRange = BookFont.MIN..BookFont.MAX,
                steps = (BookFont.MAX - BookFont.MIN).toInt() - 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(selected = fontFamily == BookFont.SERIF, onClick = { onFontFamily(BookFont.SERIF) },
                    label = { Text(stringResource(AppR.string.font_serif), fontFamily = androidx.compose.ui.text.font.FontFamily.Serif) })
                FilterChip(selected = fontFamily == BookFont.SANS, onClick = { onFontFamily(BookFont.SANS) },
                    label = { Text(stringResource(AppR.string.font_sans), fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif) })
            }
            // set like a book: hyphens and both edges even
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(AppR.string.typeset_title), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(AppR.string.typeset_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = typeset, onCheckedChange = onTypeset)
            }
            // ---- the text: a ribbon to scroll or pages to turn (how they turn, with a rustle or not) ----
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(AppR.string.text_view_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(selected = !paged, onClick = { onPaged(false) }, label = { Text(stringResource(AppR.string.text_view_ribbon)) })
                FilterChip(selected = paged, onClick = { onPaged(true) }, label = { Text(stringResource(AppR.string.text_view_pages)) })
                if (paged) {
                    FilterChip(selected = pageAnim == PageMode.FLIP, onClick = { onPageAnim(PageMode.FLIP) },
                        label = { Text(stringResource(AppR.string.page_anim_flip)) })
                    FilterChip(selected = pageAnim == PageMode.SLIDE, onClick = { onPageAnim(PageMode.SLIDE) },
                        label = { Text(stringResource(AppR.string.page_anim_slide)) })
                }
            }
            if (paged) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(AppR.string.page_sound), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = pageSound, onCheckedChange = { onPageSound(it) })
                }
            }
            }

            if (speaking) {

            // ---- the pace ----
            Text(
                stringResource(AppR.string.speed_label) + "  " + String.format(java.util.Locale.US, "%.1f×", pace),
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = pace,
                onValueChange = { pace = Math.round(it * 10f) / 10f },
                onValueChangeFinished = { onSpeedChange(pace) },
                valueRange = 0.5f..2.5f,
                steps = 19
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (v in listOf(0.8f, 1.0f, 1.3f, 1.6f, 2.0f)) {
                    FilterChip(
                        selected = Math.abs(pace - v) < 0.05f,
                        onClick = { pace = v; onSpeedChange(v) },
                        label = { Text(String.format(java.util.Locale.US, "%.1f", v)) }
                    )
                }
            }

            // ---- the sleep timer ----
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(AppR.string.sleep_title), style = MaterialTheme.typography.titleSmall)
            val chapterEnd = com.brahmadeo.supertonic.tts.utils.SleepTimer.CHAPTER_END
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (m in listOf(15, 30, 60)) {
                    FilterChip(selected = false, onClick = { onSleepTimer(m) },
                        label = { Text(stringResource(AppR.string.sleep_minutes_fmt, m)) })
                }
                FilterChip(selected = sleepLeft == chapterEnd, onClick = { onSleepTimer(chapterEnd) },
                    label = { Text(stringResource(AppR.string.sleep_chapter_chip)) })
                if (sleepLeft != null) {
                    FilterChip(selected = false, onClick = { onSleepTimer(0) },
                        label = { Text(stringResource(AppR.string.sleep_off)) })
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- the voice: tap — the list right here; a voice chosen — the reading goes on in it ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { voicesOpen = !voicesOpen }.padding(vertical = 6.dp)
            ) {
                Text(stringResource(AppR.string.main_voice_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(voices.firstOrNull { stem(it) == stem(voice) } ?: voice),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(if (voicesOpen) "  ▴" else "  ▾", color = MaterialTheme.colorScheme.primary)
            }
            if (voicesOpen) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        for (v in voices) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    voice = v; voicesOpen = false; onVoicePick(v)
                                }.padding(horizontal = 4.dp)
                            ) {
                                RadioButton(selected = stem(v) == stem(voice), onClick = { voice = v; voicesOpen = false; onVoicePick(v) })
                                Text(com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(v), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ---- the model ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(stringResource(AppR.string.sheet_model), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = engine == assets.ENGINE_TERA,
                    onClick = { if (engine != assets.ENGINE_TERA) { engine = assets.ENGINE_TERA; onEngine(engine) } },
                    label = { Text(stringResource(AppR.string.engine_tera)) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = engine == assets.ENGINE_SHTORM,
                    onClick = {
                        // not downloaded yet: it is downloaded in the settings
                        if (!pocketReady) { onDismiss(); onVoiceSettings() }
                        else if (engine != assets.ENGINE_SHTORM) { engine = assets.ENGINE_SHTORM; onEngine(engine) }
                    },
                    label = { Text(stringResource(AppR.string.sheet_engine_pocket)) }
                )
            }
            // Pocket's two models (when both are here): fast or slow — the reading goes on in the chosen one
            if (engine == assets.ENGINE_SHTORM && remember { assets.shtormSlowReady(context) }) {
                var variant by remember { mutableStateOf(assets.shtormVariant(context)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))
                    FilterChip(selected = variant == assets.VARIANT_FAST, onClick = {
                        if (variant != assets.VARIANT_FAST) { variant = assets.VARIANT_FAST; onPocketVariant(variant) }
                    }, label = { Text(stringResource(AppR.string.shtorm_variant_fast_short)) })
                    FilterChip(selected = variant == assets.VARIANT_SLOW, onClick = {
                        if (variant != assets.VARIANT_SLOW) { variant = assets.VARIANT_SLOW; onPocketVariant(variant) }
                    }, label = { Text(stringResource(AppR.string.shtorm_variant_slow_short)) })
                }
            }

            // ---- the roles ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(AppR.string.roles_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = roles, onCheckedChange = { roles = it; onRoles(it) })
            }
            // ---- the background music (when music is chosen in the settings): on / off at hand ----
            val music = com.brahmadeo.supertonic.tts.utils.BackgroundMusic
            if (remember { music.track(context) != null }) {
                var musicOn by remember { mutableStateOf(music.enabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(stringResource(AppR.string.music_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = musicOn, onCheckedChange = { musicOn = it; music.setEnabled(context, it) })
                }
            }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (speaking) TextButton(onClick = { onDismiss(); onVoiceSettings() }) { Text(stringResource(AppR.string.player_voice_settings)) }
                else Spacer(modifier = Modifier.width(1.dp))
                TextButton(onClick = { onDismiss(); onSettings() }) { Text(stringResource(AppR.string.sheet_all_settings)) }
            }
        }
    }
}

/** One of the actions at hand: a sign and a short word under it. */
@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(58.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * The sleep timer while it is on, a thin line under the buttons: how long is left, "+15 min" and off
 * (as in eReader Prestigio — the timer is in sight and is put off with one tap).
 */
@Composable
internal fun SleepStrip(left: Int, onSet: (Int) -> Unit) {
    val chapterEnd = com.brahmadeo.supertonic.tts.utils.SleepTimer.CHAPTER_END
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(
            if (left == chapterEnd) stringResource(AppR.string.sleep_strip_chapter)
            else stringResource(AppR.string.sleep_strip_minutes, left),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 6.dp)
        )
        TextButton(onClick = { onSet(if (left > 0) left + 15 else 15) }) { Text(stringResource(AppR.string.sleep_plus15)) }
        TextButton(onClick = { onSet(0) }) { Text(stringResource(AppR.string.sleep_off)) }
    }
}

/**
 * The quotes of this book: a tap — to the place (if it is in this chapter), share, delete.
 */
@Composable
internal fun QuotesDialog(
    quotes: List<com.brahmadeo.supertonic.tts.utils.Quotes.Quote>,
    onGo: (com.brahmadeo.supertonic.tts.utils.Quotes.Quote) -> Unit,
    onShare: (com.brahmadeo.supertonic.tts.utils.Quotes.Quote) -> Unit,
    onDelete: (com.brahmadeo.supertonic.tts.utils.Quotes.Quote) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.quotes_title)) },
        text = {
            if (quotes.isEmpty()) Text(stringResource(AppR.string.quotes_empty), style = MaterialTheme.typography.bodyMedium)
            else Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (q in quotes) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.weight(1f).clickable { onGo(q) }.padding(end = 4.dp)) {
                            Text("«" + q.text + "»", style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            if (q.chapter.isNotEmpty()) Text(q.chapter, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.IconButton(onClick = { onShare(q) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(AppR.string.quotes_share), modifier = Modifier.size(20.dp))
                        }
                        androidx.compose.material3.IconButton(onClick = { onDelete(q) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(AppR.string.quotes_delete), modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(AppR.string.quotes_close)) } }
    )
}
