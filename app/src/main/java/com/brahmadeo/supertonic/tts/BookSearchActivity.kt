package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.BookSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search across the whole book: a word → every place in every chapter; a tap on one
 * returns it to the player (chapter index + where in the chapter's text).
 */
class BookSearchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CHAPTER = "search_chapter"
        const val EXTRA_OFFSET = "search_offset"
        private const val MAX_HITS = 500
    }

    class Hit(val chapter: Int, val offset: Int, val title: String, val before: String, val match: String, val after: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupertonicTheme {
                SearchScreen(onBack = { finish() }, onPick = { h ->
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CHAPTER, h.chapter).putExtra(EXTRA_OFFSET, h.offset))
                    finish()
                })
            }
        }
    }

    /** Case and "ё" do not matter; the length stays the same, so places map back one to one. */
    private fun fold(s: String): String {
        val b = CharArray(s.length)
        for (i in s.indices) {
            val c = s[i].lowercaseChar()
            b[i] = if (c == 'ё') 'е' else c
        }
        return String(b)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SearchScreen(onBack: () -> Unit, onPick: (Hit) -> Unit) {
        var query by remember { mutableStateOf("") }
        var submitted by remember { mutableStateOf("") }
        var runs by remember { mutableIntStateOf(0) }
        val hits = remember { mutableStateListOf<Hit>() }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }

        LaunchedEffect(runs) {
            val q = fold(submitted.trim())
            if (q.length < 2) return@LaunchedEffect
            hits.clear()
            busy = true
            var count = 0
            withContext(Dispatchers.IO) {
                BookSession.forEachChapter(this@BookSearchActivity) { i, total, text ->
                    withContext(Dispatchers.Main) { progress = "${i + 1} / $total" }
                    val folded = fold(text)
                    val title = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(60) ?: ""
                    val found = ArrayList<Hit>()
                    var at = folded.indexOf(q)
                    while (at >= 0 && count + found.size < MAX_HITS) {
                        val a = maxOf(0, at - 50)
                        val b = minOf(text.length, at + q.length + 70)
                        found.add(Hit(i, at, title,
                            (if (a > 0) "…" else "") + text.substring(a, at).replace('\n', ' '),
                            text.substring(at, at + q.length),
                            text.substring(at + q.length, b).replace('\n', ' ') + if (b < text.length) "…" else ""))
                        at = folded.indexOf(q, at + q.length)
                    }
                    count += found.size
                    withContext(Dispatchers.Main) { hits.addAll(found) }
                    count < MAX_HITS
                }
            }
            busy = false
            progress = ""
        }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    trailingIcon = {
                        IconButton(onClick = { submitted = query; runs++ }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_title))
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitted = query; runs++ }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus)
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("  " + stringResource(R.string.search_chapter_fmt, progress),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (submitted.isNotBlank() && (!busy || hits.isNotEmpty())) {
                        Text((if (busy) "   " else "") + stringResource(R.string.search_found_fmt, hits.size),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(hits) { h ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(h.title, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(buildAnnotatedString {
                                append(h.before)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))) {
                                    append(h.match)
                                }
                                append(h.after)
                            }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
