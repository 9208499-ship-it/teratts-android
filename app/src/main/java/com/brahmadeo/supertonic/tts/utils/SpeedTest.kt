package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.brahmadeo.supertonic.tts.R
import com.brahmadeo.supertonic.tts.SupertonicTTS
import java.io.File

/**
 * Settings → "Тест скорости": which way of running the engine is fastest on THIS phone.
 * Phones differ too much to guess: on some all cores win, on others only the fast ones (the
 * little cores slow the big ones down), and XNNPACK — ONNX Runtime's accelerator — was slower
 * than the plain processor where it was tried, though phones it helps may exist. Each mode is
 * loaded in turn, warmed up with one phrase, then three Russian phrases are timed; the speed is
 * seconds of speech made per second of work (×2 = twice as fast as it is heard; under ×1
 * reading would stall). The fastest is kept and used from the next phrase.
 */
object SpeedTest {
    private const val TAG = "TeraSpeedTest"
    private const val KEY_RESULT = "speed_test_result"

    private val PHRASES = listOf(
        "Солнце садилось за горы, окрашивая небо в розовые и оранжевые тона.",
        "Она шла по тихому лесу, слушая, как поют птицы в ветвях деревьев.",
        "В старинной библиотеке хранились тысячи книг, собранных за много поколений."
    )

    /** Bumped when the mode changes: the settings rows read it again. */
    val version = mutableIntStateOf(0)

    @Volatile var running = false
        private set

    class Mode(val label: String, val fast: Boolean, val xnn: Boolean)
    class Result(val mode: Mode, val speed: Double?)

    fun modes(context: Context): List<Mode> = buildList {
        add(Mode(context.getString(R.string.speed_test_mode_all), false, false))
        if (CpuCores.fast().isNotEmpty()) add(Mode(context.getString(R.string.speed_test_mode_fast), true, false))
        if (SupertonicTTS.xnnpackAvailable()) add(Mode(context.getString(R.string.speed_test_mode_xnn), false, true))
    }

    /** The mode in use now, by name. */
    fun currentLabel(context: Context): String = when {
        CpuCores.xnnpack(context) -> context.getString(R.string.speed_test_mode_xnn)
        CpuCores.enabled(context) && CpuCores.fast().isNotEmpty() -> context.getString(R.string.speed_test_mode_fast)
        else -> context.getString(R.string.speed_test_mode_all)
    }

    fun lastResult(context: Context): String? =
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString(KEY_RESULT, null)

    /**
     * Runs the test on the calling (background) thread; [progress] gets what is being done.
     * Returns the text to show. The engine is left unloaded: the next phrase loads it in the
     * chosen mode.
     */
    fun run(context: Context, progress: (String) -> Unit): String {
        running = true
        val app = context.applicationContext
        val fast0 = CpuCores.enabled(app)
        val xnn0 = CpuCores.xnnpack(app)
        try {
            val modelPath = File(app.filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = app.applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            val voice = app.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
                .getString("selected_voice", "ru_f1.json") ?: "ru_f1.json"
            val dir = File(app.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
            val style = File(dir, voice).takeIf { it.exists() } ?: File(dir, "ru_f1.json")
            val results = ArrayList<Result>()
            for (mode in modes(app)) {
                progress(app.getString(R.string.speed_test_loading, mode.label))
                SupertonicTTS.fastCoresOnly = mode.fast
                SupertonicTTS.useXnnpack = mode.xnn
                if (!SupertonicTTS.reload(modelPath, libPath)) { results.add(Result(mode, null)); continue }
                // the first phrase pays for laying out memory (and XNNPACK's kernels): not timed
                SupertonicTTS.generateAudio(PHRASES[0], "ru", style.absolutePath, 1f, 0f, 5, 1f, null)
                progress(app.getString(R.string.speed_test_measuring, mode.label))
                val rate = SupertonicTTS.getAudioSampleRate().coerceAtLeast(1)
                var audio = 0.0
                var nanos = 0L
                var ok = true
                for (p in PHRASES) {
                    val t0 = System.nanoTime()
                    val pcm = SupertonicTTS.generateAudio(p, "ru", style.absolutePath, 1f, 0f, 5, 1f, null)
                    nanos += System.nanoTime() - t0
                    if (pcm == null) { ok = false; break }
                    audio += pcm.size / 2.0 / rate
                }
                val speed = if (ok && nanos > 0) audio / (nanos / 1e9) else null
                Log.i(TAG, "${mode.label}: ${speed ?: "failed"}")
                results.add(Result(mode, speed))
            }
            val best = results.filter { it.speed != null }.maxByOrNull { it.speed!! }
            if (best != null) CpuCores.setMode(app, best.mode.fast, best.mode.xnn)
            else CpuCores.setMode(app, fast0, xnn0)
            val lines = results.joinToString("\n") { r ->
                if (r.speed == null) app.getString(R.string.speed_test_failed, r.mode.label)
                else app.getString(R.string.speed_test_line, r.mode.label, String.format(java.util.Locale.US, "%.1f", r.speed))
            }
            val text = lines + "\n\n" + if (best != null) app.getString(R.string.speed_test_chosen, best.mode.label)
                else app.getString(R.string.speed_test_none)
            app.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                .putString(KEY_RESULT, lines.replace("\n", " · ")).apply()
            return text
        } catch (e: Throwable) {
            Log.e(TAG, "speed test failed", e)
            CpuCores.setMode(app, fast0, xnn0)
            return app.getString(R.string.speed_test_none)
        } finally {
            SupertonicTTS.release()
            running = false
            version.intValue++
        }
    }
}
