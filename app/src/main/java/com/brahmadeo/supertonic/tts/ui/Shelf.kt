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
    // the user's own shelf (Skins): a nine-patch shelf (AlReader's skins) or a picture of the wall
    val ctx = LocalContext.current
    val skinVersion = com.brahmadeo.supertonic.tts.utils.Skins.version.intValue
    val skin = remember(skinVersion) { ShelfSkins.get(ctx) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // a nine-patch's pixels are taken as xhdpi ones (2 per dp), as such skins are drawn
    val k = density / 2f
    val rows = books.chunked(3)
    LazyColumn(
        modifier = modifier.drawWithCache {
            val brush = ShaderBrush(ImageShader(wall, TileMode.Repeated, TileMode.Repeated))
            onDrawBehind {
                when {
                    skin == null -> drawRect(brush)
                    skin.nine != null -> drawRect(skin.mean)
                    else -> drawCover(skin.image)
                }
            }
        },
        contentPadding = PaddingValues(top = if (skin?.nine != null) 0.dp else 12.dp, bottom = 96.dp)
    ) {
        items(rows.size) { r ->
            val nine = skin?.nine
            if (skin != null && nine != null) {
                // one shelf of the skin per row, stretched round the books; the books stand inside its padding
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .drawWithCache { onDrawBehind { drawNine(skin.image, nine, k) } }
                        .padding(start = (nine.padL * k / density).dp, top = (nine.padT * k / density).dp,
                            end = (nine.padR * k / density).dp, bottom = (nine.padB * k / density).dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        for (book in rows[r]) {
                            ShelfBook(book, titleOf(book), Modifier.weight(1f), onOpen, onToc, onRename, onDelete, labels, refresh)
                        }
                        repeat(3 - rows[r].size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            } else Column(modifier = Modifier.fillMaxWidth()) {
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

/** The user's shelf picture: [image] without a nine-patch's border, [nine] its parts (null — a plain picture). */
internal class ShelfSkin(val image: ImageBitmap, val nine: NinePatch?, val mean: Color)

/** A nine-patch, in the picture's pixels (its border taken off): the parts along each side, and the padding. */
internal class NinePatch(val xs: List<NineSeg>, val ys: List<NineSeg>, val padL: Int, val padT: Int, val padR: Int, val padB: Int)
internal class NineSeg(val from: Int, val to: Int, val stretch: Boolean)

internal object ShelfSkins {
    @Volatile private var cached: ShelfSkin? = null
    @Volatile private var version = -1

    fun get(c: android.content.Context): ShelfSkin? {
        val v = com.brahmadeo.supertonic.tts.utils.Skins.version.intValue
        if (v != version) { version = v; cached = load(c) }
        return cached
    }

    private fun load(c: android.content.Context): ShelfSkin? {
        val f = com.brahmadeo.supertonic.tts.utils.Skins.shelfFile(c)
        if (!f.isFile) return null
        val bmp = try { f.inputStream().use { android.graphics.BitmapFactory.decodeStream(it) } } catch (e: Exception) { null } ?: return null
        // a compiled nine-patch (taken from an app, as AlReader's skins are): its parts come with it;
        // a .9.png as drawn: read from its black border lines
        val compiled = bmp.ninePatchChunk?.let { fromChunk(it, bmp.width, bmp.height) }
        val nine = compiled ?: parseNine(bmp)
        val inner = if (compiled == null && nine != null) android.graphics.Bitmap.createBitmap(bmp, 1, 1, bmp.width - 2, bmp.height - 2) else bmp
        val one = android.graphics.Bitmap.createScaledBitmap(inner, 1, 1, true).getPixel(0, 0)
        return ShelfSkin(inner.asImageBitmap(), nine, Color(one or (0xff shl 24)))
    }

    /**
     * A compiled nine-patch's chunk (Res_png_9patch: counts, offsets, the padding, then the stretched
     * ranges as pairs); the device's byte order, else the file's.
     */
    fun fromChunk(ch: ByteArray, w: Int, h: Int): NinePatch? {
        if (ch.size < 32) return null
        val nx = ch[1].toInt() and 0xff; val ny = ch[2].toInt() and 0xff
        if (ch.size < 32 + 4 * (nx + ny)) return null
        for (order in listOf(java.nio.ByteOrder.LITTLE_ENDIAN, java.nio.ByteOrder.BIG_ENDIAN)) {
            val bb = java.nio.ByteBuffer.wrap(ch).order(order)
            bb.position(12)
            val pl = bb.int; val pr = bb.int; val pt = bb.int; val pb = bb.int
            bb.position(32)
            val xd = IntArray(nx) { bb.int }; val yd = IntArray(ny) { bb.int }
            fun ok(d: IntArray, n: Int) = d.isNotEmpty() && d.size % 2 == 0 && d.all { it in 0..n } && (1 until d.size).all { d[it] >= d[it - 1] }
            if (!ok(xd, w) || !ok(yd, h) || listOf(pl, pr, pt, pb).any { it < 0 || it > maxOf(w, h) }) continue
            fun stretched(d: IntArray): List<Int> = (0 until d.size / 2).flatMap { (d[2 * it] until d[2 * it + 1]).toList() }
            return NinePatch(segs(w, stretched(xd)), segs(h, stretched(yd)), pl, pt, pr, pb)
        }
        return null
    }

    private fun segs(n: Int, st: List<Int>): List<NineSeg> {
        val set = st.toHashSet()
        val out = ArrayList<NineSeg>()
        var a = 0
        while (a < n) {
            val s = a in set
            var e = a
            while (e < n && (e in set) == s) e++
            out.add(NineSeg(a, e, s)); a = e
        }
        return out
    }

    private fun black(p: Int) = (p ushr 24) == 0xff && (p and 0xffffff) == 0
    // a nine-patch's border holds only clear pixels, black marks and red layout bounds
    private fun mark(p: Int) = (p ushr 24) == 0 || black(p) || p == (0xffff0000).toInt()

    /** The border of a .9.png read: black on top/left — what stretches, on bottom/right — where the content goes. */
    fun parseNine(b: android.graphics.Bitmap): NinePatch? {
        val w = b.width; val h = b.height
        if (w < 4 || h < 4) return null
        val top = IntArray(w).also { b.getPixels(it, 0, w, 0, 0, w, 1) }
        val bottom = IntArray(w).also { b.getPixels(it, 0, w, 0, h - 1, w, 1) }
        val left = IntArray(h).also { b.getPixels(it, 0, 1, 0, 0, 1, h) }
        val right = IntArray(h).also { b.getPixels(it, 0, 1, w - 1, 0, 1, h) }
        if (!(top.all { mark(it) } && bottom.all { mark(it) } && left.all { mark(it) } && right.all { mark(it) })) return null
        val sx = (1 until w - 1).filter { black(top[it]) }.map { it - 1 }
        val sy = (1 until h - 1).filter { black(left[it]) }.map { it - 1 }
        if (sx.isEmpty() || sy.isEmpty()) return null
        val iw = w - 2; val ih = h - 2
        val px = (1 until w - 1).filter { black(bottom[it]) }.map { it - 1 }
        val py = (1 until h - 1).filter { black(right[it]) }.map { it - 1 }
        val cx = px.ifEmpty { sx }; val cy = py.ifEmpty { sy }
        return NinePatch(segs(iw, sx), segs(ih, sy), cx.first(), cy.first(), iw - 1 - cx.last(), ih - 1 - cy.last())
    }
}

/** A nine-patch drawn over the whole area: the fixed parts at [k] screen pixels per picture pixel, the rest stretched. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNine(img: ImageBitmap, n: NinePatch, k: Float) {
    fun place(segs: List<NineSeg>, dst: Float): List<Pair<NineSeg, Pair<Int, Int>>> {
        val fixed = segs.filter { !it.stretch }.sumOf { it.to - it.from } * k
        val stretch = segs.filter { it.stretch }.sumOf { it.to - it.from }.toFloat().coerceAtLeast(1f)
        val squeeze = if (fixed > dst && fixed > 0f) dst / fixed else 1f
        val rest = (dst - fixed * squeeze).coerceAtLeast(0f)
        var at = 0f
        return segs.map { s ->
            val len = if (s.stretch) rest * (s.to - s.from) / stretch else (s.to - s.from) * k * squeeze
            val r = s to (Math.round(at) to Math.round(at + len))
            at += len
            r
        }
    }
    for ((sx, dx) in place(n.xs, size.width)) for ((sy, dy) in place(n.ys, size.height)) {
        val dw = dx.second - dx.first; val dh = dy.second - dy.first
        if (dw <= 0 || dh <= 0) continue
        drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset(sx.from, sy.from),
            srcSize = androidx.compose.ui.unit.IntSize(sx.to - sx.from, sy.to - sy.from),
            dstOffset = androidx.compose.ui.unit.IntOffset(dx.first, dy.first), dstSize = androidx.compose.ui.unit.IntSize(dw, dh),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
    }
}

/** A plain picture over the whole wall, cut to fill it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCover(img: ImageBitmap) {
    val k = maxOf(size.width / img.width, size.height / img.height)
    val sw = (size.width / k).toInt().coerceIn(1, img.width); val sh = (size.height / k).toInt().coerceIn(1, img.height)
    drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
        srcSize = androidx.compose.ui.unit.IntSize(sw, sh), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
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
