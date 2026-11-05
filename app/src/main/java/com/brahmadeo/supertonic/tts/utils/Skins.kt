package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * The user's own pictures: the page under the text and the bookshelf (a picture of the wall, or a
 * shelf in Android's nine-patch form, .9.png — as AlReader's shelf skins are). Copied into the app
 * (the picture chosen may go away), kept until changed.
 */
object Skins {
    private const val TAG = "TeraSkins"

    /** Bumped on every change: the screens take the pictures again. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun dir(c: Context) = File(c.filesDir, "skins").apply { mkdirs() }
    fun pageFile(c: Context) = File(dir(c), "page.img")
    fun shelfFile(c: Context) = File(dir(c), "shelf.img")

    /** The page: any picture, made no larger than 2048 px on its long side. */
    fun importPage(c: Context, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) false else {
            var sample = 1
            while (long / (sample * 2) >= 2048) sample *= 2
            val bmp = c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (bmp == null) false else {
                pageFile(c).outputStream().use { bmp.compress(if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
                version.intValue++
                true
            }
        }
    } catch (e: Exception) { Log.w(TAG, "page: ${e.message}"); false }

    /**
     * The shelf: copied as it is (a nine-patch's black border lines must stay exact), if it is a
     * picture of a sensible size.
     */
    fun importShelf(c: Context, uri: Uri): Boolean = try {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bytes == null || bounds.outWidth <= 2 || bounds.outHeight <= 2 || maxOf(bounds.outWidth, bounds.outHeight) > 4096) false
        else {
            shelfFile(c).writeBytes(bytes)
            version.intValue++
            true
        }
    } catch (e: Exception) { Log.w(TAG, "shelf: ${e.message}"); false }

    fun removePage(c: Context) { pageFile(c).delete(); version.intValue++ }
    fun removeShelf(c: Context) { shelfFile(c).delete(); version.intValue++ }
    fun hasPage(c: Context) = pageFile(c).isFile
    fun hasShelf(c: Context) = shelfFile(c).isFile
}
