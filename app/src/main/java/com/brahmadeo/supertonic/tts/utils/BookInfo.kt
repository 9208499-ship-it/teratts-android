package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.readium.r2.shared.publication.services.cover
import java.io.ByteArrayOutputStream
import java.io.File

/** Title, author and cover of the book being read — for the tags of the saved audio files. */
object BookInfo {
    class Info(val title: String?, val author: String?, val coverJpeg: ByteArray?)

    suspend fun load(context: Context): Info {
        val path = BookSession.currentPath(context) ?: return Info(null, null, null)
        val pub = try { EbookParser(context).openPublication(File(path)).getOrNull() } catch (e: Throwable) { null }
            ?: return Info(null, null, null)
        return try {
            val jpeg = try {
                pub.cover()?.let { b ->
                    // players show the cover small: 800 px on the long side is plenty (and keeps each file small)
                    val scale = minOf(1f, 800f / maxOf(b.width, b.height).coerceAtLeast(1))
                    val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1),
                        (b.height * scale).toInt().coerceAtLeast(1), true) else b
                    ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                }
            } catch (e: Throwable) { Log.w("TeraExport", "no cover: ${e.message}"); null }
            Info(pub.metadata.title, pub.metadata.authors.firstOrNull()?.name, jpeg)
        } catch (e: Throwable) {
            Info(null, null, null)
        } finally {
            try { pub.close() } catch (_: Throwable) { }
        }
    }
}
