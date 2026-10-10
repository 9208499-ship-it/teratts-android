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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * The word from the book, letter by letter: a tap on a vowel puts the stress mark over it.
 * "Послушать" says the word with that stress; "Сохранить" puts it into the user's dictionary.
 */
@Composable
fun StressDialog(
    word: String,
    initial: Int,
    onListen: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var at by remember(word) { mutableIntStateOf(if (initial in word.indices && isVowel(word[initial])) initial else -1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.stress_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(AppR.string.stress_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    word.forEachIndexed { i, c ->
                        val vowel = isVowel(c)
                        val chosen = i == at
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
                                .clickable(enabled = vowel) { at = i }
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                        ) {
                            // the stress mark sits over the chosen vowel
                            Text(if (chosen) "´" else " ", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.height(22.dp))
                            Text(c.toString(), fontSize = 34.sp, fontFamily = FontFamily.Serif,
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                                color = if (chosen) MaterialTheme.colorScheme.primary
                                        else if (vowel) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { onListen(withStress(word.lowercase(), at)) }) {
                    Text(stringResource(AppR.string.characters_listen))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = at >= 0, onClick = { onSave(withStress(word.lowercase(), at)) }) {
                Text(stringResource(AppR.string.stress_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(AppR.string.cancel_action)) }
        }
    )
}
