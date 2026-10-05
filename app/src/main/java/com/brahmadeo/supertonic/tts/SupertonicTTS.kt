package com.brahmadeo.supertonic.tts

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object SupertonicTTS {
    @Volatile
    private var nativePtr: Long = 0
    @Volatile
    private var currentModelPath: String? = null

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
    private external fun setParagraphPause(seconds: Float)

    @Synchronized
    fun isInitialized(modelPath: String): Boolean {
        if (nativePtr == 0L || currentModelPath != modelPath) return false
        return getSocClass(nativePtr) != -1
    }

    /**
     * XNNPACK accelerator switch. With it the heavy ops run in XNNPACK's own
     * pool (4 threads) and plain ORT gets 1 thread so the pools don't fight
     * over cores (ONNX Runtime's recommendation). false = plain ORT, 4 threads.
     */
    const val USE_XNNPACK = false

    @Synchronized
    fun initialize(
        modelPath: String,
        libPath: String,
        ortThreads: Int = if (USE_XNNPACK) 1 else 4,
        xnnThreads: Int = if (USE_XNNPACK) 4 else 0
    ): Boolean {
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
        nativePtr = init(modelPath, libPath, ortThreads, xnnThreads)
        try {
            android.os.Process.setThreadPriority(previousPriority)
        } catch (e: Exception) { }
        val success = nativePtr != 0L
        if (success) {
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
        if (nativePtr == 0L) {
            Log.e("SupertonicTTS", "Engine not initialized")
            return null
        }
        
        val sid = ++sessionIdCounter
        currentSession.set(SessionContext(sid, listener))
        
        try {
            setPauseScale(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.pauseScale)
            setParagraphPause(com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.paragraphPause)
            val data = synthesize(nativePtr, text, lang, stylePath, speed, bufferDuration, steps, gain)
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native synthesis exception: ${e.message}")
            return null
        } finally {
            currentSession.set(null)
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    @Synchronized
    fun getAudioSampleRate(): Int {
        if (nativePtr == 0L) return 44100
        return getSampleRate(nativePtr)
    }

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

    @Synchronized
    fun release() {
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
