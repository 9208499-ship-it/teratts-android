package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** «Э» in loanwords (tolyos, 11.2026). Run: ./gradlew testDebugUnitTest */
class HardETest {
    @Test fun loanwordsGetE() {
        assertEquals("Мы зашли в кафэ у отэля, где Компьютэр стоял.", HardE.apply("Мы зашли в кафе у отеля, где Компьютер стоял."))
        assertEquals("свитэры, тэннисистка Интэрнэт", HardE.apply("свитеры, теннисистка Интернет"))
    }

    @Test fun stressAndCaseStay() {
        assertEquals("каф+э от+эле мод́эль КАФЭ", HardE.apply("каф+е от+еле мод́ель КАФЕ"))
    }

    @Test fun softWordsAndOtherWordsStay() {
        // меню, кофе, термин are said soft; «теста» is also «тесто»
        assertEquals("меню кофе термин тесто теста", HardE.apply("меню кофе термин тесто теста"))
    }
}
