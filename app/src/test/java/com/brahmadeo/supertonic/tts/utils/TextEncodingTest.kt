package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** A .txt in any encoding reads as the same Russian text (11.2026). Run: ./gradlew testDebugUnitTest */
class TextEncodingTest {
    private val text = "Василий Головачев. Реликт (книга первая)\n\n" +
        "OCR & spellcheck by HarryFan, 2 November 2000\n\nКНИГА ПЕРВАЯ\n\n" +
        "Ты готова? спросил он. Да, сказала она. Ёжик шёл по дороге и ничего не видел вокруг."

    private fun bytes(cs: String) = text.toByteArray(Charset.forName(cs))

    @Test fun utf8() = assertEquals(text, TextEncoding.decode(bytes("UTF-8")))
    @Test fun utf8WithMark() = assertEquals(text, TextEncoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("UTF-8")))
    @Test fun windows1251() = assertEquals(text, TextEncoding.decode(bytes("windows-1251")))
    @Test fun koi8r() = assertEquals(text, TextEncoding.decode(bytes("KOI8-R")))
    @Test fun dos866() = assertEquals(text, TextEncoding.decode(bytes("IBM866")))
    @Test fun utf16le() = assertEquals(text, TextEncoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes("UTF-16LE")))
    @Test fun utf16leWithoutMark() = assertEquals(text, TextEncoding.decode(bytes("UTF-16LE")))

    @Test fun windows1251WithAnUndefinedByte() {
        val b = bytes("windows-1251").toMutableList(); b.add(10, 0x98.toByte())
        assertEquals(text, TextEncoding.decode(b.toByteArray()))
    }

    @Test fun cp1251ReadAsWesternEuropeanIsTurnedBack() {
        val broken = String(bytes("windows-1251"), Charset.forName("windows-1252"))
        assertTrue(broken.startsWith("Âàñèëèé"))
        assertTrue(TextEncoding.looksBroken(broken))
        assertEquals(text, TextEncoding.repair(broken))
        assertEquals(text, TextEncoding.decode(broken.toByteArray(Charsets.UTF_8)))   // saved so as UTF-8
    }

    @Test fun utf8ReadAsLatin1IsTurnedBack() =
        assertEquals(text, TextEncoding.repair(String(bytes("UTF-8"), Charsets.ISO_8859_1)))

    @Test fun frenchStaysAsItIs() {
        val fr = "Les élèves étaient très fâchés; à côté, où était-il? Garçon, ça déçoit. ".repeat(3)
        assertEquals(fr, TextEncoding.repair(fr))
        assertFalse(TextEncoding.looksBroken(fr))
        assertEquals(fr, TextEncoding.decode(fr.toByteArray(Charsets.UTF_8)))
    }

    @Test fun russianIsNotBroken() = assertFalse(TextEncoding.looksBroken(text))
}
