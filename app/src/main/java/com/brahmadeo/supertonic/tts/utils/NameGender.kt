package com.brahmadeo.supertonic.tts.utils

/**
 * Gender of a person's name when the text itself does not show it ("— Саша, ты где?",
 * a book in the present tense). Common Russian first names with their diminutives,
 * patronymics and surnames, then word endings as a last resort. What the book says
 * ("сказала Саша") always wins over this.
 */
object NameGender {
    enum class G { M, F }

    private fun set(s: String) = s.trim().split(Regex("\\s+")).toHashSet()

    private val male = set("""
        александр алексей анатолий андрей антон аркадий арсений артём артем артур богдан борис вадим валентин
        валерий василий вениамин виктор виталий владимир владислав всеволод вячеслав гавриил геннадий георгий
        герман глеб григорий давид даниил данила денис дмитрий евгений егор елисей емельян ефим захар иван игнат
        игорь илья иннокентий иосиф кирилл климент константин кузьма лаврентий лев леонид лука макар максим марат
        марк матвей мирон михаил назар никита никифор николай олег остап павел пётр петр платон прохор родион
        роман ростислав руслан савва савелий святослав семён семен сергей станислав степан тарас тимофей тимур
        тихон трофим фёдор федор феликс филипп фома эдуард эрик юлиан юрий яков ян ярослав
        саня лёша леша алёша алеша андрюша тоша боря вадик валера вася веня витя вова володя гена гоша гриша
        дима митя егорка ваня игорёк кирюша костя коля лёва лева лёня леня макс миша мишка паша петя
        рома серёжа сережа стёпа степа тёма тема толя федя фима юра яша славик жора гера сёма сема
        джон джек питер майкл томас генри гарри уильям ричард роберт чарльз джеймс
        """)
    private val female = set("""
        александра алёна алена алина алиса алла анастасия ангелина анна антонина арина валентина валерия
        варвара василиса вера вероника виктория галина дарья диана ева евгения екатерина елена елизавета жанна
        зинаида зоя инна ирина карина кира клавдия кристина ксения лариса лидия лилия любовь людмила маргарита
        марина мария марфа милана надежда наталья наталия нина нонна оксана олеся ольга полина раиса регина римма
        светлана серафима снежана софья софия стефания таисия тамара татьяна ульяна фаина элина эльвира эмилия
        юлия яна ярослава маша маруся машенька даша катя катюша лена леночка лиза настя наташа оля таня
        танечка аня анечка ира иришка ксюша люба люда мила надя нюра поля рита света соня тоня уля юля галя
        зина лара вика ника алька
        мэри анна-мария кейт джейн элизабет сара эмма люси
        """)
    /** Names worn by both, or by men though they end in -а/-я: decided by the text only. */
    private val unisex = set("саша шура женя валя слава")
    /** Feminine names ending in a soft sign. */
    private val femaleSoft = set("любовь адель нинель изабель ассоль рахиль юдифь")

    /** M / F for a person's name, or null if it cannot be told. */
    fun guess(name: String): G? {
        val w = name.lowercase().replace('ё', 'е')
        if (w.length < 2 || !w[0].isLetter()) return null
        if (w in unisex) return null
        if (w in male || w.replace('е', 'ё') in male) return G.M
        if (w in female || w.replace('е', 'ё') in female) return G.F
        // patronymics and surnames
        if (w.endsWith("вич") || w.endsWith("ич") && w.length > 5) return G.M
        if (w.endsWith("вна") || w.endsWith("чна") || w.endsWith("ична")) return G.F
        if (Regex("(ова|ева|ёва|ина|ына|ская|цкая|ская)$").containsMatchIn(w) && w.length > 4) return G.F
        if (Regex("(ов|ев|ёв|ин|ын|ский|цкий|ской)$").containsMatchIn(w) && w.length > 3) return G.M
        // endings: -а/-я feminine, a consonant or -й masculine
        if (w in femaleSoft) return G.F
        val last = w.last()
        return when {
            last == 'а' || last == 'я' -> G.F
            last == 'й' || last in "бвгджзклмнпрстфхцчшщ" -> G.M
            else -> null
        }
    }
}
