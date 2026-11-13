package com.brahmadeo.supertonic.tts.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer

/**
 * The role model (опыт): a RuModernBERT trained to tell who says a line where the author does not
 * say it (no «— …, — сказал он»). The rules stay the basis; the model only overrules them where it is
 * sure, mostly on the lines with no author words. An optional download (~215 MB) into the app's
 * folder; without it reading by roles is exactly as before.
 *
 * It reads the chapter in windows (RoleWindows) — about 6 s a window on a phone — so it never runs
 * while a chapter is started: it works out the NEXT chapter while this one is read, and the book's
 * chapters when the book is prepared (BookPrep). What it found is kept per chapter (a small file).
 * A chapter is read window by window, each kept: one left half read (a call, the reading gone elsewhere)
 * is gone on with, and what it has is used meanwhile.
 */
object RoleModel {
    private const val TAG = "RoleModel"
    private const val PREFS = "TeraRoleModel"
    private const val RELEASE = "role-model-m10b"
    private const val BASE = "https://github.com/9208499-ship-it/teratts-android/releases/download/$RELEASE/"
    /** Bumped with the model or the rules it was trained on top of: what was worked out before is made again. */
    // 1.56: the windows are those of training (the neighbours' author words are mentions again) — what was
    // worked out on the windows of 1.50–1.55 is not taken
    // 1.59: m10b (m8b taught on 40 more books, marked by Haiku) — its own results; the m8b of an older
    // version, still here until m10b is downloaded, keeps its own (OLD_VERSION)
    private const val VERSION = "m10b-159"
    private const val OLD_VERSION = "m8b-157"
    val FILES = listOf("roles_enc_int8.onnx", "roles_head.onnx", "tokenizer.json", "roles_meta.json")
    private const val MAXTOK = 2048
    private const val NB = 12

    // the policy: a line with no author words — the model from HARD_OVER; one with them — the rules, the model
    // only from 0.9; the rules silent — from FILL. m8b (197 hand-checked lines and «Алиса»): 0.6 / 0.5;
    // m10b (1668 lines of three books outside its training, by the phone's own code, and the 197): 0.5 / 0.3 —
    // the speaker right in 59.8 % (m8b 56.5 %), a wrong voice sex in 92 lines (m8b 108)
    @Volatile private var HARD_OVER = 0.5f
    private const val EXPL_OVER = 0.9f
    @Volatile private var FILL = 0.3f

    /** The files of the release as they must be: a file cut short or damaged is not a model (1.54). */
    private val SIZE = mapOf("roles_enc_int8.onnx" to 208313718L, "roles_head.onnx" to 4013587L,
        "tokenizer.json" to 4753266L, "roles_meta.json" to 233L)
    private val SHA256 = mapOf(
        "roles_enc_int8.onnx" to "ed0b1cfc6373631bd9e81d6b81943b192f6193baff869fe4816d5d541855819e",
        "roles_head.onnx" to "f5f359ee8cb8e709969f2713410c18e23abc1b3024c909394e85c6ffcf248e65",
        "tokenizer.json" to "c166ae391d765915521dce27bf940cc9c7bff5050260f652f9327a7df1b9cd56",
        "roles_meta.json" to "e404e8bb91b9175d308c4a5793e063d164fe8845448c554949285849f882a7ee")
    /** m8b (1.48–1.58): known by its sizes (it had no mark); kept in use until m10b is downloaded. */
    private val OLD_SIZE = mapOf("roles_enc_int8.onnx" to 208313718L, "roles_head.onnx" to 4014896L,
        "tokenizer.json" to 4753266L, "roles_meta.json" to 234L)
    /** Which release the files are (m10b's encoder is as long as m8b's: the size alone does not tell). */
    private const val MARK = "release.txt"

    fun dir(c: Context) = File(c.filesDir, "role_model")
    /** A new release is downloaded here, beside the model in use; it replaces it only whole and checked. */
    private fun nextDir(c: Context) = File(c.filesDir, "role_model.next")

    /** 2 — the current release, 1 — m8b of an older version, 0 — none. Sets the policy of the model found. */
    private fun which(c: Context): Int {
        val d = dir(c)
        val m = when {
            FILES.all { File(d, it).length() == SIZE[it] } &&
                (try { File(d, MARK).readText().trim() } catch (_: Exception) { "" }) == RELEASE -> 2
            FILES.all { File(d, it).length() == OLD_SIZE[it] } -> 1
            else -> 0
        }
        if (m == 1) { HARD_OVER = 0.6f; FILL = 0.5f } else { HARD_OVER = 0.5f; FILL = 0.3f }
        return m
    }
    fun installed(c: Context) = which(c) > 0
    /** The model in use is the current release (not m8b waiting to be replaced). */
    fun upToDate(c: Context) = which(c) == 2
    private fun version(c: Context) = if (which(c) == 1) OLD_VERSION else VERSION
    fun size(c: Context): Long = FILES.sumOf { File(dir(c), it).length() }
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun wanted(c: Context) = p(c).getBoolean("on", true)
    fun setWanted(c: Context, on: Boolean) = p(c).edit().putBoolean("on", on).apply()
    fun enabled(c: Context) = wanted(c) && installed(c)

