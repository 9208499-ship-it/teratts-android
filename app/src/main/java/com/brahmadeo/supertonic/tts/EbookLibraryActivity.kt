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
            val localPath = EbookManager.importBook(this, it)
            if (localPath != null) {
                openBook(localPath)
            } else {
                Toast.makeText(this, "Failed to import book", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.brahmadeo.supertonic.tts.utils.NoAutofill.apply(this)
        setContent {
            SupertonicTheme {
                LibraryScreen(
                    onBack = { finish() },
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
        // the book whose menu is open, the one being renamed, the one asked about deleting
        var menuFor by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<RecentBook?>(null) }
        var deleting by remember { mutableStateOf<RecentBook?>(null) }
        val ctx = this@EbookLibraryActivity
        val untitled = getString(R.string.library_untitled)
        fun titleOf(book: RecentBook) = EbookManager.displayTitle(ctx, book) ?: untitled

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
                    title = { Text("Ebook Library") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        Text("No recent books", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onOpenNew, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Open your first book")
                        }
                    }
                }
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
                                val pos = remember(book.path) {
                                    com.brahmadeo.supertonic.tts.utils.BookPositions.get(this@EbookLibraryActivity, java.io.File(book.path).name)
                                }
                                Text(if (pos != null && pos.quote.isNotBlank()) "▶ «" + pos.quote.trim().take(60) + "…»"
                                     else EbookManager.readableTitle(EbookManager.originalName(this@EbookLibraryActivity, book.path))
                                        ?: book.path.substringAfterLast('/'),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { Icon(Icons.Default.Book, contentDescription = null) },
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
