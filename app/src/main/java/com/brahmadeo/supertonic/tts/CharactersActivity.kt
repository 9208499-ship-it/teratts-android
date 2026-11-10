package com.brahmadeo.supertonic.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.BookSession
import com.brahmadeo.supertonic.tts.utils.CharacterVoices
import com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2
import com.brahmadeo.supertonic.tts.utils.RoleCast
import com.brahmadeo.supertonic.tts.utils.RolePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import com.brahmadeo.supertonic.tts.R as AppR

/**
 * Characters of the text open in the reader: a voice, a pace and merges for each.
 * Changes take effect the next time reading starts (play / continue).
 */
class CharactersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("last_text", "") ?: ""
        setContent {
            SupertonicTheme {
                CharactersScreen(text = text, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharactersScreen(text: String, onBack: () -> Unit) {
    val context = LocalContext.current
    // settings are kept per book: the book of the text open in the reader ("" = not a book)
    val scope = remember(text) { BookSession.scopeFor(context, text) }
    var version by remember { mutableIntStateOf(0) }          // bumped after a merge → re-analyse
    var castVersion by remember { mutableIntStateOf(0) }      // bumped after a voice is changed → hand out again
    var result by remember { mutableStateOf<DialogueAnalyzer2.Result?>(null) }
    var roster by remember { mutableStateOf<com.brahmadeo.supertonic.tts.utils.BookRoster.Roster?>(null) }
    // the whole book's characters (this text's first), with what reading will give them
    var characters by remember { mutableStateOf<List<DialogueAnalyzer2.Character>?>(null) }
    var inText by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bookLines by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var cast by remember { mutableStateOf<RoleCast.Cast?>(null) }
    var common by remember { mutableStateOf<RoleCast.Common?>(null) }
    // a line of each character from the text — what "Listen" says
    var samples by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var aliases by remember { mutableStateOf(CharacterVoices.aliases(context, scope)) }
    val voices = remember { RolePrefs.availableVoices(context) }

    LaunchedEffect(version) {
        aliases = CharacterVoices.aliases(context, scope)
        val (r, ro) = withContext(Dispatchers.Default) {
            val ro = com.brahmadeo.supertonic.tts.utils.BookRoster.cached(context, scope)
            val r = if (text.isBlank()) null
            else DialogueAnalyzer2.applyAliases(DialogueAnalyzer2.analyze(text, DialogueAnalyzer2.Prior(
                ro?.genders ?: emptyMap(),
                com.brahmadeo.supertonic.tts.utils.SpeakerOverrides.all(context, scope),
                ro?.aliases ?: emptyMap())),
                (ro?.aliases ?: emptyMap()) + aliases)
            r to ro
        }
        samples = r?.let { sampleLines(text, it) } ?: emptyMap()
        roster = ro
        result = r
        if (r == null) characters = emptyList() else castVersion++
    }
    LaunchedEffect(castVersion) {
        val r = result ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val book = RoleCast.book(roster, r, aliases)
            val cm = RoleCast.common(context, scope)
            val c = RoleCast.cast(context, scope, book.order, book.genders, cm)
            val own = r.characters.associateBy { it.name }
            val list = book.order.map { n ->
                // the sex of the whole book, not of this text
                own[n]?.let { ch -> book.genders[n]?.let { ch.copy(role = it) } ?: ch }
                    ?: DialogueAnalyzer2.Character(n, book.genders[n] ?: DialogueAnalyzer2.Role.SPEECH, 0, 1f, emptyList())
            }
            // others of the book: the ones with a voice of their own or a few lines (not every passer-by)
            val shown = list.filter { it.name in own || c.voice(it.name) != null || (book.lines[it.name] ?: 2) >= 2 }
            withContext(Dispatchers.Main) {
                common = cm
                cast = c
                bookLines = book.lines
                inText = own.keys
                characters = shown
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(AppR.string.characters_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(AppR.string.back))
                    }
                }
            )
        }
    ) { pad ->
        val list = characters
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(stringResource(AppR.string.characters_empty), modifier = Modifier.padding(24.dp))
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        if (scope.isEmpty()) stringResource(AppR.string.characters_scope_global)
                        // the book's title, not its file name inside the app (book_3fa9….epub)
                        else stringResource(AppR.string.characters_scope_book, remember(scope) {
                            com.brahmadeo.supertonic.tts.utils.EbookManager.getRecentBooks(context)
                                .firstOrNull { java.io.File(it.path).name == scope }
                                ?.let { com.brahmadeo.supertonic.tts.utils.EbookManager.displayTitle(context, it) } ?: scope
                        }),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                item {
                    Text(
                        stringResource(AppR.string.characters_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val here = list.filter { it.name in inText }
                val elsewhere = list.filter { it.name !in inText }
                if (elsewhere.isNotEmpty()) item {
                    Text(stringResource(AppR.string.characters_in_text), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
                items(here, key = { it.name }) { c ->
                    CharacterCard(c, list, voices, scope, samples[c.name], cast, common, bookLines[c.name], true,
                        onMerged = { version++ }, onVoice = { castVersion++ })
                }
                if (elsewhere.isNotEmpty()) item {
                    Text(stringResource(AppR.string.characters_in_book), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
                items(elsewhere, key = { "book:" + it.name }) { c ->
                    CharacterCard(c, list, voices, scope, null, cast, common, bookLines[c.name], false,
                        onMerged = { version++ }, onVoice = { castVersion++ })
                }
                item {
                    OutlinedButton(
                        onClick = {
                            CharacterVoices.resetAuto(context, scope)
                            com.brahmadeo.supertonic.tts.utils.BookRoster.clear(context, scope)
                            android.widget.Toast.makeText(context, context.getString(AppR.string.characters_reset_done),
                                android.widget.Toast.LENGTH_LONG).show()
                            version++
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(stringResource(AppR.string.characters_reset)) }
                }
                if (aliases.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(AppR.string.characters_merged),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    items(aliases.entries.toList(), key = { "alias:" + it.key }) { (from, into) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("$from → $into", modifier = Modifier.weight(1f))
                            TextButton(onClick = { CharacterVoices.unmerge(context, from, scope); version++ }) {
                                Text(stringResource(AppR.string.characters_unmerge))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterCard(
    c: DialogueAnalyzer2.Character,
    all: List<DialogueAnalyzer2.Character>,
    voices: List<String>,
    scope: String,
    sample: String?,
    cast: RoleCast.Cast?,
    common: RoleCast.Common?,
    bookLines: Int?,
    inText: Boolean,
    onMerged: () -> Unit,
    onVoice: () -> Unit
) {
    val context = LocalContext.current
    val isHero = c.name == DialogueAnalyzer2.HERO
    var speed by remember(c.name) { mutableFloatStateOf(CharacterVoices.speedOverride(context, c.name, scope) ?: c.speed) }
    var voiceMenu by remember { mutableStateOf(false) }
    var mergeMenu by remember { mutableStateOf(false) }

    val entry = cast?.entries?.get(c.name)
    val female = c.role == DialogueAnalyzer2.Role.FEMALE
    val defaultLabel = stringResource(if (isHero) AppR.string.characters_as_narrator else AppR.string.characters_auto)
    val label = when (entry?.kind) {
        RoleCast.Kind.MANUAL -> com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(entry?.voice ?: "")
        RoleCast.Kind.HERO -> com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(entry?.voice ?: "") + " · " +
            stringResource(if (female) AppR.string.characters_heroine_short else AppR.string.characters_hero_short)
        RoleCast.Kind.AUTO -> stringResource(AppR.string.characters_auto_fmt, com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(entry?.voice ?: ""))
        RoleCast.Kind.NARRATOR -> stringResource(AppR.string.characters_as_narrator)
        RoleCast.Kind.COMMON -> stringResource(when (c.role) {
            DialogueAnalyzer2.Role.MALE -> AppR.string.characters_common_male
            DialogueAnalyzer2.Role.FEMALE -> AppR.string.characters_common_female
            else -> AppR.string.characters_common_unknown
        })
        null -> defaultLabel
    }
    val gender = when (c.role) {
        DialogueAnalyzer2.Role.MALE -> stringResource(AppR.string.characters_male)
        DialogueAnalyzer2.Role.FEMALE -> stringResource(AppR.string.characters_female)
        else -> "?"
    }
    val title = if (isHero) stringResource(AppR.string.characters_hero) else c.name
    val main = when (c.name) {
        cast?.mainHero -> stringResource(AppR.string.characters_hero_short)
        cast?.mainHeroine -> stringResource(AppR.string.characters_heroine_short)
        else -> null
    }
    val counts = listOfNotNull(
        if (inText) stringResource(AppR.string.characters_lines_fmt, c.lines) else null,
        bookLines?.let { stringResource(AppR.string.characters_book_lines_fmt, it) })

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (listOfNotNull(main, gender) + counts + c.traits).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    TextButton(onClick = { mergeMenu = true }) { Text(stringResource(AppR.string.characters_merge)) }
                    DropdownMenu(expanded = mergeMenu, onDismissRequest = { mergeMenu = false }) {
                        for (o in all) if (o.name != c.name) {
                            DropdownMenuItem(
                                text = { Text(if (o.name == DialogueAnalyzer2.HERO) stringResource(AppR.string.characters_hero) else o.name) },
                                onClick = { mergeMenu = false; CharacterVoices.merge(context, c.name, o.name, scope); onMerged() }
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(AppR.string.characters_voice), modifier = Modifier.weight(1f))
                val fallback = stringResource(AppR.string.characters_sample)
                IconButton(onClick = { preview(context, c, entry, common, sample ?: fallback, speed) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(AppR.string.characters_listen))
                }
                Box {
                    TextButton(onClick = { voiceMenu = true }) { Text(label) }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(defaultLabel) },
                            onClick = { voiceMenu = false; CharacterVoices.clear(context, c.name, scope); onVoice() }
                        )
                        for (v in voices) {
                            DropdownMenuItem(
                                text = { Text(com.brahmadeo.supertonic.tts.utils.VoiceLibrary.label(v)) },
                                onClick = { voiceMenu = false; CharacterVoices.set(context, c.name, v, scope); onVoice() }
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(Locale.US, "×%.2f", speed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = speed,
                    onValueChange = { speed = Math.round(it * 20f) / 20f },
                    onValueChangeFinished = { CharacterVoices.setSpeed(context, c.name, speed, scope) },
                    valueRange = 0.7f..1.3f,
                    steps = 11,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The longest line (up to ~200 letters) each character says in [text]: their voice is heard on it. */
private fun sampleLines(text: String, r: DialogueAnalyzer2.Result): Map<String, String> {
    val best = HashMap<String, String>()
    for (s in r.spans) {
        val who = s.speaker ?: continue
        if (s.role == DialogueAnalyzer2.Role.NARRATOR) continue
        val line = text.substring(s.start, s.end).trim().trim('—', '–', '-', '«', '»', '"', ' ', ',').trim()
        if (line.count { it.isLetter() } < 12 || line.length > 220) continue
        val cur = best[who]
        if (cur == null || (line.length > cur.length && cur.length < 120)) best[who] = line
    }
    return best
}

/** Say [line] the way reading will voice [c] (see RoleCast). */
private fun preview(context: android.content.Context, c: DialogueAnalyzer2.Character, entry: RoleCast.Entry?,
                    common: RoleCast.Common?, line: String, speed: Float) {
    val prefs = context.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
    val main = prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
    val cm = common ?: RoleCast.common(context)
    val file = entry?.voice ?: when {
        c.name == DialogueAnalyzer2.HERO -> cm.narrator
        c.role == DialogueAnalyzer2.Role.FEMALE -> cm.female
        c.role == DialogueAnalyzer2.Role.MALE -> cm.male
        else -> cm.unknown
    }
    val path = RolePrefs.pathOf(context, file) ?: RolePrefs.pathOf(context, main) ?: return
    val base = prefs.getFloat("last_speed", 1f)
    context.startService(android.content.Intent(context, com.brahmadeo.supertonic.tts.service.PlaybackService::class.java)
        .setAction(com.brahmadeo.supertonic.tts.service.PlaybackService.ACTION_PREVIEW)
        .putExtra("text", line).putExtra("voice", path).putExtra("speed", base * speed))
}
