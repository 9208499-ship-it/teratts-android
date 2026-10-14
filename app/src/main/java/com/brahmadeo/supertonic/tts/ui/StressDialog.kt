package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahmadeo.supertonic.tts.R as AppR

private const val VOWELS = "аеёиоуыэюяaeiouy"

fun isVowel(c: Char) = c.lowercaseChar() in VOWELS

/** [word] with "+" before the vowel at [at] — how the engine is told the stress ("гот+ов"). */
fun withStress(word: String, at: Int): String =
    if (at in word.indices) word.substring(0, at) + "+" + word.substring(at) else word

/**
 * The word from the book, letter by letter: a tap on a vowel puts the stress mark over it,
 * a second tap on «е» makes it «ё». The words next to it can be taken in too ("у ворот"), so
 * the stress is set for the phrase only. "Послушать" says it; "В словарь" saves it.
 */
@Composable
fun StressDialog(
    word: String,
    before: String?,
    after: String?,
    initial: Int,
    onListen: (String) -> Unit,
    onSave: (term: String, stressed: String) -> Unit,
    onDismiss: () -> Unit
) {
    // positions are counted from the start of the tapped word (the word before gives negatives)
    var at by remember(word) { mutableIntStateOf(if (initial in word.indices && isVowel(word[initial])) initial else Int.MIN_VALUE) }
    var yo by remember(word) { mutableStateOf(setOf<Int>()) }
    var withBefore by remember(word) { mutableStateOf(false) }
    var withAfter by remember(word) { mutableStateOf(false) }
    val shift = if (withBefore && before != null) before.length + 1 else 0
    val phrase = (if (withBefore && before != null) "$before " else "") + word + (if (withAfter && after != null) " $after" else "")
    val rel = { i: Int -> i - shift }
    // a stress or «ё» on a word that is no longer taken in is dropped
    val inPhrase = { r: Int -> r + shift in phrase.indices }
    fun result(): String {
        val sb = StringBuilder()
        phrase.forEachIndexed { i, c ->
            val r = rel(i)
            if (r == at) sb.append('+')
            sb.append(if (r in yo) (if (c.isUpperCase()) 'Ё' else 'ё') else c)
        }
        return sb.toString().lowercase()
    }
    val canSave = (at != Int.MIN_VALUE && inPhrase(at)) || yo.any { inPhrase(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.stress_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(AppR.string.stress_hint_yo), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    phrase.forEachIndexed { i, c0 ->
                        val r = rel(i)
                        if (c0 == ' ') Spacer(Modifier.widthIn(min = 14.dp)) else {
                        val c = if (r in yo) (if (c0.isUpperCase()) 'Ё' else 'ё') else c0
                        val vowel = isVowel(c0)
                        val chosen = r == at
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .widthIn(min = 22.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        chosen -> MaterialTheme.colorScheme.primaryContainer
                                        vowel -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable(enabled = vowel) {
                                    // the stressed «е» tapped again: «ё» (and back)
                                    if (chosen && c0.lowercaseChar() == 'е') yo = if (r in yo) yo - r else yo + r
                                    else at = r
                                }
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                        ) {
                            Text(if (chosen) "´" else " ", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.height(22.dp))
                            Text(c.toString(), fontSize = 34.sp, fontFamily = FontFamily.Serif,
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                                color = if (chosen || r in yo) MaterialTheme.colorScheme.primary
                                        else if (vowel) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        }
                    }
                }
                // "у в+орот": the stress only in this phrase — take the word before / after in
                if (before != null || after != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (before != null) FilterChip(selected = withBefore, onClick = { withBefore = !withBefore },
                            label = { Text("+ «$before»") })
                        if (after != null) FilterChip(selected = withAfter, onClick = { withAfter = !withAfter },
                            label = { Text("+ «$after»") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(enabled = canSave, onClick = { onListen(result()) }) {
                    Text(stringResource(AppR.string.characters_listen))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(phrase, result()) }) {
                Text(stringResource(AppR.string.stress_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(AppR.string.cancel_action)) }
        }
    )
}
