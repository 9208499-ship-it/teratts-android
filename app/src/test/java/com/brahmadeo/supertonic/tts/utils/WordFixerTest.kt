package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Obvious mistakes in words put right by a dictionary (4PDA, 11.2026). Run: ./gradlew testDebugUnitTest */
class WordFixerTest {
    private val dict = ("хлестнут здесь он был то подполковник командирском ещё стоял стол кто-то кто " +
        "юго северо и западный танковые снаряды боевой тревоги сидел").split(' ').toSet()
    private fun fix(t: String, typos: Boolean = true, keep: Set<String> = emptySet()) =
        WordFixer.fix(t, { it in dict }, typos, keep)

    @Test fun hyphenBreaks() {
        assertEquals("Так хлестнут и хлестнут.", fix("Так хлес- тнут и хлес-тнут."))
        assertEquals("хлестнут", fix("хлес-\nтнут"))
    }

    @Test fun realHyphensStay() {
        assertEquals("Кто-то, юго- и северо-западный.", fix("Кто-то, юго- и северо-западный."))
    }

    @Test fun digitsForLetters() {
        assertEquals("Здесь он был, то и 2х.", fix("3десь 0н 6ыл, т0 и 2х."))
    }

    @Test fun oneLetterTypos() {
        assertEquals("в командирском кресле; подполковник.", fix("в командриском кресле; подполковнкк."))
    }

    @Test fun capitalsShortWordsAndUnknownStay() {
        // a capital — most likely a name; under five letters; nothing one letter away
        assertEquals("Ермолоф сидел. Пордполковник. еще. кресле", fix("Ермолоф сидел. Пордполковник. еще. кресле"))
    }

    @Test fun typosCanBeOffAndPersonalWordsStay() {
        assertEquals("подполковнкк", fix("подполковнкк", typos = false))
        assertEquals("подполковнкк", fix("подполковнкк", keep = setOf("подполковнкк")))
        assertEquals("хлестнут", fix("хлес- тнут", typos = false))   // hyphen breaks always
    }

    @Test fun latinTextUntouched() {
        val t = "Hello 3D world, co-operation."
        assertEquals(t, fix(t))
    }
}
