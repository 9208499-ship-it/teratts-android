package com.brahmadeo.supertonic.tts.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The page under the text of the book (as paper readers have it): plain (the app's colours), paper,
 * parchment with darker edges, dark. The textures are drawn by the app itself (noise, fibres), once,
 * as a small tile repeated over the page — no pictures, nothing to slow the scrolling down.
 */
internal object PageStyles {
    const val PLAIN = "plain"
    const val PAPER = "paper"
    const val PARCHMENT = "parchment"
    const val DARK = "dark"
    val ALL = listOf(PLAIN, PAPER, PARCHMENT, DARK)
    private const val KEY = "page_style"

    fun get(c: Context): String =
        c.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString(KEY, PLAIN)?.takeIf { it in ALL } ?: PLAIN

    fun set(c: Context, style: String) =
        c.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putString(KEY, style).apply()

    /** base colour, text colour, accent (highlight, buttons), grain, blotches, edge darkening */
    class Look(val base: Int, val text: Long, val accent: Long, val onAccent: Long, val grain: Int, val blotch: Int, val edges: Float)

    fun look(style: String): Look? = when (style) {
        PAPER -> Look(0xF4ECD8, 0xFF2B2118, 0xFF8B5E34, 0xFFFFFFFF, 6, 4, 0.10f)
        PARCHMENT -> Look(0xE8D2A4, 0xFF3A2A17, 0xFF8B5A2B, 0xFFFFFFFF, 8, 8, 0.38f)
        DARK -> Look(0x1E1B18, 0xFFD8CCB6, 0xFFD9A55B, 0xFF2A1D0E, 4, 4, 0.0f)
        else -> null
    }

    private val tiles = HashMap<String, ImageBitmap>()

    /** The texture's tile (256×256, seamless), made once. */
    @Synchronized
    fun tile(style: String): ImageBitmap? {
        val l = look(style) ?: return null
        return tiles.getOrPut(style) { makeTile(l, style.hashCode()) }
    }

    private fun makeTile(l: Look, seed: Int): ImageBitmap {
        val n = 256
        val rnd = java.util.Random(seed.toLong())
        // smooth blotches: value noise on a 16×16 grid that wraps round (the tile has no seams)
        val cells = 16
        val g = FloatArray(cells * cells) { rnd.nextFloat() * 2f - 1f }
        fun smooth(t: Float) = t * t * (3f - 2f * t)
        fun blot(x: Int, y: Int): Float {
            val fx = x * cells / n.toFloat(); val fy = y * cells / n.toFloat()
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            val tx = smooth(fx - x0); val ty = smooth(fy - y0)
            fun at(i: Int, j: Int) = g[((j % cells + cells) % cells) * cells + ((i % cells + cells) % cells)]
            val a = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
            val b = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
            return a + (b - a) * ty
        }
        // a few fibres: faint lines across the tile, wrapping round
        val fibre = FloatArray(n * n)
        repeat(18) {
            var x = rnd.nextInt(n).toFloat(); var y = rnd.nextInt(n).toFloat()
            val ang = rnd.nextFloat() * 6.283f
            val len = 20 + rnd.nextInt(60)
            for (k in 0 until len) {
                val px = ((x.toInt() % n) + n) % n; val py = ((y.toInt() % n) + n) % n
                fibre[py * n + px] = 1f
                x += kotlin.math.cos(ang + 0.3f * sin(k * 0.2f)); y += sin(ang + 0.3f * sin(k * 0.2f))
            }
        }
        val br = (l.base shr 16) and 0xff; val bg = (l.base shr 8) and 0xff; val bb = l.base and 0xff
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val d = (rnd.nextFloat() * 2f - 1f) * l.grain + blot(x, y) * l.blotch - fibre[y * n + x] * l.grain * 0.8f
            fun c(v: Int) = (v + d).toInt().coerceIn(0, 255)
            px[y * n + x] = (0xff shl 24) or (c(br) shl 16) or (c(bg) shl 8) or c(bb)
        }
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp.asImageBitmap()
    }
}

/** The app's colours for the chosen page: the text, the panel and the highlight in its tones. */
@Composable
internal fun PageTheme(style: String, content: @Composable () -> Unit) {
    val l = PageStyles.look(style)
    if (l == null) { content(); return }
    val base = Color(0xff000000 or l.base.toLong())
    val text = Color(l.text)
    val cs = MaterialTheme.colorScheme.copy(
        background = base, onBackground = text,
        surface = base, onSurface = text,
        surfaceVariant = base.copy(alpha = 1f).let { if (style == PageStyles.DARK) Color(0xFF2A2622) else Color(0xFFE6D9BC) },
        onSurfaceVariant = text.copy(alpha = 0.7f),
        surfaceContainer = base, surfaceContainerLow = base, surfaceContainerHigh = base,
        primary = Color(l.accent), onPrimary = Color(l.onAccent),
        secondaryContainer = Color(l.accent).copy(alpha = 0.25f)
    )
    MaterialTheme(colorScheme = cs, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** The texture of the chosen page behind the text (nothing for the plain one). */
internal fun Modifier.pageTexture(style: String): Modifier {
    val l = PageStyles.look(style) ?: return this
    val tile = PageStyles.tile(style) ?: return this
    return this.drawWithCache {
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        val edge = if (l.edges > 0f) Brush.radialGradient(
            0.55f to Color.Transparent,
            1.0f to Color(0xFF6B4A22).copy(alpha = l.edges),
            center = Offset(size.width / 2f, size.height / 2f), radius = max(size.width, size.height) * 0.75f
        ) else null
        onDrawBehind {
            drawRect(brush)
            if (edge != null) drawRect(edge)
        }
    }
}
