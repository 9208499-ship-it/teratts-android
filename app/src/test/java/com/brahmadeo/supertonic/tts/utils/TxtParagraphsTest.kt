package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** A .txt's paragraphs: by indents (lib.ru) or by line ends, not by blank lines only (4PDA, 11.2026). */
class TxtParagraphsTest {

    @Test fun libRuIndentedParagraphsAndTalk() {
        val t = """
     Так часто бывает - хлестнут дивизию плетью боевой тревоги, вырвется она
на простор, а ее обратно возвращают.
     - Садитесь, старший лейтенант, - не слушая рапорта предлагает
подполковник.
     Сел. На краешек.
     - Доложите, старший лейтенант,
почему вы улыбались?
""".trimStart('\n')
        assertEquals(listOf(
            "Так часто бывает - хлестнут дивизию плетью боевой тревоги, вырвется она на простор, а ее обратно возвращают.",
            "- Садитесь, старший лейтенант, - не слушая рапорта предлагает подполковник.",
            "Сел. На краешек.",
            "- Доложите, старший лейтенант, почему вы улыбались?"
        ), BookConverter.txtParagraphs(t))
    }

    @Test fun noIndentsLineWithoutFullStopIsItsOwnParagraph() {
        val t = "Ну, а теперь давайте рассмотрим главное\n" +
            "Капитан вошёл в комнату. Было тихо.\n" +
            "- Здравствуйте, - сказал он,\n" +
            "- и садитесь.\n" +
            "- Спасибо.\n" +
            "Длинный абзац, перенесённый\n" +
            "на следующую строку без точки."
        assertEquals(listOf(
            "Ну, а теперь давайте рассмотрим главное",
            "Капитан вошёл в комнату. Было тихо.",
            "- Здравствуйте, - сказал он, - и садитесь.",
            "- Спасибо.",
            "Длинный абзац, перенесённый на следующую строку без точки."
        ), BookConverter.txtParagraphs(t))
    }

    @Test fun blankLinesStillPartParagraphs() {
        val t = "Первый абзац, перенесённый\nна вторую строку.\n\nВторой абзац.\n\n\nТретий."
        assertEquals(listOf("Первый абзац, перенесённый на вторую строку.", "Второй абзац.", "Третий."),
            BookConverter.txtParagraphs(t))
    }
}
