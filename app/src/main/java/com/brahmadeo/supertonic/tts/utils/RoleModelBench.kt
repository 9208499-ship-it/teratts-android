package com.brahmadeo.supertonic.tts.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.LongBuffer

/**
 * Замер скорости модели ролей (опыт, в чтении не участвует): кодировщик RuModernBERT в ONNX
 * прогоняется на окне текста 2048 токенов — столько модель читает за раз. Файл модели выбирает
 * пользователь; он копируется во временную папку и после замера удаляется.
 */
object RoleModelBench {
    @Volatile var running = false
        private set

    fun run(context: Context, uri: Uri, progress: (String) -> Unit): String {
        running = true
        val f = File(context.cacheDir, "role_bench.onnx")
        try {
            progress("Копирую модель…")
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "модель"
            context.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            val env = OrtEnvironment.getEnvironment()
            val cores = Runtime.getRuntime().availableProcessors()
            val sb = StringBuilder()
            sb.append("$name: %.1f МБ, ядер %d\n".format(f.length() / 1e6, cores))
            var best = Double.MAX_VALUE
            for (threads in listOf(2, 4, cores).filter { it <= cores }.distinct()) {
                OrtSession.SessionOptions().use { so ->
                    so.setIntraOpNumThreads(threads)
                    so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    progress("Потоков $threads: загрузка модели…")
                    val tl = System.nanoTime()
                    env.createSession(f.absolutePath, so).use { s ->
                        val load = (System.nanoTime() - tl) / 1e9
                        fun once(len: Int): Double {
                            val ids = LongArray(len) { 5L + (it * 7919L) % 50000L }
                            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, len.toLong())).use { t ->
                                val a = System.nanoTime()
                                s.run(mapOf("input_ids" to t)).use { }
                                return (System.nanoTime() - a) / 1e9
                            }
                        }
                        progress("Потоков $threads: прогрев…")
                        once(512)
                        val times = (1..3).map { progress("Потоков $threads: прогон $it из 3…"); once(2048) }
                        val avg = times.average()
                        if (avg < best) best = avg
                        sb.append("Потоков %d: загрузка %.1f с, окно — %.2f с (лучшее %.2f)\n".format(threads, load, avg, times.min()))
                    }
                }
            }
            val mem = android.os.Debug.getNativeHeapAllocatedSize() / 1e6
            sb.append("Память (нативная): %.0f МБ\n".format(mem))
            sb.append("Книга ≈ 250 окон с диалогами: около %.0f мин".format(250 * best / 60))
            return sb.toString()
        } catch (e: Throwable) {
            return "Не получилось: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            f.delete()
            running = false
        }
    }
}
