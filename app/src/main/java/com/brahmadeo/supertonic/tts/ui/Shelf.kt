package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brahmadeo.supertonic.tts.utils.RecentBook
import kotlin.math.sin

/** The library's look: a list (with what was heard last) or a shelf of covers. */
internal object LibraryView {
    const val LIST = "list"
    const val SHELF = "shelf"
    private const val KEY = "library_view"
    fun get(c: android.content.Context): String =
        c.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE).getString(KEY, LIST) ?: LIST
    fun set(c: android.content.Context, v: String) =
        c.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
}

/**
 * The books on wooden shelves, three to a board (as eReader Prestigio has it): the cover (or one made
 * from the title), a thin line of how much is read; a tap opens the book where it was left, a long
 * press — the contents, renaming, deleting. The wood is drawn by the app (no pictures).
 */
@Composable
internal fun BookShelf(
    books: List<RecentBook>,
    titleOf: (RecentBook) -> String,
    modifier: Modifier = Modifier,
    onOpen: (RecentBook) -> Unit,
    onToc: (RecentBook) -> Unit,
    onRename: (RecentBook) -> Unit,
    onDelete: (RecentBook) -> Unit,
    labels: ShelfLabels,
    refresh: Int = 0
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val wall = remember(dark) { woodTile(dark) }
    val rows = books.chunked(3)
    LazyColumn(
        modifier = modifier.drawWithCache {
            val brush = ShaderBrush(ImageShader(wall, TileMode.Repeated, TileMode.Repeated))
            onDrawBehind { drawRect(brush) }
        },
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        items(rows.size) { r ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    for (book in rows[r]) {
                        ShelfBook(book, titleOf(book), Modifier.weight(1f), onOpen, onToc, onRename, onDelete, labels, refresh)
                    }
                    repeat(3 - rows[r].size) { Spacer(modifier = Modifier.weight(1f)) }
                }
                Plank(dark)
            }
        }
    }
}

/** The words of the book's menu on the shelf. */
internal class ShelfLabels(val toc: String, val rename: String, val delete: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBook(
    book: RecentBook, title: String, modifier: Modifier,
    onOpen: (RecentBook) -> Unit, onToc: (RecentBook) -> Unit,
    onRename: (RecentBook) -> Unit, onDelete: (RecentBook) -> Unit, labels: ShelfLabels, refresh: Int = 0
) {
    val ctx = LocalContext.current
    var cover by remember(book.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var share by remember(book.path, refresh) { mutableStateOf(com.brahmadeo.supertonic.tts.utils.BookProgress.savedShare(ctx, book.path)) }
    LaunchedEffect(book.path, refresh) {
        cover = com.brahmadeo.supertonic.tts.utils.BookProgress.cover(ctx, book.path)
        if (share == null && com.brahmadeo.supertonic.tts.utils.BookPositions.get(ctx, java.io.File(book.path).name) != null) {
            com.brahmadeo.supertonic.tts.utils.BookProgress.lengths(ctx, book.path)
            share = com.brahmadeo.supertonic.tts.utils.BookProgress.savedShare(ctx, book.path)
        }
    }
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier.padding(horizontal = 10.dp).padding(top = 14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .shadow(6.dp, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .combinedClickable(onClick = { onOpen(book) }, onLongClick = { menu = true })
        ) {
            val c = cover
            if (c != null) Image(c.asImageBitmap(), contentDescription = title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            else MadeCover(title)
            // the spine's fold, as on a real cover
            Box(modifier = Modifier.fillMaxSize().drawWithCache {
                val fold = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.18f), 0.04f to Color.White.copy(alpha = 0.08f),
                    0.07f to Color.Black.copy(alpha = 0.10f), 0.12f to Color.Transparent
                )
                onDrawBehind { drawRect(fold) }
            })
            // how much is read
            share?.let { s ->
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.35f))) {
                    Box(modifier = Modifier.fillMaxWidth(s.coerceIn(0f, 1f)).height(4.dp).background(Color(0xFFF2B544)))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text(labels.toc) }, onClick = { menu = false; onToc(book) })
            DropdownMenuItem(text = { Text(labels.rename) }, onClick = { menu = false; onRename(book) })
            DropdownMenuItem(text = { Text(labels.delete) }, onClick = { menu = false; onDelete(book) })
        }
    }
}

/** A book without a cover of its own: a coloured cloth binding with its title in gold. */
@Composable
private fun MadeCover(title: String) {
    val palette = listOf(0xFF7A1E1E, 0xFF1F3A5F, 0xFF2E5A3A, 0xFF5B3A1E, 0xFF4A2A5E, 0xFF6B4E16)
    val color = Color(palette[(title.hashCode() and 0x7fffffff) % palette.size])
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(color.copy(alpha = 1f), Color(
                red = color.red * 0.7f, green = color.green * 0.7f, blue = color.blue * 0.7f)))
        ).padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title, color = Color(0xFFF2D58A), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis
        )
    }
}

/** The board the books stand on: its top, its front edge, the shadow under it. */
@Composable
private fun Plank(dark: Boolean) {
    val top = if (dark) Color(0xFF6A4A2E) else Color(0xFFEBCB97)
    val front = if (dark) Color(0xFF4E351F) else Color(0xFFC79B60)
    val edge = if (dark) Color(0xFF3A2716) else Color(0xFFA77B44)
    Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
        val w = size.width
        val topH = size.height * 0.22f
        val frontH = size.height * 0.40f
        drawRect(Brush.verticalGradient(listOf(top, front), startY = 0f, endY = topH), size = Size(w, topH))
        drawRect(Brush.verticalGradient(listOf(front, edge), startY = topH, endY = topH + frontH),
            topLeft = Offset(0f, topH), size = Size(w, frontH))
        // a line of light on the edge and the shadow on the wall below
        drawRect(Color.White.copy(alpha = 0.25f), topLeft = Offset(0f, topH), size = Size(w, 1.5f))
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
            startY = topH + frontH, endY = size.height), topLeft = Offset(0f, topH + frontH), size = Size(w, size.height - topH - frontH))
    }
}

/** The wall of the bookcase: wood with its grain running across, a seamless tile drawn once. */
private fun woodTile(dark: Boolean): ImageBitmap {
    val w = 256; val h = 256
    val rnd = java.util.Random(if (dark) 77L else 41L)
    val base = if (dark) intArrayOf(74, 52, 33) else intArrayOf(214, 176, 124)
    val amp = if (dark) 10f else 18f
    // the grain: fine stripes across (many waves of whole periods — the tile repeats exactly),
    // bent a little along the board
    val ph = FloatArray(2) { rnd.nextFloat() * 6.283f }
    val ks = intArrayOf(5, 8, 13, 21, 34, 55, 71, 97)
    val amps = floatArrayOf(0.30f, 0.22f, 0.18f, 0.14f, 0.10f, 0.08f, 0.06f, 0.05f)
    val phs = FloatArray(ks.size) { rnd.nextFloat() * 6.283f }
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val fx = x * 6.283f / w
        val warp = 1.6f * sin(fx + ph[0]) + 0.8f * sin(2 * fx + ph[1])
        val fy = (y + warp) * 6.283f / h
        var grain = 0f
        for (i in ks.indices) grain += amps[i] * sin(ks[i] * fy + phs[i])
        val d = grain * amp + (rnd.nextFloat() - 0.5f) * amp * 0.35f
        val r = (base[0] + d).toInt().coerceIn(0, 255)
        val g = (base[1] + d * 0.85f).toInt().coerceIn(0, 255)
        val b = (base[2] + d * 0.6f).toInt().coerceIn(0, 255)
        px[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}
