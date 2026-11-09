package com.brahmadeo.supertonic.tts.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.LongBuffer

/**
 * Замер скорости модели ролей (опыт, в чтении не участвует): кодировщик RuModernBERT в ONNX
 * прогоняется на окне текста 2048 токенов — столько модель читает за раз. Модели (*.onnx) лежат
 * в папке приложения Android/data/<пакет>/files — туда их кладут командой adb push, без окна
 * выбора файла (пока оно открыто, система может выгрузить приложение из памяти).
 */
object RoleModelBench {
    @Volatile var running = false
        private set

    fun folder(context: Context): File? = context.getExternalFilesDir(null)

    fun models(context: Context): List<File> =
        folder(context)?.listFiles { f -> f.isFile && f.name.endsWith(".onnx", ignoreCase = true) }
            ?.sortedBy { it.length() } ?: emptyList()

    fun run(context: Context, progress: (String) -> Unit): String {
        running = true
        try {
            val files = models(context)
            if (files.isEmpty()) return "Нет файлов .onnx в папке\n${folder(context)?.absolutePath ?: "?"}"
            val env = OrtEnvironment.getEnvironment()
            val cores = Runtime.getRuntime().availableProcessors()
            val sb = StringBuilder("Ядер: $cores\n")
            for (f in files) {
                sb.append("\n${f.name} (%.0f МБ)\n".format(f.length() / 1e6))
                var best = Double.MAX_VALUE
                for (threads in listOf(2, 4, cores).filter { it <= cores }.distinct()) {
                    try {
                        OrtSession.SessionOptions().use { so ->
                            so.setIntraOpNumThreads(threads)
                            so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                            progress("${f.name}\nпотоков $threads: загрузка…")
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
                                progress("${f.name}\nпотоков $threads: прогрев…")
                                once(512)
                                val times = (1..3).map { progress("${f.name}\nпотоков $threads: прогон $it из 3…"); once(2048) }
                                val avg = times.average()
                                if (avg < best) best = avg
                                sb.append("  потоков %d: загрузка %.1f с, окно %.2f с\n".format(threads, load, avg))
                            }
                        }
                    } catch (e: Throwable) {
                        sb.append("  потоков $threads: ошибка ${e.message ?: e.javaClass.simpleName}\n")
                    }
                }
                if (best < Double.MAX_VALUE) sb.append("  книга ≈ 250 окон: около %.0f мин\n".format(250 * best / 60))
            }
            sb.append("\nПамять (нативная): %.0f МБ".format(android.os.Debug.getNativeHeapAllocatedSize() / 1e6))
            return sb.toString()
        } catch (e: Throwable) {
            return "Не получилось: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            running = false
        }
    }
}
