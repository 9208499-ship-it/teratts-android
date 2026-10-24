package com.brahmadeo.supertonic.tts.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Tags in a saved .m4a: chapter title, book title, author, track number and the book's
 * cover — players show the cover and list the chapters by name and in order. Android's
 * MediaMuxer cannot write any of it, so the finished file is edited here: the iTunes-style
 * moov/udta/meta/ilst boxes every player reads (©nam, ©alb, ©ART, trkn, covr).
 *
 * The audio is not touched. If the moov box comes after the audio (as MediaMuxer writes it)
 * it is simply rewritten at the end; if it comes first, the audio is moved by the size of the
 * tags and the chunk offsets (stco / co64) are shifted to match.
 */
object Mp4Tags {
    class Tags(
        val title: String? = null, val album: String? = null, val artist: String? = null,
        val track: Int = 0, val tracks: Int = 0, val coverJpeg: ByteArray? = null
    )

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    private fun readBoxes(f: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = f.readLong(); header = 16 }
            else if (size == 0L) size = to - pos
            if (size < header || pos + size > to) break
            out.add(Box(type, pos, size, header))
            pos += size
        }
        return out
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val o = ByteArrayOutputStream(body.size + 8)
        o.write(int32(body.size + 8)); o.write(type.toByteArray(Charsets.ISO_8859_1)); o.write(body)
        return o.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** An ilst item: ©nam etc. with a "data" box of [kind] (1 = text, 13 = JPEG, 0 = binary). */
    private fun item(type: String, kind: Int, payload: ByteArray) =
        box(type, box("data", int32(kind), int32(0), payload))

    private fun udta(t: Tags): ByteArray {
        val items = ByteArrayOutputStream()
        t.title?.takeIf { it.isNotBlank() }?.let { items.write(item("©nam", 1, it.toByteArray(Charsets.UTF_8))) }
        t.album?.takeIf { it.isNotBlank() }?.let { items.write(item("©alb", 1, it.toByteArray(Charsets.UTF_8))) }
        t.artist?.takeIf { it.isNotBlank() }?.let {
            items.write(item("©ART", 1, it.toByteArray(Charsets.UTF_8)))
            items.write(item("aART", 1, it.toByteArray(Charsets.UTF_8)))
        }
        if (t.track > 0) {
            val p = byteArrayOf(0, 0, (t.track ushr 8).toByte(), t.track.toByte(), (t.tracks ushr 8).toByte(), t.tracks.toByte(), 0, 0)
            items.write(item("trkn", 0, p))
        }
        items.write(item("©gen", 1, "Audiobook".toByteArray(Charsets.UTF_8)))
        t.coverJpeg?.let { items.write(item("covr", 13, it)) }
        val hdlr = box("hdlr", int32(0), int32(0), "mdir".toByteArray(Charsets.ISO_8859_1),
            "appl".toByteArray(Charsets.ISO_8859_1), int32(0), int32(0), byteArrayOf(0))
        val meta = box("meta", int32(0), hdlr, box("ilst", items.toByteArray()))
        return box("udta", meta)
    }

    /** Adds [t] to the .m4a [file] in place; false (file unchanged) if it is not an MP4 it understands. */
    fun write(file: File, t: Tags): Boolean {
        return try {
            RandomAccessFile(file, "rw").use { f -> writeImpl(f, t) }
        } catch (e: Exception) {
            android.util.Log.w("TeraExport", "tags not written: ${e.message}")
            false
        }
    }

    private fun writeImpl(f: RandomAccessFile, t: Tags): Boolean {
        val len = f.length()
        val top = readBoxes(f, 0, len)
        val moov = top.firstOrNull { it.type == "moov" } ?: return false
        val mdat = top.firstOrNull { it.type == "mdat" } ?: return false
        if (moov.size > 64L * 1024 * 1024) return false
        // the moov body without an old udta, plus ours
        val children = readBoxes(f, moov.start + moov.header, moov.start + moov.size)
        val body = ByteArrayOutputStream()
        for (c in children) {
            if (c.type == "udta") continue
            val b = ByteArray(c.size.toInt())
            f.seek(c.start); f.readFully(b)
            body.write(b)
        }
        body.write(udta(t))
        val newMoov = box("moov", body.toByteArray())
        if (moov.start > mdat.start) {
            // moov at the end (MediaMuxer): rewrite it there, nothing else moves
            if (moov.start + moov.size != len) return false
            f.setLength(moov.start)
            f.seek(moov.start)
            f.write(newMoov)
            return true
        }
        // moov before the audio: everything after it moves by delta; the chunk offsets move too
        val delta = (newMoov.size - moov.size).toInt()
        shiftChunkOffsets(newMoov, delta)
        val tailStart = moov.start + moov.size
        val tail = len - tailStart
        if (delta > 0) {
            f.setLength(len + delta)
            var remaining = tail
            val buf = ByteArray(1 shl 20)
            while (remaining > 0) {   // copy backwards so nothing is overwritten before it is read
                val n = minOf(buf.size.toLong(), remaining).toInt()
                val src = tailStart + remaining - n
                f.seek(src); f.readFully(buf, 0, n)
                f.seek(src + delta); f.write(buf, 0, n)
                remaining -= n
            }
        } else if (delta < 0) {
            var done = 0L
            val buf = ByteArray(1 shl 20)
            while (done < tail) {
                val n = minOf(buf.size.toLong(), tail - done).toInt()
                f.seek(tailStart + done); f.readFully(buf, 0, n)
                f.seek(tailStart + done + delta); f.write(buf, 0, n)
                done += n
            }
            f.setLength(len + delta)
        }
        f.seek(moov.start)
        f.write(newMoov)
        return true
    }

    /** stco / co64 inside [moovBytes] (trak/mdia/minf/stbl) += delta. */
    private fun shiftChunkOffsets(m: ByteArray, delta: Int) {
        fun u32(p: Int) = ((m[p].toLong() and 0xff) shl 24) or ((m[p + 1].toLong() and 0xff) shl 16) or
            ((m[p + 2].toLong() and 0xff) shl 8) or (m[p + 3].toLong() and 0xff)
        fun put32(p: Int, v: Long) { m[p] = (v ushr 24).toByte(); m[p + 1] = (v ushr 16).toByte(); m[p + 2] = (v ushr 8).toByte(); m[p + 3] = v.toByte() }
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")
        fun walk(from: Int, to: Int) {
            var p = from
            while (p + 8 <= to) {
                val size = u32(p).toInt()
                if (size < 8 || p + size > to) return
                val type = String(m, p + 4, 4, Charsets.ISO_8859_1)
                when (type) {
                    in containers -> walk(p + 8, p + size)
                    "stco" -> {
                        val n = u32(p + 12).toInt()
                        for (i in 0 until n) { val q = p + 16 + 4 * i; put32(q, u32(q) + delta) }
                    }
                    "co64" -> {
                        val n = u32(p + 12).toInt()
                        for (i in 0 until n) {
                            val q = p + 16 + 8 * i
                            val v = (u32(q) shl 32) or u32(q + 4)
                            val nv = v + delta
                            put32(q, nv ushr 32); put32(q + 4, nv and 0xffffffffL)
                        }
                    }
                }
                p += size
            }
        }
        walk(0, m.size)
    }
}
