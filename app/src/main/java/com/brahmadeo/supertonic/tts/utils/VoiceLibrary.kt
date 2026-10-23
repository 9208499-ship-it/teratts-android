package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/**
 * 40 extra voices (20 male, 20 female) from the TTS Runner voice library
 * (github.com/maxfridbe/vibe_android_tts_runner, Apache-2.0): Supertonic-3 styles generated
 * by a computer, of no real person. They ship in the APK (assets/voices_lib) and are copied
 * next to the other voices on start. File names "lib_m01_…"/"lib_f01_…": the m/f lets the
 * characters' voices be picked by gender (after the TeraTTS and Supertonic ones).
 */
object VoiceLibrary {
    private const val ASSETS = "voices_lib"

    /** file → the name shown in voice lists */
    private val NAMES = mapOf(
        "lib_m01_gravel-pete.json" to "Gravel Pete — низкий, хриплый (м)",
        "lib_m02_smooth-cassius.json" to "Smooth Cassius — мягкий, лёгкий (м)",
        "lib_m03_old-salt-silas.json" to "Old Salt Silas — бывалый рассказчик (м)",
        "lib_m04_thunder-boone.json" to "Thunder Boone — громовой (м)",
        "lib_m05_mellow-marlon.json" to "Mellow Marlon — тёплый, бархатный (м)",
        "lib_m06_grizzled-gus.json" to "Grizzled Gus — грубоватый (м)",
        "lib_m07_midnight-cole.json" to "Midnight Cole — мягкий, низкий (м)",
        "lib_m08_rusty-buck.json" to "Rusty Buck — с деревенским выговором (м)",
        "lib_m09_velvet-vincent.json" to "Velvet Vincent — мягкий, глубокий (м)",
        "lib_m10_captain-crag.json" to "Captain Crag — командный (м)",
        "lib_m11_jazzy-julian.json" to "Jazzy Julian — спокойный, расслабленный (м)",
        "lib_m12_boomer-hank.json" to "Boomer Hank — густой, радушный (м)",
        "lib_m13_whispering-wade.json" to "Whispering Wade — тихий, низкий (м)",
        "lib_m14_sly-reggie.json" to "Sly Reggie — хитрый, игривый (м)",
        "lib_m15_rumble-mccoy.json" to "Rumble McCoy — раскатистый бас (м)",
        "lib_m16_sir-duncan.json" to "Sir Duncan — утончённый, британский (м)",
        "lib_m17_baron-von-bass.json" to "Baron von Bass — глубокий, аристократичный (м)",
        "lib_m18_professor-fig.json" to "Professor Fig — книжный, британский (м)",
        "lib_m19_radio-ray.json" to "Radio Ray — диктор BBC (м)",
        "lib_m20_deacon-gray.json" to "Deacon Gray — торжественный, британский (м)",
        "lib_f01_bright-birdie.json" to "Bright Birdie — звонкий, весёлый (ж)",
        "lib_f02_silky-seraphina.json" to "Silky Seraphina — мягкий, шелковистый (ж)",
        "lib_f03_bubbly-bex.json" to "Bubbly Bex — задорный (ж)",
        "lib_f04_sunny-sadie.json" to "Sunny Sadie — солнечный, тёплый (ж)",
        "lib_f05_frosty-fern.json" to "Frosty Fern — прохладный, чёткий (ж)",
        "lib_f06_cocoa-coretta.json" to "Cocoa Coretta — насыщенный, тёплый (ж)",
        "lib_f07_sparkle-sky.json" to "Sparkle Sky — искристый, высокий (ж)",
        "lib_f08_marmalade-maisie.json" to "Marmalade Maisie — нежный (ж)",
        "lib_f09_lark-lucia.json" to "Lark Lucia — лёгкий, воздушный (ж)",
        "lib_f10_honey-harlow.json" to "Honey Harlow — тёплый, мягкий (ж)",
        "lib_f11_misty-marlowe.json" to "Misty Marlowe — с придыханием (ж)",
        "lib_f12_ruby-rae.json" to "Ruby Rae — смелый (ж)",
        "lib_f13_clementine-june.json" to "Clementine June — жизнерадостный (ж)",
        "lib_f14_sassy-simza.json" to "Sassy Simza — дерзкий (ж)",
        "lib_f15_pixie-wren.json" to "Pixie Wren — тоненький, звонкий (ж)",
        "lib_f16_duchess-delphine.json" to "Duchess Delphine — царственный, британский (ж)",
        "lib_f17_nightingale-nova.json" to "Nightingale Nova — мелодичный, британский (ж)",
        "lib_f18_willow-whisper.json" to "Willow Whisper — мягкий, британский (ж)",
        "lib_f19_foxglove-faye.json" to "Foxglove Faye — загадочный, британский (ж)",
        "lib_f20_aurora-belle.json" to "Aurora Belle — неземной, британский (ж)",
    )

    /** What a voice is called in the lists: the library ones by name and manner, others by file name. */
    fun label(file: String): String {
        val f = if (file.endsWith(".json")) file else "$file.json"
        return NAMES[f] ?: file.removeSuffix(".json")
    }

    @Volatile private var done = false

    /** Copies the bundled voices that are not yet there (a few ms once they are). */
    fun install(context: Context) {
        if (done) return
        try {
            val dir = File(context.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles").apply { mkdirs() }
            for (name in context.assets.list(ASSETS) ?: emptyArray()) {
                val target = File(dir, name)
                if (target.exists()) continue
                val part = File(dir, "$name.part")
                context.assets.open("$ASSETS/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(target)
            }
            done = true
        } catch (e: Exception) {
            android.util.Log.w("VoiceLibrary", "install failed: ${e.message}")
        }
    }
}
