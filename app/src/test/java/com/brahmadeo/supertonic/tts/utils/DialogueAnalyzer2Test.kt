package com.brahmadeo.supertonic.tts.utils

import com.brahmadeo.supertonic.tts.utils.DialogueAnalyzer2.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lines and remarks in one paragraph (Астра, 11.2026). Run: ./gradlew testDebugUnitTest */
class DialogueAnalyzer2Test {

    /** The role of the span the first [word] in [text] falls into. */
    private fun roleAt(text: String, word: String): Role {
        val at = text.indexOf(word)
        require(at >= 0) { "no «$word» in «$text»" }
        val spans = DialogueAnalyzer2.analyze(text).spans
        return spans.first { at >= it.start && at < it.end }.role
    }

    @Test fun answerAfterQuestionTakesItsOwnRemark() {
        val t = "— Ты готова? — спросил он. — Да, — сказала она."
        assertEquals(Role.MALE, roleAt(t, "Ты готова"))
        assertEquals(Role.NARRATOR, roleAt(t, "спросил"))
        assertEquals(Role.FEMALE, roleAt(t, "Да,"))
        assertEquals(Role.NARRATOR, roleAt(t, "сказала"))
    }

    @Test fun answerAfterQuestionTheOtherWayRound() {
        val t = "— Ты готов? — спросила она. — Да, — ответил он."
        assertEquals(Role.FEMALE, roleAt(t, "Ты готов"))
        assertEquals(Role.MALE, roleAt(t, "Да,"))
    }

    @Test fun lineCutByARemarkStaysOneVoice() {
        val t = "— Я вернусь, — сказала она, — завтра."
        assertEquals(Role.FEMALE, roleAt(t, "Я вернусь"))
        assertEquals(Role.NARRATOR, roleAt(t, "сказала"))
        assertEquals(Role.FEMALE, roleAt(t, "завтра"))
    }

    @Test fun remarkWithoutSpaceBeforeTheDash() {
        val t = "— Я вернусь,— сказала она."
        assertEquals(Role.FEMALE, roleAt(t, "Я вернусь"))
        assertEquals(Role.NARRATOR, roleAt(t, "сказала"))
    }

    @Test fun remarkWithoutSpaceAfterTheDash() {
        val t = "— Я вернусь, —сказала она."
        assertEquals(Role.FEMALE, roleAt(t, "Я вернусь"))
        assertEquals(Role.NARRATOR, roleAt(t, "сказала"))
    }

    @Test fun hyphenInsideAWordIsNotADash() {
        val t = "— Кто-то пришёл, — сказал он."
        assertEquals(Role.MALE, roleAt(t, "Кто-то"))
        assertEquals(Role.NARRATOR, roleAt(t, "сказал"))
    }
}
