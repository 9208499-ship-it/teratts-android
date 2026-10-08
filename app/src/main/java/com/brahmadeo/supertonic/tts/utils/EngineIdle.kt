package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS

/**
 * Lets go of the engine's memory when nothing is being read, as an Android TTS engine
 * should: the models (hundreds of MB) are unloaded IDLE_MS after the last phrase, and
 * the process can then be reclaimed by the system. They load again on the next phrase
 * (a couple of seconds). Not done in "keep ready" mode (the default on Huawei, where a
 * permanently foreground engine is what makes screen-off reading work).
 */
object EngineIdle {
    const val IDLE_MS = 3 * 60 * 1000L
    const val PREF_KEEP_ALIVE = "keep_alive_foreground"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null

    /** "Keep the engine ready" is on by default only on Huawei. */
    fun defaultKeepAlive(): Boolean = false   // with fast cores even Huawei keeps up without it

    fun keepAlive(context: Context): Boolean =
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEEP_ALIVE, defaultKeepAlive())

    private val check = Runnable {
        val c = appContext ?: return@Runnable
        if (keepAlive(c)) return@Runnable
        Thread {
            if (SupertonicTTS.releaseIfIdle(IDLE_MS)) Log.i("TeraTTS", "engine released after ${IDLE_MS / 1000} s idle")
        }.start()
    }

    /** Call when a reading ends: the engine is let go once nothing has been read for IDLE_MS. */
    fun schedule(context: Context) {
        appContext = context.applicationContext
        handler.removeCallbacks(check)
        handler.postDelayed(check, IDLE_MS + 1_000L)
    }

    /** The system is short of memory: let go now unless something is being read right now. */
    fun releaseNow(context: Context) {
        if (keepAlive(context)) return
        Thread { if (SupertonicTTS.releaseIfIdle(10_000L)) Log.i("TeraTTS", "engine released: low memory") }.start()
    }
}
