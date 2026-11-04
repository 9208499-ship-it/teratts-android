package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Audio of the open book made beforehand (dullish, 4PDA): while the phone charges (or has enough
 * battery), chapter after chapter is voiced into files — the same voices, roles and pace as the
 * reading itself. When such a chapter is read, the player plays the file instead of the neural
 * network: as cheap as any audio player, and it never stalls on a phone that cuts the CPU.
 *
 * A chapter is kept as <text hash>.m4a (AAC; .wav on a phone without an encoder) with an index
 * (.json): where every phrase starts and, inside it, every sentence (for the highlight), and the
 * "fingerprint" of the settings it was made with. Other settings (another voice, model, roles,
 * dictionary…) — the file is not used; the chapter is read live and made again.
 */
object BookPrep {
    private const val PREFS = "BookPrep"
    private const val KEY_ON = "on"
    private const val KEY_MODE = "mode"
    const val MODE_CHARGING = "charging"
    const val MODE_HALF = "charging_or_half"
    const val ACTION_PREPARE = "PREPARE_BOOK"
    const val ACTION_STOP = "STOP_PREPARE"
    private const val TAG = "TeraPrep"
    private const val KEY_HOLD = "hold_until"
    private const val HOLD_MS = 3 * 60 * 60_000L

    /** What the preparing does right now, for the settings ("глава 3 из 40 · 37 %"); null — nothing. */
    val now = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** A chapter is being made right now (the engine is working for BookPrep, not for the reading). */
    @Volatile var making = false

    /** Asked for while the book was being read: starts as soon as the reading is paused or stopped. */
    @Volatile var wanted = false