    /** Download progress 0…1, or null when no download is going on. */
    val progress = androidx.compose.runtime.mutableStateOf<Float?>(null)

    /**
     * All the files from the release, one after another (.part until whole and checked). A download broken
     * off (the network gone, GitHub's 502, the app closed) goes on from where it stopped — 208 MB are not
     * begun again; each file is checked by its size and SHA-256 before it is taken. Throws on failure.
     */
    suspend fun download(c: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (upToDate(c)) return@withContext
        // beside the model in use (m8b goes on working meanwhile); put in its place only when all is here
        val d = nextDir(c).apply { mkdirs() }
        val all = SIZE.values.sum().toFloat()
        var before = 0L
        progress.value = 0f
        try {
            for (name in FILES) {
                val f = File(d, name)
                val size = SIZE.getValue(name)
                if (f.length() != size) {
                    f.delete()
                    val part = File(d, "$name.part")
                    var tries = 0
                    while (true) {
                        try {
                            fetch(name, part, size) { done -> progress.value = (before + done) / all }
                            break
                        } catch (e: java.io.IOException) {
                            if (++tries >= 5) throw e
                            Log.i(TAG, "download: $name — ${e.message}; again ($tries)")
                            kotlinx.coroutines.delay(2000L * tries)
                        }
                    }
                    if (sha256(part) != SHA256[name]) { part.delete(); throw IllegalStateException("$name: файл повреждён при загрузке — скачайте ещё раз") }
                    if (!part.renameTo(f)) throw IllegalStateException("$name: не удалось сохранить")
                }
                before += size
                progress.value = before / all
            }
            swapIn(c)
        } finally {
            progress.value = null
        }
    }

