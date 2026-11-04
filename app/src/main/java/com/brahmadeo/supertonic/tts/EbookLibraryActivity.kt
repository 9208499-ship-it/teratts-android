package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.EbookManager
import com.brahmadeo.supertonic.tts.utils.RecentBook

class EbookLibraryActivity : ComponentActivity() {

    companion object {
        /** Opened as the app's home screen (MainActivity): "Back" leaves the app. */
        const val EXTRA_HOME = "library_home"
        /** In the result: the user left the home library — the app closes. */
        const val EXTRA_EXIT = "library_exit"
    }

    private val isHome get() = intent.getBooleanExtra(EXTRA_HOME, false)

    /** The screen's own tasks (importing a book), ended with it. */
    private val uiScope = kotlinx.coroutines.MainScope()

    /** Bumped on every return to the library: the books, their places and shares are read again. */
    private val resumeTick = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun leave() {
        if (isHome) setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_EXIT, true))
        finish()
    }

    private val ebookOutlineLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }

    private val ebookPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // copying and converting a book takes seconds: in the background (it froze the library)
            Toast.makeText(this, getString(R.string.book_opening), Toast.LENGTH_SHORT).show()
            uiScope.launch {
                val localPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { EbookManager.importBook(this@EbookLibraryActivity, it) } catch (e: Exception) { null }
                }
                if (localPath != null) openBook(localPath)
                else Toast.makeText(this@EbookLibraryActivity, getString(R.string.book_open_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.brahmadeo.supertonic.tts.utils.NoAutofill.apply(this)
        setContent {
            SupertonicTheme {
                com.brahmadeo.supertonic.tts.ui.UpdateDialogHost()
                // the home screen: system "Back" leaves the app (as from Moon+ Reader's bookshelf)
                androidx.activity.compose.BackHandler(enabled = isHome) { leave() }
                LibraryScreen(
                    onBack = { leave() },
                    onOpenNew = { ebookPickerLauncher.launch(arrayOf("*/*")) },
                    onBookClick = { openBook(it.path) },
                    onTocClick = { openBook(it.path, toContents = true) }
                )
            }
        }
    }

    private fun openBook(path: String, toContents: Boolean = false) {
        val intent = Intent(this, EbookOutlineActivity::class.java).apply {
            putExtra(EbookOutlineActivity.EXTRA_URI, path)
            // a book read before opens at its place, as in reading apps; the list icon — its contents
            putExtra(EbookOutlineActivity.EXTRA_CONTINUE, !toContents)
        }
        ebookOutlineLauncher.launch(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun LibraryScreen(
        onBack: () -> Unit,
        onOpenNew: () -> Unit,
        onBookClick: (RecentBook) -> Unit,
        onTocClick: (RecentBook) -> Unit = {}
    ) {
        var recentBooks by remember { mutableStateOf(EbookManager.getRecentBooks(this@EbookLibraryActivity)) }
        // back in the library (from a book, the contents, a new book): the list and places as they are now
        val tick = resumeTick.intValue
        LaunchedEffect(tick) { recentBooks = EbookManager.getRecentBooks(this@EbookLibraryActivity) }
        // the book whose menu is open, the one being renamed, the one asked about deleting
        var menuFor by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<RecentBook?>(null) }
        var deleting by remember { mutableStateOf<RecentBook?>(null) }
        val ctx = this@EbookLibraryActivity
        val untitled = getString(R.string.library_untitled)
        fun titleOf(book: RecentBook) = EbookManager.displayTitle(ctx, book) ?: untitled
        // a list or a shelf of covers (the button above switches, the choice is kept)
        var view by remember { mutableStateOf(com.brahmadeo.supertonic.tts.ui.LibraryView.get(ctx)) }

        renaming?.let { book ->
            var name by remember(book.path) { mutableStateOf(EbookManager.displayTitle(ctx, book) ?: "") }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(getString(R.string.library_rename)) },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        EbookManager.renameBook(ctx, book.path, name)
                        recentBooks = EbookManager.getRecentBooks(ctx)
                        renaming = null
                    }) { Text(getString(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text(getString(R.string.cancel_action)) } }
            )
        }
        deleting?.let { book ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(getString(R.string.library_delete_title)) },
                text = { Text(getString(R.string.library_delete_message, titleOf(book))) },
                confirmButton = {
                    TextButton(onClick = {
                        EbookManager.removeBook(ctx, book.path)
                        recentBooks = EbookManager.getRecentBooks(ctx)
                        deleting = null
                    }) { Text(getString(R.string.delete)) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(getString(R.string.cancel_action)) } }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(getString(R.string.library_title)) },
                    navigationIcon = {
                        if (!isHome) IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // a shelf of covers / a list
                        IconButton(onClick = {
                            view = if (view == com.brahmadeo.supertonic.tts.ui.LibraryView.SHELF) com.brahmadeo.supertonic.tts.ui.LibraryView.LIST
                                else com.brahmadeo.supertonic.tts.ui.LibraryView.SHELF
                            com.brahmadeo.supertonic.tts.ui.LibraryView.set(ctx, view)
                        }) {
                            if (view == com.brahmadeo.supertonic.tts.ui.LibraryView.SHELF)
                                Icon(Icons.Default.ViewAgenda, contentDescription = getString(R.string.library_view_list))
                            else Icon(Icons.Default.GridView, contentDescription = getString(R.string.library_view_shelf))
                        }
                        // your own text (the old main screen) and the settings
                        IconButton(onClick = { setResult(RESULT_CANCELED); finish() }) {
                            Icon(androidx.compose.material.icons.Icons.Default.EditNote, contentDescription = getString(R.string.library_paste_text))
                        }
                        IconButton(onClick = { startActivity(Intent(this@EbookLibraryActivity, LexiconActivity::class.java)) }) {
                            Icon(androidx.compose.material.icons.Icons.Default.Settings, contentDescription = getString(R.string.lexicon_title))
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onOpenNew) {
                    Icon(Icons.Default.Add, contentDescription = "Open New Ebook")
                }
            }
        ) { paddingValues ->
            if (recentBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(getString(R.string.library_empty), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onOpenNew, modifier = Modifier.padding(top = 16.dp)) {
                            Text(getString(R.string.library_open_first))
                        }
                    }
                }
            } else if (view == com.brahmadeo.supertonic.tts.ui.LibraryView.SHELF) {
                com.brahmadeo.supertonic.tts.ui.BookShelf(
                    books = recentBooks,
                    titleOf = { titleOf(it) },
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    onOpen = onBookClick,
                    onToc = onTocClick,
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                    labels = com.brahmadeo.supertonic.tts.ui.ShelfLabels(getString(R.string.player_toc),
                        getString(R.string.library_rename), getString(R.string.delete)),
                    refresh = tick
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(recentBooks) { book ->
                        ListItem(
                            headlineContent = {
                                Text(titleOf(book), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val pos = remember(book.path, tick) {
                                    com.brahmadeo.supertonic.tts.utils.BookPositions.get(this@EbookLibraryActivity, java.io.File(book.path).name)
                                }
                                // how much of the book is read (its chapters are counted once, then kept)
                                var share by remember(book.path, tick) {
                                    mutableStateOf(com.brahmadeo.supertonic.tts.utils.BookProgress.savedShare(ctx, book.path))
                                }
                                LaunchedEffect(book.path, tick) {
                                    if (share == null && pos != null) {
                                        com.brahmadeo.supertonic.tts.utils.BookProgress.lengths(ctx, book.path)
                                        share = com.brahmadeo.supertonic.tts.utils.BookProgress.savedShare(ctx, book.path)
                                    }
                                }
                                Column {
                                    Text(if (pos != null && pos.quote.isNotBlank()) "▶ «" + pos.quote.trim().take(60) + "…»"
                                         else EbookManager.readableTitle(EbookManager.originalName(this@EbookLibraryActivity, book.path))
                                            ?: book.path.substringAfterLast('/'),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    share?.let { s ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                            LinearProgressIndicator(progress = { s }, modifier = Modifier.weight(1f).height(3.dp))
                                            Text(String.format(java.util.Locale.getDefault(), "  %.1f %%", s * 100f),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                // the book's cover (taken from it once and kept), else a book sign
                                var cover by remember(book.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
                                LaunchedEffect(book.path) { cover = com.brahmadeo.supertonic.tts.utils.BookProgress.cover(ctx, book.path) }
                                Box(modifier = Modifier.width(48.dp).height(68.dp), contentAlignment = Alignment.Center) {
                                    val c = cover
                                    if (c != null) androidx.compose.foundation.Image(c.asImageBitmap(), contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraSmall))
                                    else Icon(Icons.Default.Book, contentDescription = null)
                                }
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onTocClick(book) }) {
                                        Icon(Icons.Default.Toc, contentDescription = getString(R.string.player_toc))
                                    }
                                    // rename / delete: this button or a long press on the book
                                    Box {
                                        IconButton(onClick = { menuFor = book.path }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = getString(R.string.library_actions))
                                        }
                                        DropdownMenu(expanded = menuFor == book.path, onDismissRequest = { menuFor = null }) {
                                            DropdownMenuItem(text = { Text(getString(R.string.library_rename)) },
                                                onClick = { menuFor = null; renaming = book })
                                            DropdownMenuItem(text = { Text(getString(R.string.delete)) },
                                                onClick = { menuFor = null; deleting = book })
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { onBookClick(book) },
                                onLongClick = { menuFor = book.path }
                            )
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