    /** The voice the book is read in (as the player has it): the chapters are made in it. */
    fun voicePath(context: Context): String {
        val prefs = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val voiceDir = File(context.filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        return prefs.getString("last_voice_path", "")?.takeIf { it.isNotEmpty() }
            ?: File(voiceDir, prefs.getString("selected_voice", "ru_f1.json") ?: "ru_f1.json").absolutePath
    }

    /** Stopped from the notification: the system's job does not start it again for a few hours. */
    fun holdOff(context: Context) {
        wanted = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_HOLD, System.currentTimeMillis() + HOLD_MS).apply()
    }

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    fun mode(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_CHARGING) ?: MODE_CHARGING

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        schedule(context)
        if (on) kick(context, byHand = true) else stop(context)
    }

    /** Stops what is being made now (and a start waiting for the reading to stop). */
    fun stop(context: Context) {
        wanted = false
        if (now.value == null) return
        try {
            context.startService(Intent(context, com.brahmadeo.supertonic.tts.service.PlaybackService::class.java).setAction(ACTION_STOP))
        } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}"); now.value = null }
    }

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        schedule(context)
        kick(context, byHand = true)
    }

    /** May the phone make audio now: charging, or (if chosen) at least half the battery and no power saving. */
    fun conditionsOk(context: Context): Boolean {
        if (!enabled(context)) return false
        val bm = context.getSystemService(android.os.BatteryManager::class.java) ?: return false
        if (bm.isCharging) return true
        if (mode(context) != MODE_HALF) return false
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val saver = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
        return level >= 50 && !saver
    }

    // ---- the system's job: looks every 15 minutes (only on charge, if so chosen) ----
    private const val JOB_ID = 7341

    fun schedule(context: Context) {
        val js = context.getSystemService(android.app.job.JobScheduler::class.java) ?: return
        js.cancel(JOB_ID)
        if (!enabled(context)) return
        val b = android.app.job.JobInfo.Builder(JOB_ID,
            android.content.ComponentName(context, com.brahmadeo.supertonic.tts.service.PrepJobService::class.java))
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
        if (mode(context) == MODE_CHARGING) b.setRequiresCharging(true)
        try { js.schedule(b.build()) } catch (e: Exception) { Log.w(TAG, "schedule: ${e.message}") }
    }

    /**
     * Starts making audio now, if allowed (the service decides what there is to do). [byHand]: from
     * the settings — then also after a stop from the notification, and the settings show it at once.
     */
    fun kick(context: Context, byHand: Boolean = false) {
        if (!conditionsOk(context)) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (byHand) p.edit().remove(KEY_HOLD).apply()
        else if (System.currentTimeMillis() < p.getLong(KEY_HOLD, 0L)) return
        if (byHand && now.value == null) now.value = context.getString(com.brahmadeo.supertonic.tts.R.string.prep_starting)
        try {
            val i = Intent(context, com.brahmadeo.supertonic.tts.service.PlaybackService::class.java).setAction(ACTION_PREPARE)
                .putExtra("by_hand", byHand)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            // Android 12+: not from the background unless the app may ignore battery optimisation
            Log.w(TAG, "start: ${e.message}")
            if (byHand) now.value = null
        }
    }

    // ---- where and what ----
    fun bookDir(context: Context, bookPath: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "prepared/" + Integer.toHexString(File(bookPath).name.hashCode()))
    }

    fun textKey(text: String) = Integer.toHexString(text.hashCode()) + "_" + text.length

    /** All the settings that change how a chapter sounds (and the voice it is read in). */
    fun fingerprint(context: Context, voicePath: String): String {
        val p = PunctuationPrefs
        val r = RolePrefs
        val lex = File(context.filesDir, "user_lexicon.json").let { "${it.length()}:${it.lastModified()}" }
        val voice = File(AssetManager.voiceForEngine(voicePath.substringBefore(';'))).name
        val parts = listOf(
            AssetManager.engine(context), AssetManager.shtormPrecision(context), AssetManager.teacherOn(context),
            voice, r.enabled(context), r.charactersEnabled(context), r.maleVoice(context), r.femaleVoice(context),
            r.narratorVoice(context), r.unknownVoice(context), r.mainCount(context),
            p.noiseScale, p.pauseScale, p.paragraphPause, p.shtormLiveliness, p.shtormBrightness, p.shtormSteps, p.shtormDenoise != p.DENOISE_OFF,
            p.tightQuestionExclamation, p.tightCommasAndPeriods, AssetManager.commaQuotes,
            LexiconManager.isEnabled(), lex, EnglishTranscriber.let { it.load(context); it.enabled }
        )
        return Integer.toHexString(parts.joinToString("|").hashCode())
    }

    /** One prepared chapter: phrase i lies at samples [start[i], start[i+1]); [subs]: its sentences. */
    class Index(val file: File, val rate: Int, val starts: LongArray, val subs: List<List<Pair<Int, Long>>>)

    /** The chapter [text] of [bookPath], made with these settings, if it is there whole. */
    fun find(context: Context, bookPath: String, text: String, phrases: Int, voicePath: String): Index? {
        val dir = bookDir(context, bookPath)
        val key = textKey(text)
        val json = File(dir, "$key.json")
        if (!json.isFile) return null
        return try {
            val o = JSONObject(json.readText())
            if (o.getString("fp") != fingerprint(context, voicePath) || o.getInt("phrases") != phrases) return null
            val audio = File(dir, o.getString("file"))
            if (!audio.isFile) return null
            val st = o.getJSONArray("starts")
            val starts = LongArray(st.length()) { st.getLong(it) }
            val sb = o.getJSONArray("subs")
            val subs = (0 until sb.length()).map { i ->
                val a = sb.getJSONArray(i)
                (0 until a.length()).map { k -> val e = a.getJSONArray(k); e.getInt(0) to e.getLong(1) }
            }
            if (starts.size != phrases + 1) null else Index(audio, o.getInt("rate"), starts, subs)
        } catch (e: Exception) { Log.w(TAG, "index: ${e.message}"); null }
    }

    /** Writes the index of a chapter just made into [audio]. */
    fun saveIndex(context: Context, bookPath: String, text: String, audio: File, rate: Int, starts: List<Long>,
                  subs: List<List<Pair<Int, Long>>>, voicePath: String) {
        val o = JSONObject()
            .put("fp", fingerprint(context, voicePath)).put("phrases", starts.size - 1).put("rate", rate)
            .put("file", audio.name)
            .put("starts", JSONArray(starts))
            .put("subs", JSONArray(subs.map { s -> JSONArray(s.map { (c, at) -> JSONArray(listOf(c, at)) }) }))
        val f = File(bookDir(context, bookPath), textKey(text) + ".json")
        val part = File(f.path + ".part")
        part.writeText(o.toString())
        part.renameTo(f)
    }

    /** Chapters of [bookPath] ready with today's settings, and the space all prepared audio takes. */
    fun status(context: Context, bookPath: String, voicePath: String): Pair<Int, Long> {
        val fp = fingerprint(context, voicePath)
        val ready = bookDir(context, bookPath).listFiles { f -> f.name.endsWith(".json") }?.count { f ->
            try { JSONObject(f.readText()).getString("fp") == fp } catch (e: Exception) { false }
        } ?: 0
        val bytes = File(context.getExternalFilesDir(null) ?: context.filesDir, "prepared")
            .walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ready to bytes
    }

    /** Frees the space: every prepared chapter of every book. */
    fun deleteAll(context: Context) {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "prepared").deleteRecursively()
    }

    /**
     * Reads a prepared chapter phrase by phrase, decoding the file once from where reading starts.
     * [phrase] returns the phrase's sentences as PCM 16-bit, each with where it starts in the phrase
     * text (for the highlight); null if the file cannot be read (then the phrase is voiced live).
     */
    class Reader(private val index: Index) {
        private var ex: MediaExtractor? = null
        private var codec: MediaCodec? = null
        private var wav: RandomAccessFile? = null
        private var pos = -1L                       // the next sample the stream gives
        private val pending = java.io.ByteArrayOutputStream()
        private var eos = false

        private fun openAt(sample: Long) {
            close()
            pending.reset(); eos = false
            if (index.file.name.endsWith(".wav")) {
                wav = RandomAccessFile(index.file, "r").also { it.seek(44 + sample * 2) }
                pos = sample
                return
            }
            val e = MediaExtractor().also { it.setDataSource(index.file.path) }
            e.selectTrack(0)
            e.seekTo(sample * 1_000_000L / index.rate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val fmt = e.getTrackFormat(0)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            c.configure(fmt, null, null, 0); c.start()
            ex = e; codec = c
            pos = -1L
            // decode up to [sample]: what lies before it is dropped
            skipTo(sample)
        }

        private fun decodeMore(): Boolean {
            val e = ex ?: return false
            val c = codec ?: return false
            if (eos) return false
            val info = MediaCodec.BufferInfo()
            repeat(50) {
                val inIdx = c.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)!!
                    val n = e.readSampleData(buf, 0)
                    if (n < 0) c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else { c.queueInputBuffer(inIdx, 0, n, e.sampleTime, 0); e.advance() }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val out = c.getOutputBuffer(o)!!
                    if (pos < 0) pos = info.presentationTimeUs * index.rate / 1_000_000L
                    val bytes = ByteArray(info.size)
                    out.position(info.offset); out.get(bytes)
                    c.releaseOutputBuffer(o, false)
                    pending.write(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    return true
                }
            }
            return false
        }

        private fun skipTo(sample: Long) {
            while (true) {
                if (pos >= 0 && pos + pending.size() / 2 > sample) break
                if (!decodeMore()) break
            }
            if (pos >= 0 && pos < sample) {
                val drop = ((sample - pos) * 2).toInt().coerceAtMost(pending.size())
                val rest = pending.toByteArray().copyOfRange(drop, pending.size())
                pending.reset(); pending.write(rest)
                pos += drop / 2
            }
        }

        private fun take(samples: Long): ByteArray {
            wav?.let { f ->
                val b = ByteArray((samples * 2).toInt())
                val n = f.read(b).coerceAtLeast(0)
                pos += n / 2
                return if (n == b.size) b else b.copyOf(n)
            }
            val need = (samples * 2).toInt()
            while (pending.size() < need && decodeMore()) { }
            val all = pending.toByteArray()
            val n = minOf(need, all.size)
            pending.reset(); pending.write(all, n, all.size - n)
            pos += n / 2
            return all.copyOf(n)
        }

        fun phrase(i: Int): List<Pair<Int, ByteArray>>? = try {
            val from = index.starts[i]
            if (pos != from) openAt(from)
            val subs = index.subs.getOrNull(i).orEmpty().ifEmpty { listOf(0 to from) }
            subs.mapIndexed { k, (charAt, at) ->
                val end = if (k + 1 < subs.size) subs[k + 1].second else index.starts[i + 1]
                if (pos != at) openAt(at)
                charAt to take(end - at)
            }
        } catch (e: Exception) { Log.w(TAG, "read: ${e.message}"); close(); null }

        fun close() {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { ex?.release() } catch (_: Exception) { }
            try { wav?.close() } catch (_: Exception) { }
            codec = null; ex = null; wav = null; pos = -1L
        }
    }
}
