package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A lib.ru .txt with HTML in it: no tags read aloud, headings as chapters (4PDA, 11.2026). */
class BookConverterHtmlTest {
    private fun txt(s: String): File = File.createTempFile("book", ".txt").apply { writeText(s, Charsets.UTF_8); deleteOnExit() }

    @Test fun tagsGoHeadingsBecomeChapters() {
        val body = "Так часто бывает - хлестнут дивизию плетью боевой тревоги, вырвется она на простор. ".repeat(6)
        val book = BookConverter.parseTxt(txt(
            "Начало книги. ".repeat(30) + "А мог бы...\n\n" +
            "<ul><a name=8></a><h2>6.</h2></ul>\n\n" +
            "     $body&nbsp;&mdash; &#171;да&#187;. Если x <a y, то так.\n\n" +
            "<!-- конец --> <b>жирно</b>\n"))
        val all = book.chapters.flatMap { listOf(it.title) + it.paragraphs }
        assertFalse(all.joinToString(" "), all.any { "<ul>" in it || "<h2>" in it || "name=8" in it || "<b>" in it || "<!--" in it })
        assertTrue(book.chapters.any { it.title == "6." })
        val text = all.joinToString(" ")
        assertTrue(text, "— «да»." in text)
        assertTrue(text, "x <a y" in text)      // not a tag: stays
        assertTrue(text, "жирно" in text)
    }

    @Test fun plainTextStaysAsItIs() {
        val p = "Обычный текст без разметки: 3 < 5 и 7 > 2, а & здесь просто знак. ".repeat(5)
        val book = BookConverter.parseTxt(txt("Глава 1\n\n$p\n\n$p"))
        assertEquals(p.trim(), book.chapters.last().paragraphs.first())
    }

    @Test fun markupHelper() {
        assertEquals(" 6. ", BookConverter.stripMarkup("<h2>6.</h2>"))
        assertEquals("«да» — нет", BookConverter.stripMarkup("&laquo;да&raquo; &mdash; нет"))
        assertEquals("&#99999999;", BookConverter.stripMarkup("&#99999999;"))   // beyond Unicode: left, no crash
    }
}
