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
    var characters by remember { mutableStateOf<List<DialogueAnalyzer2.Character>?>(null) }
    var aliases by remember { mutableStateOf(CharacterVoices.aliases(context, scope)) }
    val voices = remember { RolePrefs.availableVoices(context) }

    LaunchedEffect(version) {
        aliases = CharacterVoices.aliases(context, scope)
        characters = withContext(Dispatchers.Default) {
            if (text.isBlank()) emptyList()
            else DialogueAnalyzer2.applyAliases(DialogueAnalyzer2.analyze(text, DialogueAnalyzer2.Prior(
                com.brahmadeo.supertonic.tts.utils.BookRoster.cached(context, scope)?.genders ?: emptyMap(),
                com.brahmadeo.supertonic.tts.utils.SpeakerOverrides.all(context, scope))), aliases).characters
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
                        else stringResource(AppR.string.characters_scope_book, scope),
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
                items(list, key = { it.name }) { c ->
                    CharacterCard(c, list, voices, scope, onMerged = { version++ })
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
    onMerged: () -> Unit
) {
    val context = LocalContext.current
    val isHero = c.name == DialogueAnalyzer2.HERO
    var voice by remember(c.name) { mutableStateOf(CharacterVoices.explicit(context, c.name, scope)) }
    var speed by remember(c.name) { mutableFloatStateOf(CharacterVoices.speedOverride(context, c.name, scope) ?: c.speed) }
    var voiceMenu by remember { mutableStateOf(false) }
    var mergeMenu by remember { mutableStateOf(false) }

    val defaultLabel = stringResource(if (isHero) AppR.string.characters_as_narrator else AppR.string.characters_auto)
    val gender = when (c.role) {
        DialogueAnalyzer2.Role.MALE -> stringResource(AppR.string.characters_male)
        DialogueAnalyzer2.Role.FEMALE -> stringResource(AppR.string.characters_female)
        else -> "?"
    }
    val title = if (isHero) stringResource(AppR.string.characters_hero) else c.name

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (listOf(gender, stringResource(AppR.string.characters_lines_fmt, c.lines)) + c.traits)
                            .joinToString(" · "),
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
                Box {
                    TextButton(onClick = { voiceMenu = true }) { Text(voice?.removeSuffix(".json") ?: defaultLabel) }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(defaultLabel) },
                            onClick = { voiceMenu = false; voice = null; CharacterVoices.clear(context, c.name, scope) }
                        )
                        for (v in voices) {
                            DropdownMenuItem(
                                text = { Text(v.removeSuffix(".json")) },
                                onClick = { voiceMenu = false; voice = v; CharacterVoices.set(context, c.name, v, scope) }
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
