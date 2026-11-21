package com.brahmadeo.supertonic.tts.utils

import android.os.Debug
import android.util.Log
import java.io.File

/**
 * What the app holds in memory, once a minute into the log ("TeraMem"), with what it was doing:
 * the phone killed the app while a book was read with the screen off (1.4–2.4 GB, Huawei,
 * 11.2026) and the log could not tell what had grown.
 *
 *   pss — what the system counts against the app (Debug.MemoryInfo), MB
 *   java / native — the Java heap and the native heap (the models, ONNX Runtime), MB
 *   anon / file — RSS: the app's own memory / files mapped in (the stress dictionary, code) —
 *                 the second the system can drop and read again
 */
object MemLog {
    const val TAG = "TeraMem"

    private fun statusKb(): Map<String, Long> = try {
        File("/proc/self/status").readLines().mapNotNull { l ->
            val k = l.substringBefore(':')
            if (k !in setOf("VmRSS", "RssAnon", "RssFile", "RssShmem", "VmSwap")) null
            else k to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
        }.toMap()
    } catch (_: Throwable) { emptyMap() }

    private fun mb(kb: Long?) = if (kb == null) "?" else (kb / 1024).toString()

    /** One line now; [state] says what the app is doing (reading, preparing, the models loaded). */
    fun log(state: String) {
        try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            val stats = mi.memoryStats
            fun stat(k: String) = stats[k]?.toLongOrNull()
            val st = statusKb()
            val rt = Runtime.getRuntime()
            Log.i(TAG, "pss ${mb(mi.totalPss.toLong())} MB" +
                " · java ${mb(stat("summary.java-heap"))} (used ${(rt.totalMemory() - rt.freeMemory()) / 1048576})" +
                " · native ${mb(stat("summary.native-heap"))} (malloc ${Debug.getNativeHeapAllocatedSize() / 1048576})" +
                " · code ${mb(stat("summary.code"))} · other ${mb(stat("summary.private-other"))}" +
                " · rss ${mb(st["VmRSS"])}: anon ${mb(st["RssAnon"])}, file ${mb(st["RssFile"])}, shmem ${mb(st["RssShmem"])}" +
                " · swap ${mb(st["VmSwap"])}" +
                " | $state")
        } catch (t: Throwable) {
            Log.w(TAG, "memory: ${t.message}")
        }
    }
}
