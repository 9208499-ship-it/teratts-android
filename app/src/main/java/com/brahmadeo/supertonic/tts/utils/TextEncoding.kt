package com.brahmadeo.supertonic.tts.utils

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A text file's encoding found by its content. Only UTF-8 and windows-1251 were tried, and
 * windows-1251 strictly: KOI8-R and DOS (CP866) texts — the old lib.ru ones — came as Cyrillic
 * nonsense, and one 0x98 byte (undefined in 1251) threw the whole book over to broken UTF-8.
 * And a text once read in the wrong encoding and saved so ("Âàñèëèé Ãîëîâà÷åâ" — 1251 taken
 * for Western European) is valid UTF-8, shown as it is: it is turned back (tolyos, 11.2026).
 *
 * The single-byte Russian encodings are told apart by how Russian the text comes out: in the
 * right one the most frequent lower-case letters (о е а и н т…) dominate; in a wrong one they
 * come out capitals (KOI8-R ↔ 1251 swap the cases), box lines or control characters.
 */
object TextEncoding {
    private const val FREQUENT = "оеаинтсрвлкмдпуяыьгзбч"
    private val SINGLE_BYTE = listOf("windows-1251", "KOI8-R", "IBM866")
    private const val SAMPLE = 64 * 1024

    /** How Russian [s] reads (higher is better); see the object's note. */
    fun score(s: CharSequence): Int {
        var good = 0; var upper = 0; var bad = 0
        for (c in s) {
            when {
                FREQUENT.indexOf(c) >= 0 -> good++
                c in 'А'..'Я' || c == 'Ё' -> upper++
                c == '�' || (c < ' ' && c != '\n' && c != '\r' && c != '\t') ||
                    c in '─'..'▟' || c in '\u0080'..'\u009F' -> bad++
            }
        }
        return 2 * good - upper - 5 * bad
    }

    private fun strict(bytes: ByteArray, from: Int, cs: String): String? = try {
        Charset.forName(cs).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, bytes.size - from)).toString()
    } catch (_: Exception) { null }

    private fun lenient(bytes: ByteArray, len: Int, cs: String): String? = try {
        Charset.forName(cs).newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, 0, len)).toString()
    } catch (_: Exception) { null }   // a charset the phone has not got

    /** The best of the single-byte Russian encodings for [bytes]. */
    private fun singleByte(bytes: ByteArray): String {
        val n = minOf(bytes.size, SAMPLE)
        val best = SINGLE_BYTE.mapNotNull { cs -> lenient(bytes, n, cs)?.let { cs to score(it) } }
            .maxByOrNull { it.second }?.first ?: "windows-1251"
        // an undefined byte (0x98 in 1251) is no letter: dropped, not a «�» read aloud
        return (lenient(bytes, bytes.size, best) ?: String(bytes, Charsets.ISO_8859_1)).replace("�", "")
    }

    /** A text file's bytes as text: by its byte-order mark, as UTF-8, or the Russian single-byte encoding it reads best in. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return repair(String(bytes, 3, bytes.size - 3, Charsets.UTF_8))
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        // UTF-16 without a mark: in most pairs the high byte is 0x00 (spaces, Latin, digits) or
        // 0x04 (Cyrillic) — never so in a single-byte or UTF-8 text
        val pairs = minOf(bytes.size, 4096) / 2
        if (pairs >= 32) {
            var le = 0; var be = 0
            for (k in 0 until pairs) {
                val even = bytes[2 * k].toInt(); val odd = bytes[2 * k + 1].toInt()
                if (odd == 0 || odd == 4) le++
                if (even == 0 || even == 4) be++
            }
            val whole = bytes.size and 1.inv()
            if (le * 10 > pairs * 7 && le > be) return String(bytes, 0, whole, Charsets.UTF_16LE)
            if (be * 10 > pairs * 7 && be > le) return String(bytes, 0, whole, Charsets.UTF_16BE)
        }
        strict(bytes, 0, "UTF-8")?.let { return repair(it) }
        return singleByte(bytes)
    }

    // windows-1252's letters at 0x80–0x9F (the rest of the high half is Latin-1's)
    private val CP1252: Map<Char, Byte> by lazy {
        val m = HashMap<Char, Byte>()
        for (b in 0x80..0x9F) lenient(byteArrayOf(b.toByte()), 1, "windows-1252")?.singleOrNull()
            ?.takeIf { it != '�' }?.let { m[it] = b.toByte() }
        m
    }

    /**
     * A Russian text read once in the wrong encoding ("Âàñèëèé" — 1251 as Western European,
     * "Ð’Ð°Ñ" — UTF-8 as Western European) turned back; any other text as it is. Only when most
     * of its letters are À–ÿ: a French or German text has a few of them, never most.
     */
    fun repair(s: String): String {
        var letters = 0; var high = 0
        for (c in s) { if (c.isLetter()) letters++; if (c.code in 0xC0..0xFF) high++ }
        if (high < 10 || high * 10 < letters * 4) return s
        val out = ByteArray(s.length)
        for ((i, c) in s.withIndex()) {
            out[i] = when {
                c.code <= 0xFF -> c.code.toByte()
                else -> CP1252[c] ?: return s     // a character no Western European reading gives
            }
        }
        val back = strict(out, 0, "UTF-8") ?: singleByte(out)
        return if (score(back) > score(s)) back else s
    }

    /** A text that came out broken (many «�», or still Western European letters for Russian). */
    fun looksBroken(s: String): Boolean {
        var letters = 0; var high = 0; var lost = 0
        for (c in s) { if (c.isLetter()) letters++; if (c.code in 0xC0..0xFF) high++; if (c == '�') lost++ }
        return (lost >= 5 && lost * 100 > letters) || (high >= 10 && high * 10 >= letters * 4)
    }
}
