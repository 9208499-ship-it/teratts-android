package com.brahmadeo.supertonic.tts.utils

/**
 * Words of one syllable spelled with ё (рёв, мёд, лёд, шёл, ёж, счёт, днём, трёх…).
 *
 * The Silero stress dictionary has no one-syllable words — they need no stress mark — and with
 * them the letter ё was lost: «грохот и рев» was read «рев», not «рёв» (tolyos). Here are the
 * ё forms (OpenCorpora) whose е spelling is no other word; ambiguous ones («сел» — сёл, «черт» —
 * чёрт, «мел», «лев», «тем») are left as written, or to the homograph resolver. Added on top:
 * «нём» (о нём), «пётр», «стёр», «флёр» — the е spelling is all but unused.
 */
object YoMono {
    private val WORDS: Map<String, String> = """
        блёк блёкл брёл брёх бьём бьёт бьёшь ввёз ввёл взбрёл взвёз взвёл взлёт взмёл взмёт взнёс
        влёк влёт вмёл вмёрз внёс вплёл впёк впёр впёрт врём врёт врёшь втёк втёр втёрт вьём вьёт
        вьёшь вёз вёл вёрст глёт гнёзд гнём гнёт гнёшь грёб грёз днём дрём дёрг дёрн ждём ждёт ждёшь
        жжём жжён жжёт жжёшь жмём жмёт жмёшь жнём жнёт жнёшь жрём жрёт жрёшь жёг жёлн жёлт жёлчь жён
        звёзд квёл клёв клёкл клён клёст клёш крёз ксёндз кёльн лжём лжёт лжёшь льнём льнёт льнёшь
        льём льёт льёшь лёг лёд лёль лёсс лёш мнём мнёт мнёшь мрём мрёт мрёшь мсьё мёд мёрз мёрзл
        мёртв нёб нём нёс плёл плёс пнём пнёт пнёшь прём прёт прёшь псёл пхнём пхнёт пхнёшь пчёл
        пшёл пьём пьёт пьёшь пёс пёстр пётр рвём рвёт рвёшь ржём ржёт ржёшь рёв сбрёл свёз свёкл
        свёл свёрл сгнёл сгрёб сжёг скрёб слёт смёрз снёс сплёл спёк спёр спёрт стрём стёб стёг стёр
        стёрт счёл счёс счёт съёжь сёмг твёрд ткнём ткнёт ткнёшь ткём ткёт ткёшь трём трёп трёт трёх
        трёшь тёк тёп тёр тёрн тёрт тёс тёть тёш тёщ флёр фёкл хлёст цвёл чтём чтёт чтёшь чхнём
        чхнёт чхнёшь чьё чьём чёлн чёрств чёс шлёнд шлёндр шлёп шлёт шлёшь шьём шьёт шьёшь шёл шёлк
        щёк щёлк ёж ёжь ёрш
    """.trim().split(Regex("\\s+")).associateBy { it.replace('ё', 'е') }

    /** The ё form of a lowercase word of one syllable, or null. */
    fun lookup(lower: String): String? = if ('е' in lower) WORDS[lower] else null
}