    /**
     * The release downloaded in [nextDir] takes the place of the model in use — once no window is being read
     * (the model closed under one is a native crash, 1.56). What the old model worked out goes (another VERSION).
     */
    private fun swapIn(c: Context) {
        aborting = true
        val mine = try { lock.tryLock(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        try {
            if (!mine) throw IllegalStateException("модель ролей занята разбором главы — нажмите ещё раз через минуту")
            unload()
            tok = null
            val d = dir(c).apply { mkdirs() }
            d.listFiles()?.forEach { it.delete() }
            for (name in FILES) if (!File(nextDir(c), name).renameTo(File(d, name))) throw IllegalStateException("$name: не удалось сохранить")
            File(d, MARK).writeText(RELEASE)
            nextDir(c).deleteRecursively()
            File(c.filesDir, "role_model_cache").listFiles()?.forEach { s ->
                s.listFiles()?.filter { !it.name.startsWith(VERSION + "_") }?.forEach { it.delete() }
            }
            Log.i(TAG, "model: $RELEASE in place")
        } finally {
            if (mine) lock.unlock()
            aborting = false
        }
    }

    /** One file into [part], after what [part] already has. IOException: worth another try. */
    private fun fetch(name: String, part: File, size: Long, onProgress: (Long) -> Unit) {
        var have = part.length()
        if (have > size) { part.delete(); have = 0 }
        if (have == size) return
        val con = URL(BASE + name).openConnection() as HttpURLConnection
        try {
            con.setRequestProperty("User-Agent", "TeraTTS")
            con.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) con.setRequestProperty("Range", "bytes=$have-")
            con.connectTimeout = 20000; con.readTimeout = 30000
            con.instanceFollowRedirects = true
            val code = con.responseCode
            when {
                code == 206 && have > 0 -> { }
                code == 200 -> have = 0                     // the whole file again
                code == 416 -> { part.delete(); throw java.io.IOException("$name: HTTP 416") }
                code in 400..499 && code != 408 && code != 429 -> throw IllegalStateException("$name: HTTP $code")
                else -> throw java.io.IOException("$name: HTTP $code")
            }
            con.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); have += n
                        onProgress(have)
                    }
                }
            }
        } finally {
            con.disconnect()
        }
        if (have != size) throw java.io.IOException("$name: скачался не целиком ($have из $size)")
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Set while the model is being deleted: a window being read stops, nothing new is begun. */
    @Volatile private var aborting = false

    /**
     * The model and what it worked out, deleted — after the window being read (if any) is stopped: the model
     * was closed under it before 1.56 (a native crash). On its own thread: the screen does not wait.
     */
    fun delete(c: Context) {
        aborting = true
        kotlin.concurrent.thread(name = "role-model-delete") {
            val mine = try { lock.tryLock(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
            try {
                unload()
                tok = null
                dir(c).listFiles()?.forEach { it.delete() }
                nextDir(c).deleteRecursively()
                File(c.filesDir, "role_model_cache").deleteRecursively()
            } finally {
                if (mine) lock.unlock()
                aborting = false
            }
        }
    }

    // ------------------------------------------------------------------ the model

    private var env: OrtEnvironment? = null
    private var enc: OrtSession? = null
    private var head: OrtSession? = null
    private var tok: RoleTokenizer? = null
    private var temp = 1f

    @Synchronized
    private fun load(c: Context, threads: Int): Boolean {
        if (enc != null) return true
        return try {
            val d = dir(c)
            val meta = org.json.JSONObject(File(d, "roles_meta.json").readText())
            temp = meta.optDouble("T", 1.0).toFloat()
            // the tokenizer (4.7 MB of JSON) is parsed once and kept (~10 MB): it was parsed again at every load
            if (tok == null) tok = tokenizerOf(File(d, "tokenizer.json"))
            val e = OrtEnvironment.getEnvironment()
            env = e
            fun session(f: String): OrtSession = OrtSession.SessionOptions().use { so ->
                so.setIntraOpNumThreads(threads)
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // threads waiting for work sleep instead of spinning: the cores go to the voice engine
                try { so.addConfigEntry("session.intra_op.allow_spinning", "0") } catch (_: Throwable) { }
                e.createSession(File(d, f).absolutePath, so)
            }
            enc = session(meta.optString("enc", "roles_enc_int8.onnx"))
            head = session(meta.optString("head", "roles_head.onnx"))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            BookSession.log(c, "модель ролей: не загрузилась — ${t.message ?: t.javaClass.simpleName}")
            unload()
            false
        }
    }

    @Synchronized
    fun unload() {
        try { enc?.close() } catch (_: Throwable) { }
        try { head?.close() } catch (_: Throwable) { }
        enc = null; head = null      // the tokenizer stays (see load)
    }

    private fun tokenizerOf(f: File): RoleTokenizer {
        val o = org.json.JSONObject(f.readText())
        val model = o.getJSONObject("model")
        val v = model.getJSONObject("vocab")
        val vocab = HashMap<String, Int>(v.length() * 2)
        for (k in v.keys()) vocab[k] = v.getInt(k)
        val m = model.getJSONArray("merges")
        val merges = ArrayList<Pair<String, String>>(m.length())
        for (i in 0 until m.length()) {
            val x = m.get(i)
            if (x is org.json.JSONArray) merges += x.getString(0) to x.getString(1)
            else { val s = x.toString(); val k = s.indexOf(' '); merges += s.substring(0, k) to s.substring(k + 1) }
        }
        val added = HashMap<String, Int>()
        var cls = -1; var sep = -1
        val a = o.getJSONArray("added_tokens")
        for (i in 0 until a.length()) {
            val t = a.getJSONObject(i)
            val content = t.getString("content")
            if (content == "[CLS]") cls = t.getInt("id")
            if (content == "[SEP]") sep = t.getInt("id")
            if (!t.optBoolean("special")) added[content] = t.getInt("id")
        }
        return RoleTokenizer(vocab, merges, added, cls, sep)
    }

    /** The model's guess for a line: [speaker] with [prob]; [kind] — the rules' kind of the line (0: no author words). */
    class Guess(val speaker: String, val prob: Float, val kind: Int)

    fun tokSpan(st: IntArray, en: IntArray, a: Int, b: Int): IntArray? {
        var s = -1
        for (i in st.indices) if (en[i] > a && st[i] < b && en[i] > st[i]) { s = i; break }
        if (s < 0) return null
        var e = s
        while (e < st.size && st[e] < b) e++
        return intArrayOf(s, maxOf(e, s + 1))
    }

    private fun bucket(d: Int): Long {
        if (d == 0) return NB.toLong()
        val b = minOf(NB - 1, (Math.log((Math.abs(d) + 1).toDouble()) / Math.log(2.0)).toInt())
        return (NB + (b + 1) * (if (d > 0) 1 else -1)).toLong()
    }

    /** What the model is given for a window (the same as spk_joint.encode makes). */
    class Inputs(val ids: LongArray, val qs: LongArray, val ms: LongArray, val kinds: LongArray, val dist: LongArray,
                 val q: Int, val m: Int, val ents: List<String>, val entOf: IntArray, val targets: List<RoleWindows.Target>)

    fun inputs(t: RoleTokenizer, w: RoleWindows.Window): Inputs? {
        val code = t.encode(w.text)
        val st = code.start; val en = code.end
        var tg = w.targets.mapNotNull { x -> tokSpan(st, en, x.span[0], x.span[1])?.let { x to it } }
        if (tg.isEmpty()) return null
        var lo = 0; var hi = code.ids.size
        if (hi > MAXTOK) {
            val c0 = tg.minOf { it.second[0] }; val c1 = tg.maxOf { it.second[1] }
            val mid = (c0 + c1) / 2
            lo = maxOf(0, minOf(mid - MAXTOK / 2, code.ids.size - MAXTOK))
            hi = lo + MAXTOK
            tg = tg.filter { it.second[0] >= lo && it.second[1] <= hi }
            if (tg.isEmpty()) return null
        }
        val ments = w.ments.mapNotNull { m -> tokSpan(st, en, m.a, m.b)?.takeIf { it[0] >= lo && it[1] <= hi }?.let { m to it } }
        if (ments.isEmpty()) return null
        val ents = ments.map { it.first.ent }.toSortedSet().toList()
        val eidx = ents.withIndex().associate { it.value to it.index }
        val q = tg.size; val mm = ments.size
        val kinds = LongArray(q * mm); val dist = LongArray(q * mm)
        fun inside(a: Int, b: Int, r: List<IntArray>) = r.any { it[0] <= a && b <= it[1] }
        for ((qi, pair) in tg.withIndex()) {
            val (x, ts) = pair
            for ((mi, mp) in ments.withIndex()) {
                val (m, ms) = mp
                val loc = when {
                    inside(m.a, m.b, x.speech) -> 2
                    x.span[0] <= m.a && m.b <= x.span[1] -> 3
                    inside(m.a, m.b, w.speech) -> 1
                    else -> 0
                }
                kinds[qi * mm + mi] = (m.type * 4 + loc).toLong()
                val d = if (ms[0] >= ts[0] && ms[1] <= ts[1]) 0 else if (ms[0] >= ts[1]) ms[0] - ts[1] else ms[0] - ts[0]
                dist[qi * mm + mi] = bucket(d)
            }
        }
        val ids = LongArray(hi - lo) { code.ids[lo + it].toLong() }
        val qs = LongArray(q * 2).also { a -> tg.forEachIndexed { i, (_, s) -> a[2 * i] = (s[0] - lo).toLong(); a[2 * i + 1] = (s[1] - lo).toLong() } }
        val msp = LongArray(mm * 2).also { a -> ments.forEachIndexed { i, (_, s) -> a[2 * i] = (s[0] - lo).toLong(); a[2 * i + 1] = (s[1] - lo).toLong() } }
        return Inputs(ids, qs, msp, kinds, dist, q, mm, ents, IntArray(mm) { eidx.getValue(ments[it].first.ent) }, tg.map { it.first })
    }

    /** A window stopped midway (see [window]). */
    private class Stopped : Exception()

    /**
     * [block] with run options that end the model's run as soon as [stop] says so (looked at every 0.2 s):
     * a window is some ten seconds, and a reading started, a pause before a call, preparing stopped no
     * longer wait for its end (1.56). Throws [Stopped] if it was stopped.
     */
    private fun <T> stoppable(stop: () -> Boolean, block: (OrtSession.RunOptions) -> T): T =
        OrtSession.RunOptions().use { ro ->
            val ended = java.util.concurrent.atomic.AtomicBoolean(false)
            val watch = Thread({
                try {
                    while (!ended.get()) {
                        if (stop()) { ro.setTerminate(true); break }
                        Thread.sleep(200)
                    }
                } catch (_: InterruptedException) { } catch (_: Throwable) { }
            }, "role-model-stop").apply { isDaemon = true; start() }
            try {
                block(ro)
            } catch (t: Throwable) {
                if (stop()) throw Stopped() else throw t
            } finally {
                ended.set(true)
                watch.interrupt()
                try { watch.join(1000) } catch (_: InterruptedException) { }
            }
        }

    /** One window: the guesses for its lines (paragraph number → guess). */
    private fun window(w: RoleWindows.Window, stop: () -> Boolean): Map<Int, Guess> {
        val t = tok ?: return emptyMap()
        val e = env ?: return emptyMap()
        val inp = inputs(t, w) ?: return emptyMap()
        val ids = inp.ids; val qs = inp.qs; val msp = inp.ms; val kinds = inp.kinds; val dist = inp.dist
        val q = inp.q; val mm = inp.m; val ents = inp.ents
        val scores: Array<FloatArray> = stoppable(stop) { ro -> OnnxTensor.createTensor(e, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { idT ->
            enc!!.run(mapOf("input_ids" to idT), ro).use { r1 ->
                val hidden = r1.get(0) as OnnxTensor
                OnnxTensor.createTensor(e, LongBuffer.wrap(qs), longArrayOf(q.toLong(), 2)).use { qT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(msp), longArrayOf(mm.toLong(), 2)).use { mT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(kinds), longArrayOf(q.toLong(), mm.toLong())).use { kT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(dist), longArrayOf(q.toLong(), mm.toLong())).use { dT ->
                    head!!.run(mapOf("hidden" to hidden, "qspans" to qT, "mspans" to mT, "kinds" to kT, "dist" to dT), ro).use { r2 ->
                        @Suppress("UNCHECKED_CAST")
                        (r2.get(0).value as Array<FloatArray>)
                    }
                } } } }
            }
        } }
        val entOf = inp.entOf
        val out = HashMap<Int, Guess>()
        for ((qi, target) in inp.targets.withIndex()) {
            // a character's score: logsumexp over its mentions; then softmax with the temperature
            val lg = DoubleArray(ents.size) { -1e4 }
            for (k in ents.indices) {
                var mx = Double.NEGATIVE_INFINITY
                for (mi in 0 until mm) if (entOf[mi] == k) mx = maxOf(mx, scores[qi][mi].toDouble())
                if (mx == Double.NEGATIVE_INFINITY) continue
                var s = 0.0
                for (mi in 0 until mm) if (entOf[mi] == k) s += Math.exp(scores[qi][mi] - mx)
                lg[k] = mx + Math.log(s)
            }
            val top = lg.maxOrNull() ?: continue
            var z = 0.0
            val pr = DoubleArray(lg.size) { Math.exp((lg[it] - top) / temp).also { v -> z += v } }
            val best = pr.indices.maxByOrNull { pr[it] } ?: continue
            out[target.pi] = Guess(ents[best], (pr[best] / z).toFloat(), target.kind)
        }
        return out
    }

    // ------------------------------------------------------------------ chapters

    private fun cacheFile(c: Context, scope: String, text: String) =
        File(c.filesDir, "role_model_cache/" + Integer.toHexString(scope.hashCode()) + "/" + version(c) + "_" + BookPrep.textKey(text) + ".json")

    private fun readCache(f: File): Map<Int, Guess>? = try {
        if (!f.exists()) null else {
            val o = org.json.JSONObject(f.readText())
            val m = HashMap<Int, Guess>()
            for (k in o.keys()) { val a = o.getJSONArray(k); m[k.toInt()] = Guess(a.getString(0), a.getDouble(1).toFloat(), a.getInt(2)) }
            m
        }
    } catch (_: Exception) { null }

    /** Is this chapter worked out already? */
    fun ready(c: Context, scope: String, text: String) = scope.isNotEmpty() && cacheFile(c, scope, text).exists()

    /** Room for the model (~300 MB) now? Loaded on a full phone it gets the app — and the reading — closed by the system. */
    private fun room(c: Context): Boolean = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        !mi.lowMemory && mi.availMem > 500L * 1024 * 1024
    } catch (_: Throwable) { true }

    /**
     * What a chapter not read to its end left: where the windows done begin in the text, and the guesses (kept by
     * paragraph). Until 1.56 the windows were kept by their number in the list of windows to read — a list the
     * rules decide (a line set to "not a line" leaves it): with as many windows as before, a window not read
     * passed for read, and its lines stayed the rules' for good. A file of 1.54–1.55: its guesses, no windows.
     */
    private fun readPart(f: File): Pair<Set<Int>, Map<Int, Guess>>? = try {
        if (!f.exists()) null else {
            val o = org.json.JSONObject(f.readText())
            val g = o.getJSONObject("g")
            val m = HashMap<Int, Guess>()
            for (k in g.keys()) { val a = g.getJSONArray(k); m[k.toInt()] = Guess(a.getString(0), a.getDouble(1).toFloat(), a.getInt(2)) }
            val b = o.optJSONArray("b")
            val at = if (b == null) emptySet() else (0 until b.length()).map { b.getInt(it) }.toSet()
            at to m
        }
    } catch (_: Exception) { null }

    /** The guesses a chapter half read has (they are kept by paragraph: right whatever its windows are now). */
    private fun readPartGuesses(f: File): Map<Int, Guess>? = try {
        if (!f.exists()) null else {
            val g = org.json.JSONObject(f.readText()).getJSONObject("g")
            val m = HashMap<Int, Guess>()
            for (k in g.keys()) { val a = g.getJSONArray(k); m[k.toInt()] = Guess(a.getString(0), a.getDouble(1).toFloat(), a.getInt(2)) }
            m
        }
    } catch (_: Exception) { null }

    private fun savePart(f: File, total: Int, doneAt: Collection<Int>, m: Map<Int, Guess>) {
        try {
            val g = org.json.JSONObject()
            for ((k, v) in m) g.put(k.toString(), org.json.JSONArray().put(v.speaker).put(v.prob.toDouble()).put(v.kind))
            val b = org.json.JSONArray()
            for (k in doneAt) b.put(k)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("w", total).put("b", b).put("g", g).toString())
            tmp.renameTo(f)
        } catch (e: Exception) { Log.w(TAG, "save part: ${e.message}") }
    }

    /** One chapter at a time, whoever asks (the reading, the chapter ahead, preparing the book). */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * The guesses for a chapter: from its file, or (with [compute]) worked out now and kept. Only windows
     * with a line the model may change are read (a line with no author words, or one the rules left).
     *
     * A chapter need not be read at once (1.54): every window read is kept, and a chapter left half read
     * (a call, [stop], [limit] windows asked for) gives what it has — the lines of its other windows stay
     * the rules'. Windows are read from the one at [from] (a place in the text: where the reading is) to
     * the end, then those before it. [patience]: how long to wait, ms, if the model is busy with another
     * chapter. [onStart]: the model is about to be loaded (there is work); [onWindow]: windows done, of all.
     * Without [compute]: the chapter's file, or what a chapter half read has; null — nothing.
     * [low]: the model's threads at a low priority (in the background, beside the voice engine; 1.56).
     * The wait for the model and a window being read stop as soon as [stop] says so (1.56: preparing the
     * book could not be stopped while the model read a chapter — "play" waited for it, a minute or more).
     */
    fun guesses(c: Context, scope: String, text: String, lines: List<RoleWindows.Line>, names: Collection<String>,
                aliases: Map<String, String>, compute: Boolean, threads: Int, stop: () -> Boolean = { false },
                from: Int = 0, limit: Int = Int.MAX_VALUE, patience: Long = Long.MAX_VALUE,
                onStart: () -> Unit = {}, onWindow: (Int, Int) -> Unit = { _, _ -> }, low: Boolean = true): Map<Int, Guess>? {
        if (scope.isEmpty() || !enabled(c)) return null
        val f = cacheFile(c, scope, text)
        readCache(f)?.let { return it }
        val part = File(f.path + ".part")
        if (!compute) return readPartGuesses(part)     // (no windows built: asked at every start and plan)
        if (aborting) return null
        val halt = { stop() || aborting || BookPrep.inCall(c) }
        val t0 = System.currentTimeMillis()
        val ws = RoleWindows.build(text, lines, names, aliases).filter { w -> w.targets.any { it.hard || it.rules.isEmpty() } }
        if (ws.isEmpty()) { save(f, emptyMap()); return emptyMap() }
        // the model busy with another chapter: waited for no longer than [patience], and not once [stop] says so
        var mine = lock.tryLock()
        while (!mine && patience > 0 && !halt() && System.currentTimeMillis() - t0 < patience) {
            mine = try { lock.tryLock(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        }
        if (!mine) {
            Log.i(TAG, "busy with another chapter")
            return readPart(part)?.second
        }
        try {
            // the other one (the reading, preparing the book) read this chapter while we waited
            readCache(f)?.let { return it }
            val all = HashMap<Int, Guess>()
            val done = HashSet<Int>()
            readPart(part)?.let { (at, g) -> ws.forEachIndexed { k, w -> if (w.base in at) done += k }; all.putAll(g) }
            val first = ws.indexOfFirst { w -> w.targets.any { w.base + it.span[1] > from } }.coerceAtLeast(0)
            val order = (first until ws.size) + (0 until first)
            val todo = (if (limit < ws.size) order.take(limit) else order).filter { it !in done }
            val had = done.size
            val tid = android.os.Process.myTid()
            val pri0 = try { android.os.Process.getThreadPriority(tid) } catch (_: Throwable) { 0 }
            if (todo.isNotEmpty()) {
                if (halt()) return all.takeIf { it.isNotEmpty() }
                if (!room(c)) {
                    Log.i(TAG, "little free memory: the chapter is read by the rules")
                    BookSession.log(c, "модель ролей: мало свободной памяти — глава по правилам")
                    return all.takeIf { it.isNotEmpty() }
                }
                onStart()
                // the model's threads are made by load and take this thread's priority: in the background a low
                // one — the reading's pool threads have the audio priority, and the model got it too (1.56)
                try { android.os.Process.setThreadPriority(if (low) android.os.Process.THREAD_PRIORITY_BACKGROUND else 0) } catch (_: Throwable) { }
                if (!load(c, threads)) { try { android.os.Process.setThreadPriority(pri0) } catch (_: Throwable) { }; return all.takeIf { it.isNotEmpty() } }
                try {
                    for (k in todo) {
                        if (halt()) break
                        all.putAll(window(ws[k], halt))
                        done += k
                        if (done.size < ws.size) savePart(part, ws.size, done.map { ws[it].base }, all)
                        onWindow(done.size, ws.size)
                    }
                } catch (_: Stopped) {
                    Log.i(TAG, "stopped midway (windows ${done.size} of ${ws.size})")
                } catch (t: Throwable) {
                    Log.w(TAG, "failed: ${t.message}")
                    BookSession.log(c, "модель ролей: ошибка (окон ${done.size} из ${ws.size}) — ${t.message ?: t.javaClass.simpleName}")
                    return all.takeIf { it.isNotEmpty() }
                } finally {
                    unload()     // ~300 MB: not kept while the book is read
                    try { android.os.Process.setThreadPriority(pri0) } catch (_: Throwable) { }
                }
            }
            val sec = (System.currentTimeMillis() - t0) / 1000
            if (done.size < ws.size) {
                if (done.size > had) {
                    Log.i(TAG, "chapter: ${done.size} of ${ws.size} windows so far (+${done.size - had} in $sec s, $threads threads)")
                    BookSession.log(c, "модель ролей: разобрано окон ${done.size} из ${ws.size} (+${done.size - had} за $sec с, потоков $threads)")
                }
                return all
            }
            save(f, all)
            part.delete()
            Log.i(TAG, "chapter: ${ws.size} windows (+${done.size - had}), ${all.size} lines in $sec s, $threads threads")
            BookSession.log(c, "модель ролей: глава разобрана — окон ${ws.size} (+${done.size - had} за $sec с, потоков $threads), реплик ${all.size}")
            return all
        } finally {
            lock.unlock()
        }
    }

    private fun save(f: File, m: Map<Int, Guess>) {
        try {
            val o = org.json.JSONObject()
            for ((k, g) in m) o.put(k.toString(), org.json.JSONArray().put(g.speaker).put(g.prob.toDouble()).put(g.kind))
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (e: Exception) { Log.w(TAG, "save: ${e.message}") }
    }

    /**
     * The rules' result with the model's sure guesses put in: the lines' speakers (and their voices'
     * sex) change; a character the rules did not have in this chapter joins its list.
     * [genderOf]: a name's sex (the whole book's roster first). [aliases]: other name → name, as they are
     * now — two characters made one in the Characters window after the chapter was worked out stay one.
     * [overrides]: the user's own "this line is said by …" (SpeakerOverrides) — the model never changes
     * those (until 1.54 it did: a line set by hand, or set to "unknown", could get the model's speaker).
     */
    fun improve(r: DialogueAnalyzer2.Result, text: String, lines: List<RoleWindows.Line>, guesses: Map<Int, Guess>,
                aliases: Map<String, String> = emptyMap(), overrides: Map<String, String> = emptyMap(),
                genderOf: (String) -> DialogueAnalyzer2.Role?): DialogueAnalyzer2.Result {
        if (guesses.isEmpty()) return r
        fun resolve(n: String): String { var c = n; repeat(8) { c = aliases[c] ?: return c }; return c }
        val startToPi = HashMap<Int, Int>()
        var o = 0
        text.split('\n').forEachIndexed { i, p -> startToPi[o] = i; o += p.length + 1 }
        val spans = r.spans.toMutableList()
        val chars = r.characters.associateBy { it.name }.toMutableMap()
        var changed = 0
        for (l in lines) {
            val pi = startToPi[l.start] ?: continue
            val g0 = guesses[pi] ?: continue
            if (overrides.isNotEmpty() && l.speech.any { overrides[DialogueAnalyzer2.speechKey(text.substring(it[0], it[1]))] != null }) continue
            val g = resolve(g0.speaker).let { if (it == g0.speaker) g0 else Guess(it, g0.prob, g0.kind) }
            if (l.quoted || g.speaker.equals(l.rules, ignoreCase = true)) continue
            val ok = if (l.rules.isEmpty()) g.prob >= FILL else g.prob >= (if (l.kind == 0) HARD_OVER else EXPL_OVER)
            if (!ok) continue
            val role = genderOf(g.speaker) ?: chars[g.speaker]?.role ?: DialogueAnalyzer2.Role.SPEECH
            // the rules' pieces of this line
            val mine = spans.indices.filter { i ->
                spans[i].role != DialogueAnalyzer2.Role.NARRATOR && l.speech.any { spans[i].start >= it[0] && spans[i].start < it[1] }
            }
            if (mine.isEmpty()) continue
            // a paragraph the rules gave to two speakers (a line and the answer to it): not made one (1.56)
            if (mine.mapNotNull { spans[it].speaker }.distinct().size > 1) continue
            // The sex the line's author words give («спросила она», «ответил Пётр» — their first past-tense verb)
            // or, without them, the line itself («я пришла»): not gone against. The model can only name someone
            // named in the window — «незнакомая женщина» got a man's name and voice (1.56). A sex the rules took
            // from the turns of a talk is not held to: there the model is the surer one (94% against 68%).
            // (the remark is the author words after the speech — «— …, — окликнул он»; before it only an introduction,
            // «Она сказала:». 1.56 took the first author words of the paragraph: «…Лэнгдона осенила мысль. — …, —
            // окликнул он» gave a woman, and the model was not let mend the rules' «Кэтрин», 1.57)
            val firstSpeech = l.speech.firstOrNull()?.get(0) ?: 0
            val remark = l.authors.firstOrNull { it[0] >= firstSpeech }
                ?: l.authors.firstOrNull { it[1] <= firstSpeech && text.substring(it[0], it[1]).trimEnd(' ', '—', '–', '-').endsWith(":") }
            val sex = remark?.let { DialogueAnalyzer2.remarkSex(text.substring(it[0], it[1])) }
                ?: DialogueAnalyzer2.selfSex(l.speech.joinToString(" ") { text.substring(it[0], it[1]) })
            val known = role == DialogueAnalyzer2.Role.MALE || role == DialogueAnalyzer2.Role.FEMALE
            if (sex != null && known && role != sex) continue
            val newRole = if (sex != null && !known) sex else role
            for (i in mine) spans[i] = spans[i].copy(speaker = g.speaker, role = newRole)
            if (g.speaker !in chars) chars[g.speaker] = DialogueAnalyzer2.Character(g.speaker, newRole, 0, 1f, emptyList())
            changed++
        }
        if (changed == 0) return r
        Log.i(TAG, "lines given by the model: $changed of ${guesses.size}")
        return DialogueAnalyzer2.Result(spans, chars.values.toList(), r.aliases)
    }
}

/** Glue for the reading (PlaybackService.voicePlan). */
object RoleModelGlue {
    /** The rules' lines with their (merged) speaker: what RoleWindows needs. */
    fun linesOf(r: DialogueAnalyzer2.Result, probes: List<DialogueAnalyzer2.Probe>, aliases: Map<String, String>): List<RoleWindows.Line> {
        fun resolve(n: String): String { var c = n; repeat(8) { c = aliases[c] ?: return c }; return c }
        val at = HashMap<Int, String>()
        for (s in r.spans) if (s.role != DialogueAnalyzer2.Role.NARRATOR) at[s.start] = s.speaker ?: ""
        return probes.map { p ->
            RoleWindows.Line(p.ps, p.pe, p.kind, p.quoted, p.speech, p.authors,
                if (p.speech.isEmpty()) "" else resolve(at[p.speech[0][0]] ?: ""))
        }
    }

    /**
     * A chapter's roles with the model's sure guesses, if the model is on and this chapter is worked out
     * (with [threads] > 0: worked out now if it is not — the next chapter while this one is read, the
     * book's chapters while it is prepared).
     */
    /**
     * A name's case form taken for a person of its own («Лэнгдона», «Майку», «Базаровым») made one with the
     * name: the rules of a chapter made such a «person» of a name in another case, and by its ending it got a
     * woman's voice («Лэнгдона»), and the model a candidate of its own (1.57). [r] with them merged, and the
     * aliases with them.
     */
    private fun merged(r: DialogueAnalyzer2.Result, roster: BookRoster.Roster?, aliases: Map<String, String>):
            Pair<DialogueAnalyzer2.Result, Map<String, String>> {
        val names = ((roster?.ranking ?: emptyList()) + r.characters.map { it.name }).distinct()
        val forms = RoleWindows.caseForms(names).filterKeys { it !in aliases }
        if (forms.isEmpty()) return r to aliases
        val roleOf = HashMap<String, DialogueAnalyzer2.Role>()
        for (ch in r.characters) if (ch.role == DialogueAnalyzer2.Role.MALE || ch.role == DialogueAnalyzer2.Role.FEMALE) roleOf[ch.name] = ch.role
        roster?.genders?.forEach { (k, v) -> roleOf[k] = v }
        val m = DialogueAnalyzer2.applyAliases(r, forms)
        val spans = m.spans.mapIndexed { i, s ->
            val was = r.spans[i].speaker
            val base = if (was != null) forms[was] else null
            val role = if (base != null) roleOf[base] else null
            if (role != null && s.role != DialogueAnalyzer2.Role.NARRATOR) s.copy(role = role) else s
        }
        android.util.Log.i("RoleModel", "case forms made one: $forms")
        return DialogueAnalyzer2.Result(spans, m.characters, r.aliases + forms) to (forms + aliases)
    }

    fun improve(c: android.content.Context, scope: String, text: String, r0: DialogueAnalyzer2.Result,
                probes: List<DialogueAnalyzer2.Probe>, roster: BookRoster.Roster?, aliases0: Map<String, String>,
                threads: Int, stop: () -> Boolean): DialogueAnalyzer2.Result {
        val (r, aliases) = try { merged(r0, roster, aliases0) } catch (t: Throwable) { r0 to aliases0 }
        if (!RoleModel.enabled(c) || scope.isEmpty()) return r
        return try {
            val lines = linesOf(r, probes, aliases)
            val names = ((roster?.ranking ?: emptyList()) + r.characters.map { it.name }).distinct().filter { it !in aliases }
            val g = RoleModel.guesses(c, scope, text, lines, names, aliases, threads > 0, maxOf(1, threads), stop) ?: return r
            RoleModel.improve(r, text, lines, g, aliases, SpeakerOverrides.all(c, scope)) { n -> roster?.genders?.get(n) }
        } catch (t: Throwable) {
            android.util.Log.w("RoleModel", "not used: ${t.message}"); r
        }
    }

    /**
     * The role model reads a chapter (or goes on with it) for the book being read — see [RoleModel.guesses]:
     * from the window at [from], [limit] windows at most.
     */
    fun read(c: android.content.Context, scope: String, text: String, r0: DialogueAnalyzer2.Result,
             probes: List<DialogueAnalyzer2.Probe>, roster: BookRoster.Roster?, aliases0: Map<String, String>,
             threads: Int, from: Int, limit: Int, patience: Long, stop: () -> Boolean,
             onStart: () -> Unit = {}, onWindow: (Int, Int) -> Unit = { _, _ -> }, low: Boolean = true) {
        if (!RoleModel.enabled(c) || scope.isEmpty()) return
        try {
            val (r, aliases) = merged(r0, roster, aliases0)
            val lines = linesOf(r, probes, aliases)
            val names = ((roster?.ranking ?: emptyList()) + r.characters.map { it.name }).distinct().filter { it !in aliases }
            RoleModel.guesses(c, scope, text, lines, names, aliases, true, maxOf(1, threads), stop, from, limit, patience, onStart, onWindow, low)
        } catch (t: Throwable) {
            android.util.Log.w("RoleModel", "not read: ${t.message}")
        }
    }
}
