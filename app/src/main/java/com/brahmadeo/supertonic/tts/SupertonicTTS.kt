package com.brahmadeo.supertonic.tts

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object SupertonicTTS {
    @Volatile private var nativePtr: Long = 0
    @Volatile private var currentModelPath: String? = null
    /** Read once at start-up: asking the engine later would wait for its lock (synthesis). */
    @Volatile private var cachedSampleRate: Int = 44100

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_tts")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SupertonicTTS", "Failed to load native library: ${e.message}")
        }
    }

    private external fun init(modelPath: String, libPath: String, ortThreads: Int, xnnThreads: Int): Long
    private external fun synthesize(ptr: Long, text: String, lang: String, stylePath: String, speed: Float, bufferSeconds: Float, steps: Int, gain: Float): ByteArray
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)
    private external fun reset(ptr: Long)
    private external fun setPauseScale(scale: Float)
    private external fun setNoiseScale(scale: Float)
    private external fun setPocketTemperature(value: Float)
    private external fun setPocketBrightness(value: Float)
    private external fun setPocketSteps(value: Int)
    private external fun setPocketDenoise(on: Boolean)
    private external fun timeStretchPcm(pcm: ByteArray, rate: Float, sampleRate: Int): ByteArray?

    /** PCM 16-bit mono [rate] times faster (pitch kept): audio made beforehand, played at the reading's pace. */
    fun stretch(pcm: ByteArray, rate: Float): ByteArray =
        try { timeStretchPcm(pcm, rate, cachedSampleRate) ?: pcm } catch (e: Throwable) { pcm }
    private external fun setVoiceRhythm(table: String)
    private external fun setCommaQuotes(on: Boolean)
    @Volatile private var rhythmAvailable = true
    private external fun setParagraphPause(seconds: Float)
    private external fun setFastCores(cores: IntArray)
    private external fun isXnnpackEnabled(): Boolean

    /**
     * A paused reading keeps the engine (it waits inside a phrase to go on from the very word). Any
     * other user of the engine — a reader app through the system TTS, the speed test — calls
     * [requestEngine] first: the paused reading then lets the engine go (its place is kept).
     */
    @Volatile var onEngineWanted: (() -> Unit)? = null

    fun requestEngine() {
        try { onEngineWanted?.invoke() } catch (e: Exception) { Log.w("SupertonicTTS", "requestEngine", e) }
    }

    /** "Fast cores only" (CpuCores): threads = the fast cores, pinned to them. */
    @Volatile var fastCoresOnly = false

    /**
     * No lock: called from the main thread, and the engine lock can be held for
     * seconds by model loading or a running synthesis ("приложение не отвечает").
     */
    fun isInitialized(modelPath: String): Boolean =
        nativePtr != 0L && currentModelPath == modelPath

    /**
     * XNNPACK accelerator. With it the heavy ops run in XNNPACK's own pool (4 threads) and
     * plain ORT gets 1 thread so the pools don't fight over cores (ONNX Runtime's
     * recommendation). Off by default (plain ORT on all cores was faster on the phones
     * measured); Settings → "Тест скорости" turns it on where it wins (CpuCores.applyPref).
     */
    @Volatile var useXnnpack = false

    /** Is XNNPACK built into the engine (the cargo feature)? */
    fun xnnpackAvailable(): Boolean = try { isXnnpackEnabled() } catch (e: Throwable) { false }

    /** Unload and load again with the current settings (the speed test); false if it failed. */
    @Synchronized
    fun reload(modelPath: String, libPath: String): Boolean {
        release()
        return initialize(modelPath, libPath)
    }

    @Synchronized
    fun initialize(
        modelPath: String,
        libPath: String,
        // all cores: with the screen fully off the phone caps the big cores hardest,
        // and the little ones add real throughput (measured: RTF 2.07 on / 0.6 off on 4 threads)
        ortThreads: Int = if (useXnnpack) 1 else Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        xnnThreads: Int = if (useXnnpack) 4 else 0
    ): Boolean {
        initModelPath = modelPath
        initLibPath = libPath
        if (nativePtr != 0L) {
            // Health check: Can we still talk to the engine?
            if (getSocClass(nativePtr) != -1) {
                if (currentModelPath == modelPath) {
                    Log.i("SupertonicTTS", "Engine already initialized and healthy for this path: $modelPath")
                    return true
                } else {
                    Log.i("SupertonicTTS", "Model path changed ($currentModelPath -> $modelPath). Re-initializing...")
                    release()
                }
            } else {
                Log.w("SupertonicTTS", "Engine pointer exists but is unhealthy. Re-initializing...")
                release()
            }
        }
        
        // ONNX Runtime creates its worker threads here; on Linux they inherit the
        // creating thread's priority, so raise it for the duration of init.
        val tid = android.os.Process.myTid()
        val previousPriority = android.os.Process.getThreadPriority(tid)
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (e: Exception) { }
        val fast = if (fastCoresOnly && !useXnnpack) com.brahmadeo.supertonic.tts.utils.CpuCores.fast() else emptyList()
        try { setFastCores(fast.toIntArray()) } catch (e: Throwable) { }
        val threads = if (fast.isNotEmpty()) fast.size else ortThreads
        Log.i("SupertonicTTS", "threads: $threads" + if (fast.isNotEmpty()) " on fast cores $fast" else " (all cores)")
        val before = taskIds()
        nativePtr = init(modelPath, libPath, threads, xnnThreads)
        try {
            android.os.Process.setThreadPriority(previousPriority)
        } catch (e: Exception) { }
        // the threads made while loading (ONNX Runtime's workers) and this one: the system is told
        // how fast they must be (ADPF), so it may raise the clock of the cores they run on
        if (nativePtr != 0L) openHint((taskIds() - before + tid).toIntArray())
        val success = nativePtr != 0L
        if (success) {
            cachedSampleRate = getSampleRate(nativePtr)
            currentModelPath = modelPath
            Log.i("SupertonicTTS", "Engine initialized successfully (ORT: $ortThreads, XNN: $xnnThreads) with model: $modelPath")
        } else {
            currentModelPath = null
            Log.e("SupertonicTTS", "Engine initialization FAILED")
        }
        return success
    }

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    
    // VULN-003 fix: Use an atomic SessionContext to ensure sid and listener are updated together
    private class SessionContext(val sid: Long, val listener: ProgressListener?)
    private val currentSession = AtomicReference<SessionContext?>(null)

    interface ProgressListener {
        fun onProgress(sessionId: Long, current: Int, total: Int)
        fun onAudioChunk(sessionId: Long, data: ByteArray)
    }

    fun addProgressListener(listener: ProgressListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeProgressListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        val ctx = currentSession.get()
        val sid = ctx?.sid ?: 0L
        val listener = ctx?.listener
        
        // Priority to task-specific listener
        if (listener != null) {
            listener.onProgress(sid, current, total)
        } else {
            // Only notify global listeners if no specific task listener is set
            for (l in listeners) l.onProgress(sid, current, total)
        }
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray) {
        val ctx = currentSession.get()
        val sid = ctx?.sid ?: 0L
        val listener = ctx?.listener
        
        // STRICT ISOLATION: Audio chunks ONLY go to the requester
        if (listener != null) {
            listener.onAudioChunk(sid, data)
        } else {
            // Only if no specific task listener is active (e.g. legacy app call)
            // we send to global listeners
            for (l in listeners) l.onAudioChunk(sid, data)
        }
    }

    @Volatile
    private var isCancelled = false

    fun setCancelled(cancelled: Boolean) {
        isCancelled = cancelled
    }

    // Called from JNI
    fun isCancelled(): Boolean {
        return isCancelled
    }

    @Volatile
    private var sessionIdCounter: Long = 0

    @Synchronized
    fun generateAudio(text: String, lang: String, stylePath: String, speed: Float = 1.0f, bufferDuration: Float = 0.0f, steps: Int = 5, gain: Float = 1.0f, listener: ProgressListener? = null): ByteArray? {
        lastUse = System.currentTimeMillis()
        if (nativePtr == 0L) {
            // unloaded while idle: load again (the first phrase waits a couple of seconds)
            val m = initModelPath
            val l = initLibPath
            if (m != null && l != null) {
                Log.i("SupertonicTTS", "Engine was unloaded while idle: loading it again")
                initialize(m, l)
            }
        }
        if (nativePtr == 0L) {
            Log.e("SupertonicTTS", "Engine not initialized")
            return null
        }
        
        val sid = ++sessionIdCounter
        currentSession.set(SessionContext(sid, listener))
        
        try {
            setPauseScale(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.pauseScale)
            setNoiseScale(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.noiseScale)
            if (rhythmAvailable) {
                try {
                    setVoiceRhythm(com.brahmadeo.supertonic.tts.utils.VoiceRhythm.table)
                    setCommaQuotes(com.brahmadeo.supertonic.tts.utils.AssetManager.commaQuotes)
                    setPocketTemperature(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.shtormLiveliness)
                    setPocketBrightness(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.shtormBrightness)
                }
                catch (e: UnsatisfiedLinkError) { rhythmAvailable = false }
            }
            try { setPocketSteps(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.shtormSteps) } catch (e: UnsatisfiedLinkError) { }
            // the cleaning network: always, or only where time does not matter (a book made beforehand,
            // an audiobook being saved)
            try {
                val pp = com.brahmadeo.supertonic.tts.utils.PunctuationPrefs
                val offline = com.brahmadeo.supertonic.tts.utils.BookPrep.making || com.brahmadeo.supertonic.tts.utils.ExportJob.runningHere
                setPocketDenoise(pp.shtormDenoise == pp.DENOISE_ALWAYS || (pp.shtormDenoise == pp.DENOISE_PREP && offline))
            } catch (e: UnsatisfiedLinkError) { }
            setParagraphPause(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.paragraphPause)
            // the voice in the form the running model needs (Shtorm: a recording, TeraTTS: a voice file)
            val voice = com.brahmadeo.supertonic.tts.utils.AssetManager.voiceForEngine(stylePath)
            // Shtorm makes speech frame by frame, much of it on this very thread: at the audio priority
            // it is not pushed aside on the few cores Huawei leaves to the app with the screen off
            val me = android.os.Process.myTid()
            val oldPriority = try { android.os.Process.getThreadPriority(me) } catch (e: Exception) { null }
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (e: Exception) { }
            hintAddThread(me)
            val t0 = System.nanoTime()
            val data = try {
                synthesize(nativePtr, text, lang, voice, speed, bufferDuration, steps, gain)
            } finally {
                oldPriority?.let { p -> try { android.os.Process.setThreadPriority(p) } catch (e: Exception) { } }
            }
            reportHint(data.size / 2f / cachedSampleRate.coerceAtLeast(1), System.nanoTime() - t0)
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native synthesis exception: ${e.message}")
            return null
        } finally {
            currentSession.set(null)
            // the end of use too: a phrase held through a long pause must not count as idle time
            lastUse = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    /** No lock, for the same reason as isInitialized(). */
    fun getAudioSampleRate(): Int = if (nativePtr == 0L) 44100 else cachedSampleRate

    // True once the engine has run one full synthesize() pass since process
    // start. That first pass is where XNNPACK JITs its kernels for the current
    // SoC's instruction set and where ORT lays out activation buffers. Both
    // costs are paid only once per process; subsequent calls inherit the
    // warmed state. We track this so the two services don't double-prewarm.
    @Volatile
    private var prewarmed: Boolean = false

    /**
     * Run a throwaway synthesis to warm ORT graph / XNNPACK kernels.
     *
     * Best-effort and idempotent — silently no-ops if the engine isn't
     * initialised, the voice file is missing, or a prewarm has already
     * happened. Designed to be called once per service onCreate.
     *
     * Heavy: blocks the calling thread for ~500ms-1.5s on weak SoCs. Call
     * from a background thread, not from onCreate's main-thread context.
     */
    fun prewarm(stylePath: String) {
        if (prewarmed) return
        if (nativePtr == 0L) {
            Log.w("SupertonicTTS", "prewarm skipped: engine not initialized")
            return
        }
        if (!java.io.File(stylePath).exists()) {
            Log.w("SupertonicTTS", "prewarm skipped: missing voice style $stylePath")
            return
        }
        // Use lang="en" + steps=3 for fastest possible warmup pass — the goal
        // is to compile kernels, not to produce useful audio. Result is
        // discarded by passing through generateAudio's regular path which
        // returns the PCM as a ByteArray we drop on the floor.
        try {
            val t0 = System.currentTimeMillis()
            generateAudio(
                text = ".",
                lang = "en",
                stylePath = stylePath,
                speed = 1.0f,
                bufferDuration = 0.0f,
                steps = 3,
                gain = 1.0f,
                listener = null
            )
            val dt = System.currentTimeMillis() - t0
            prewarmed = true
            Log.i("SupertonicTTS", "Prewarm complete in ${dt}ms")
        } catch (e: Throwable) {
            Log.w("SupertonicTTS", "Prewarm failed (ignored)", e)
        }
    }

    // ---- unloading when idle (EngineIdle) and loading again on demand ----
    @Volatile private var lastUse = 0L
    @Volatile private var initModelPath: String? = null
    @Volatile private var initLibPath: String? = null

    /** Unload the models if nothing has been synthesized for [minIdleMs]. */
    @Synchronized
    /** How long no phrase has been made (by anyone: the reading, BookPrep, a reader app). */
    fun idleMs(): Long = System.currentTimeMillis() - lastUse

    fun releaseIfIdle(minIdleMs: Long): Boolean {
        if (nativePtr == 0L || System.currentTimeMillis() - lastUse < minIdleMs) return false
        release()
        return true
    }

    // ---- telling the system how fast synthesis must be (Android 12+, ADPF) ----
    /** Set by the services before the engine loads: the performance hint needs a context. */
    @Volatile var appContext: android.content.Context? = null
    private var hint: Any? = null              // PerformanceHintManager.Session
    private val hintTids = java.util.TreeSet<Int>()

    private fun taskIds(): Set<Int> =
        java.io.File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    private fun openHint(tids: IntArray) {
        closeHint()
        if (android.os.Build.VERSION.SDK_INT < 31 || tids.isEmpty()) return
        val ctx = appContext ?: return
        try {
            val pm = ctx.getSystemService(android.os.PerformanceHintManager::class.java) ?: return
            hint = pm.createHintSession(tids, 500_000_000L)
            hintTids.clear(); hintTids.addAll(tids.toList())
            Log.i("SupertonicTTS", "performance hint: " + (if (hint != null) "on, ${tids.size} threads" else "not supported here"))
        } catch (e: Throwable) {
            hint = null
            Log.w("SupertonicTTS", "performance hint: ${e.message}")
        }
    }

    /** The thread that calls synthesis (it changes): added to the hint where Android allows it (14+). */
    private fun hintAddThread(tid: Int) {
        if (android.os.Build.VERSION.SDK_INT < 34 || tid in hintTids) return
        val s = hint as? android.os.PerformanceHintManager.Session ?: return
        try {
            // only threads that still live
            val live = taskIds()
            hintTids.retainAll(live); hintTids.add(tid)
            s.setThreads(hintTids.toIntArray())
        } catch (e: Throwable) { }
    }

    /** One phrase: it should be ready in half its own length (twice as fast as it is heard). */
    private fun reportHint(audioSeconds: Float, elapsedNs: Long) {
        if (android.os.Build.VERSION.SDK_INT < 31 || audioSeconds <= 0f) return
        val s = hint as? android.os.PerformanceHintManager.Session ?: return
        try {
            s.updateTargetWorkDuration((audioSeconds * 0.5f * 1e9f).toLong().coerceAtLeast(10_000_000L))
            s.reportActualWorkDuration(elapsedNs.coerceAtLeast(1L))
        } catch (e: Throwable) { }
    }

    private fun closeHint() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { (hint as? android.os.PerformanceHintManager.Session)?.close() } catch (e: Throwable) { }
        }
        hint = null
        hintTids.clear()
    }

    @Synchronized
    fun release() {
        closeHint()
        if (nativePtr != 0L) {
            Log.i("SupertonicTTS", "Releasing engine: $nativePtr")
            close(nativePtr)
            nativePtr = 0
        }
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) {
            reset(nativePtr)
        }
    }
}
