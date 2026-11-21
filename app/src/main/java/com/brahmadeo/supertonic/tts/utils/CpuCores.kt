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
    /** XNNPACK on (chosen by the speed test when it is the fastest on this phone). */
    const val PREF_XNN = "engine_xnnpack"

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
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun xnnpack(context: Context): Boolean =
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF_XNN, false) &&
            SupertonicTTS.xnnpackAvailable()

    private const val PREF_LEAN = "lean_memory"

    /** "Экономить память": on unless turned off (see helper.rs, LEAN_MEMORY). */
    fun lean(context: Context): Boolean =
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF_LEAN, true)

    /** Change it: the engine is let go and loaded again on the next phrase, the roles' model at its next load. */
    fun setLean(context: Context, on: Boolean) {
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_LEAN, on).apply()
        SupertonicTTS.leanMemory = on
        Thread { SupertonicTTS.release() }.start()
    }

    /** Read the setting into the engine (before it loads). */
    fun applyPref(context: Context) {
        SupertonicTTS.leanMemory = lean(context)
        SupertonicTTS.fastCoresOnly = enabled(context)
        SupertonicTTS.useXnnpack = xnnpack(context)
    }

    /** Saves a mode chosen by the speed test (the engine is reloaded by the caller). */
    fun setMode(context: Context, fast: Boolean, xnn: Boolean) {
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF, fast).putBoolean(PREF_XNN, xnn).apply()
        SupertonicTTS.fastCoresOnly = fast
        SupertonicTTS.useXnnpack = xnn
    }

    /** Change the setting: the engine is reloaded on the next phrase with the new threads. */
    fun set(context: Context, on: Boolean) {
        // a choice by hand is a plain-processor mode: XNNPACK (if the test chose it) goes off
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF, on).putBoolean(PREF_XNN, false).apply()
        SupertonicTTS.fastCoresOnly = on
        SupertonicTTS.useXnnpack = false
        SpeedTest.version.intValue++
        Thread { SupertonicTTS.release() }.start()
    }
}
