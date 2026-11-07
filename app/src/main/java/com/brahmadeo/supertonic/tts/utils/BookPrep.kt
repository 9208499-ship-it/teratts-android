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

    // ---- the audio of books read: deleted by itself (dullish, 4PDA) ----
    private const val KEY_CLEAN = "clean_read"
    /** A book read this far, and not opened for [CLEAN_DAYS] days, is done with: its audio goes. */
    private const val READ_SHARE = 0.9f
    private const val CLEAN_DAYS = 14

    fun autoClean(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLEAN, false)
    fun setAutoClean(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLEAN, on).apply()

    /**
     * The prepared audio no longer needed: of books gone from the library (always), and — if turned
     * on — of books read to 90 % and more and not opened for two weeks. Never the book being read.
     * Returns the bytes freed.
     */
    fun cleanUp(context: Context): Long {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "prepared")
        val dirs = base.listFiles { f -> f.isDirectory } ?: return 0L
        val books = EbookManager.getRecentBooks(context).associateBy { bookDir(context, it.path).name }
        // the library not read (or empty): nothing is taken for gone
        if (books.isEmpty()) return 0L
        val current = BookSession.currentPath(context)
        val on = autoClean(context)
        val now = System.currentTimeMillis()
        var freed = 0L
        for (d in dirs) {
            val book = books[d.name]
            val gone = when {
                book == null -> true
                !on || book.path == current -> false
                else -> {
                    val pos = BookPositions.get(context, File(book.path).name)
                    val share = BookProgress.savedShare(context, book.path)
                    pos != null && share != null && share >= READ_SHARE && now - pos.time > CLEAN_DAYS * 24L * 3600_000L
                }
            }
            if (gone) {
                val size = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (d.deleteRecursively()) { freed += size; Log.i(TAG, "cleaned ${d.name}: ${size / 1_000_000} MB") }
            }
        }
        return freed
    }
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

    /**
     * On the charger: plugged in (mains, USB, wireless). Not "charging": a full battery, or one held
     * at 80 % by the phone's battery care, is not charging, yet the phone is on the charger
     * (SECTORAS, 4PDA: on the charger preparing did not start).
     */
    fun plugged(context: Context): Boolean = try {
        val i = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (e: Exception) {
        context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
    }

    /** Why the phone may not make audio now (for the settings), or null if it may. */
    fun why(context: Context): String? {
        if (!enabled(context)) return context.getString(com.brahmadeo.supertonic.tts.R.string.prep_why_off)
        if (plugged(context)) return null
        if (mode(context) != MODE_HALF) return context.getString(com.brahmadeo.supertonic.tts.R.string.prep_why_unplugged)
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        if (level < 50) return context.getString(com.brahmadeo.supertonic.tts.R.string.prep_why_low)
        val saver = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
        if (saver) return context.getString(com.brahmadeo.supertonic.tts.R.string.prep_why_saver)
        return null
    }

    /** May the phone make audio now: on the charger, or (if chosen) at least half the battery and no power saving. */
    fun conditionsOk(context: Context): Boolean = why(context) == null

    /**
     * May the app start working when it is closed (Android 12+: only an app let off battery
     * optimisation may start its foreground work from the background — the system's job on the
     * charger, or after the reading has stopped).
     */
    fun mayRunInBackground(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Asks Android to let the app work in the background (the system's dialog). */
    fun askBackground(context: Context) {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    /** How the last preparing ended ("18:40 — Остановлено: телефон отключён от зарядки."), kept for the settings. */
    fun setNote(context: Context, text: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("note", "$time — $text").apply()
    }

    fun note(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("note", null)

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
    fun fingerprint(context: Context, voicePath: String): String =
        Integer.toHexString(settings(context, voicePath).joinToString("|") { it.second }.hashCode())

    /**
     * Fingerprints a chapter made with today's settings may carry: today's, and that of 1.24–1.26
     * (quality and cleaning always in it) — chapters made then are good too.
     */
    fun fingerprints(context: Context, voicePath: String): Set<String> {
        val p = PunctuationPrefs
        val all = settings(context, voicePath)
        // the slow Pocket model came after 1.26: nothing made before was made with it
        if (all.any { it.first == "модель Pocket" }) return setOf(fingerprint(context, voicePath))
        val base = all.filter { it.first != "качество звука" && it.first != "очистка звука" }
        val i = base.indexOfFirst { it.first == "яркость" } + 1
        val old = base.map { it.second }.toMutableList().apply {
            addAll(i, listOf(p.shtormSteps.toString(), (p.shtormDenoise != p.DENOISE_OFF).toString()))
        }
        return setOf(fingerprint(context, voicePath), Integer.toHexString(old.joinToString("|").hashCode()))
    }

    /**
     * The settings a chapter is made with, each with what it is called in the settings (for "made
     * with another voice"). What was added later (quality, cleaning) counts only when it is not
     * as it was before: chapters prepared before it came in stay good (1.24–1.25 made every
     * prepared chapter useless — read live again, the phone heating up).
     */
    fun settings(context: Context, voicePath: String): List<Pair<String, String>> {
        val p = PunctuationPrefs
        val r = RolePrefs
        val lex = File(context.filesDir, "user_lexicon.json").let { "${it.length()}:${it.lastModified()}" }
        val voice = File(AssetManager.voiceForEngine(voicePath.substringBefore(';'))).name
        val list = arrayListOf<Pair<String, Any?>>(
            "модель" to AssetManager.engine(context), "точность" to AssetManager.shtormPrecision(context),
            "учитель" to AssetManager.teacherOn(context),
            "голос" to voice, "роли" to r.enabled(context), "голоса персонажей" to r.charactersEnabled(context),
            "мужской голос" to r.maleVoice(context), "женский голос" to r.femaleVoice(context),
            "голос рассказчика" to r.narratorVoice(context), "голос прочих" to r.unknownVoice(context),
            "число главных персонажей" to r.mainCount(context),
            "выразительность" to p.noiseScale, "паузы" to p.pauseScale, "пауза абзаца" to p.paragraphPause,
            "живость" to p.shtormLiveliness, "яркость" to p.shtormBrightness,
            "паузы ?!" to p.tightQuestionExclamation, "паузы ,." to p.tightCommasAndPeriods,
            "замена запятых" to AssetManager.commaQuotes,
            "словарь" to LexiconManager.isEnabled(), "свой словарь" to lex,
            "английские слова" to EnglishTranscriber.let { it.load(context); it.enabled }
        )
        if (p.shtormSteps != 1) list.add("качество звука" to p.shtormSteps)
        if (AssetManager.engine(context) == AssetManager.ENGINE_SHTORM && AssetManager.shtormVariant(context) != AssetManager.VARIANT_FAST)
            list.add("модель Pocket" to AssetManager.shtormVariant(context))
        // 1.34: the main hero's / heroine's voices (only when set: chapters made before stay good)
        r.heroVoice(context, false).takeIf { it.isNotEmpty() }?.let { list.add("голос главного героя" to it) }
        r.heroVoice(context, true).takeIf { it.isNotEmpty() }?.let { list.add("голос главной героини" to it) }
        if (p.shtormDenoise != p.DENOISE_OFF) list.add("очистка звука" to true)
        return list.map { (k, v) -> k to v.toString() }
    }

    /** How the chapter being read is voiced, for the player and the notification; null — no preparing in play. */
    val source = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * Why the chapter [text] cannot be played from a file now: not made yet; made with other
     * settings (which, for chapters made from 1.26 on); or its text is not the same.
     */
    fun whyNot(context: Context, bookPath: String, text: String, phrases: Int, voicePath: String): String {
        val json = File(bookDir(context, bookPath), textKey(text) + ".json")
        if (!json.isFile) return context.getString(com.brahmadeo.supertonic.tts.R.string.src_not_made)
        return try {
            val o = JSONObject(json.readText())
            if (o.getString("fp") !in fingerprints(context, voicePath)) {
                val was = o.optJSONObject("settings")
                val changed = if (was == null) emptyList() else settings(context, voicePath)
                    .filter { (k, v) -> was.optString(k, "") != v }.map { it.first } +
                    was.keys().asSequence().filter { k -> settings(context, voicePath).none { it.first == k } }.toList()
                if (changed.isEmpty()) context.getString(com.brahmadeo.supertonic.tts.R.string.src_other_settings)
                else context.getString(com.brahmadeo.supertonic.tts.R.string.src_other_settings_list, changed.distinct().take(3).joinToString(", "))
            } else if (o.getInt("phrases") != phrases) context.getString(com.brahmadeo.supertonic.tts.R.string.src_other_text)
            else context.getString(com.brahmadeo.supertonic.tts.R.string.src_not_made)
        } catch (e: Exception) { context.getString(com.brahmadeo.supertonic.tts.R.string.src_not_made) }
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
            if (o.getString("fp") !in fingerprints(context, voicePath) || o.getInt("phrases") != phrases) return null
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
    /**
     * [madeWith]: the settings when the chapter was begun ([settings]) — a setting changed while it
     * was being made (roles turned off) must not give it today's fingerprint.
     */
    fun saveIndex(context: Context, bookPath: String, text: String, audio: File, rate: Int, starts: List<Long>,
                  subs: List<List<Pair<Int, Long>>>, voicePath: String, madeWith: List<Pair<String, String>>? = null) {
        val list = madeWith ?: settings(context, voicePath)
        val made = JSONObject().apply { list.forEach { (k, v) -> put(k, v) } }
        val o = JSONObject()
            .put("fp", Integer.toHexString(list.joinToString("|") { it.second }.hashCode())).put("settings", made).put("phrases", starts.size - 1).put("rate", rate)
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
        val fp = fingerprints(context, voicePath)
        val ready = bookDir(context, bookPath).listFiles { f -> f.name.endsWith(".json") }?.count { f ->
            try { JSONObject(f.readText()).getString("fp") in fp } catch (e: Exception) { false }
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
