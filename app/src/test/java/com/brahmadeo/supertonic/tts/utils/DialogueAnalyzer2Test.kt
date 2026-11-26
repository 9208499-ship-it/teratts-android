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

    // «один из бойцов Первого Легиона указал»: not «Легиона» (a woman's name by its «-а») — a man of the group (tolyos, 11.2026)
    @Test fun oneOfAGroupIsNotTheGroupsName() {
        val t = "– Его убил не ты, а застрелил герд Тарасов! – один из бойцов Первого Легиона указал на своего командира."
        assertEquals(Role.MALE, roleAt(t, "Его убил"))
        assertEquals(null, DialogueAnalyzer2.analyze(t).spans.first { it.role == Role.MALE }.speaker)
        val u = "– Куда теперь? – спросила одна из девушек Северной Стражи."
        assertEquals(Role.FEMALE, roleAt(u, "Куда теперь"))
    }

    // «— Жаль… — кажется, Тамару мой отказ расстроил»: hers, not a man's by «расстроил» (tolyos, 11.2026)
    @Test fun nameInAnotherCaseBeforeAThingsVerb() {
        val t = "– Жаль… – кажется, Тамару мой отказ расстроил. – Хотя, наверное, ты прав."
        assertEquals(Role.FEMALE, roleAt(t, "Жаль"))
        assertEquals(Role.FEMALE, roleAt(t, "Хотя"))
        // the object of someone's verb stays not the speaker
        assertEquals(Role.MALE, roleAt("– Пойдём, – я обнял Тамару.", "Пойдём"))
    }

    // «Я вздохнул и проговорил грустно:» and a line with no remark: the hero's, a man's — the chapter had no
    // «сказал я», his line was left «unknown» and the role model gave it to the girl (tolyos, 11.2026)
    @Test fun heroIntroWithoutAnyRemarkIsTheHeros() {
        val t = "Иришка смотрела на меня и молчала. Я вздохнул и проговорил грустно:\n" +
            "– Получается, я уже тут тридцать часов прохлаждаюсь… Постойте, друзья, неужели вы всё это время были со мной?!\n" +
            "Девушка почему-то смутилась и ответила не сразу."
        assertEquals(Role.MALE, roleAt(t, "Получается"))
        assertEquals("Я", DialogueAnalyzer2.analyze(t).spans.first { it.role == Role.MALE }.speaker)
    }
}
