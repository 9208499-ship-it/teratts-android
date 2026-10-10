package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LibraryScreen(
        onBack: () -> Unit,
        onOpenNew: () -> Unit,
        onBookClick: (RecentBook) -> Unit,
        onTocClick: (RecentBook) -> Unit = {}
    ) {
        val recentBooks by remember { mutableStateOf(EbookManager.getRecentBooks(this@EbookLibraryActivity)) }

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
                                val shown = if (book.title.startsWith("book_"))
                                    com.brahmadeo.supertonic.tts.utils.EbookManager.originalName(this@EbookLibraryActivity, book.path) ?: book.title
                                else book.title
                                Text(shown, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val pos = remember(book.path) {
                                    com.brahmadeo.supertonic.tts.utils.BookPositions.get(this@EbookLibraryActivity, java.io.File(book.path).name)
                                }
                                Text(if (pos != null && pos.quote.isNotBlank()) "▶ «" + pos.quote.trim().take(60) + "…»"
                                     else com.brahmadeo.supertonic.tts.utils.EbookManager.originalName(this@EbookLibraryActivity, book.path)
                                        ?: book.path.substringAfterLast('/'),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { Icon(Icons.Default.Book, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { onTocClick(book) }) {
                                    Icon(Icons.Default.Toc, contentDescription = getString(R.string.player_toc))
                                }
                            },
                            modifier = Modifier.clickable { onBookClick(book) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
