package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import com.brahmadeo.supertonic.tts.SupertonicTTS
import java.io.File

/**
 * "Fast cores only": which CPU cores are fast (the clusters above the slowest one,
 * by their maximum frequency) and whether synthesis is pinned to them.
 */
object CpuCores {
    const val PREF = "cpu_fast_cores_only"

    private fun maxFreqs(): IntArray {
        val n = Runtime.getRuntime().availableProcessors()
        return IntArray(n) { i ->
            try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
            } catch (e: Exception) { 0 }
        }
    }

    /** Fast cores (0-based ids), or empty if the cores are all alike or unknown. */
    fun fast(): List<Int> {
        val f = maxFreqs()
        if (f.isEmpty() || f.any { it <= 0 }) return emptyList()
        val min = f.minOrNull() ?: return emptyList()
        val fast = f.indices.filter { f[it] > min }
        return if (fast.size >= 2) fast else emptyList()
    }

    fun total(): Int = Runtime.getRuntime().availableProcessors()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF, true)   // measured: ×1.6 with the screen off

    /** Read the setting into the engine (before it loads). */
    fun applyPref(context: Context) {
        SupertonicTTS.fastCoresOnly = enabled(context)
    }

    /** Change the setting: the engine is reloaded on the next phrase with the new threads. */
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        SupertonicTTS.fastCoresOnly = on
        Thread { SupertonicTTS.release() }.start()
    }
}
