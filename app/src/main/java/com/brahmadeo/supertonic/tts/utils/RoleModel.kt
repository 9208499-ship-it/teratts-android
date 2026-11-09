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
 */
object RoleModel {
    private const val TAG = "RoleModel"
    private const val PREFS = "TeraRoleModel"
    private const val RELEASE = "role-model-m8b"
    private const val BASE = "https://github.com/9208499-ship-it/teratts-android/releases/download/$RELEASE/"
    /** Bumped with the model or the rules it was trained on top of: what was worked out before is made again. */
    private const val VERSION = "m8b-149"
    val FILES = listOf("roles_enc_int8.onnx", "roles_head.onnx", "tokenizer.json", "roles_meta.json")
    private const val MAXTOK = 2048
    private const val NB = 12

    // the policy (measured on 197 hand-checked lines and «Алиса»): a line with no author words — the
    // model from 0.6; one with them — the rules, the model only from 0.9; the rules silent — from 0.5
    private const val HARD_OVER = 0.6f
    private const val EXPL_OVER = 0.9f
    private const val FILL = 0.5f

    fun dir(c: Context) = File(c.filesDir, "role_model")
    fun installed(c: Context) = FILES.all { File(dir(c), it).length() > 0 }
    fun size(c: Context): Long = FILES.sumOf { File(dir(c), it).length() }
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun wanted(c: Context) = p(c).getBoolean("on", true)
    fun setWanted(c: Context, on: Boolean) = p(c).edit().putBoolean("on", on).apply()
    fun enabled(c: Context) = wanted(c) && installed(c)

    /** Download progress 0…1, or null when no download is going on. */
    val progress = androidx.compose.runtime.mutableStateOf<Float?>(null)

    /** All the files from the release, one after another (.part until whole). Throws on failure. */
    suspend fun download(c: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val d = dir(c).apply { mkdirs() }
        // the weights are nearly all of it: their share of the bar
        val share = mapOf("roles_enc_int8.onnx" to 0.97f, "roles_head.onnx" to 0.02f, "tokenizer.json" to 0.009f, "roles_meta.json" to 0.001f)
        var before = 0f
        progress.value = 0f
        try {
            for (name in FILES) {
                val f = File(d, name)
                val w = share[name] ?: 0f
                if (f.length() > 0) { before += w; progress.value = before; continue }
                val part = File(d, "$name.part")
                val con = URL(BASE + name).openConnection() as HttpURLConnection
                con.setRequestProperty("User-Agent", "TeraTTS")
                con.connectTimeout = 20000; con.readTimeout = 30000
                con.instanceFollowRedirects = true
                if (con.responseCode != 200) throw IllegalStateException("$name: HTTP ${con.responseCode}")
                val total = con.contentLengthLong
                con.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress.value = before + w * done / total
                        }
                    }
                }
                if (total > 0 && part.length() != total) throw IllegalStateException("$name: скачался не целиком")
                if (!part.renameTo(f)) throw IllegalStateException("$name: не удалось сохранить")
                before += w
            }
        } finally {
            progress.value = null
        }
    }

    fun delete(c: Context) {
        unload()
        dir(c).listFiles()?.forEach { it.delete() }
        File(c.filesDir, "role_model_cache").deleteRecursively()
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
            tok = tokenizerOf(File(d, "tokenizer.json"))
            val e = OrtEnvironment.getEnvironment()
            env = e
            fun session(f: String): OrtSession = OrtSession.SessionOptions().use { so ->
                so.setIntraOpNumThreads(threads)
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                e.createSession(File(d, f).absolutePath, so)
            }
            enc = session(meta.optString("enc", "roles_enc_int8.onnx"))
            head = session(meta.optString("head", "roles_head.onnx"))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            unload()
            false
        }
    }

    @Synchronized
    fun unload() {
        try { enc?.close() } catch (_: Throwable) { }
        try { head?.close() } catch (_: Throwable) { }
        enc = null; head = null; tok = null
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

    /** One window: the guesses for its lines (paragraph number → guess). */
    private fun window(w: RoleWindows.Window): Map<Int, Guess> {
        val t = tok ?: return emptyMap()
        val e = env ?: return emptyMap()
        val inp = inputs(t, w) ?: return emptyMap()
        val ids = inp.ids; val qs = inp.qs; val msp = inp.ms; val kinds = inp.kinds; val dist = inp.dist
        val q = inp.q; val mm = inp.m; val ents = inp.ents
        val scores: Array<FloatArray> = OnnxTensor.createTensor(e, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { idT ->
            enc!!.run(mapOf("input_ids" to idT)).use { r1 ->
                val hidden = r1.get(0) as OnnxTensor
                OnnxTensor.createTensor(e, LongBuffer.wrap(qs), longArrayOf(q.toLong(), 2)).use { qT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(msp), longArrayOf(mm.toLong(), 2)).use { mT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(kinds), longArrayOf(q.toLong(), mm.toLong())).use { kT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(dist), longArrayOf(q.toLong(), mm.toLong())).use { dT ->
                    head!!.run(mapOf("hidden" to hidden, "qspans" to qT, "mspans" to mT, "kinds" to kT, "dist" to dT)).use { r2 ->
                        @Suppress("UNCHECKED_CAST")
                        (r2.get(0).value as Array<FloatArray>)
                    }
                } } } }
            }
        }
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
        File(c.filesDir, "role_model_cache/" + Integer.toHexString(scope.hashCode()) + "/" + VERSION + "_" + BookPrep.textKey(text) + ".json")

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

    /**
     * The guesses for a chapter: from its file, or (with [compute]) worked out now and kept. Only windows
     * with a line the model may change are read (a line with no author words, or one the rules left).
     * Stops (nothing kept) on a phone call or when [stop] says so.
     */
    fun guesses(c: Context, scope: String, text: String, lines: List<RoleWindows.Line>, names: Collection<String>,
                aliases: Map<String, String>, compute: Boolean, threads: Int, stop: () -> Boolean = { false }): Map<Int, Guess>? {
        if (scope.isEmpty() || !enabled(c)) return null
        val f = cacheFile(c, scope, text)
        readCache(f)?.let { return it }
        if (!compute) return null
        val t0 = System.currentTimeMillis()
        val ws = RoleWindows.build(text, lines, names, aliases).filter { w -> w.targets.any { it.hard || it.rules.isEmpty() } }
        if (ws.isEmpty()) { save(f, emptyMap()); return emptyMap() }
        synchronized(this) {
            if (!load(c, threads)) return null
            val all = HashMap<Int, Guess>()
            try {
                for (w in ws) {
                    if (stop() || BookPrep.inCall(c)) { Log.i(TAG, "stopped (${all.size} lines so far)"); return null }
                    all.putAll(window(w))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed: ${t.message}"); return null
            } finally {
                unload()     // ~300 MB: not kept while the book is read
            }
            save(f, all)
            Log.i(TAG, "chapter: ${ws.size} windows, ${all.size} lines in ${(System.currentTimeMillis() - t0) / 1000} s")
            return all
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
     * [genderOf]: a name's sex (the whole book's roster first).
     */
    fun improve(r: DialogueAnalyzer2.Result, text: String, lines: List<RoleWindows.Line>, guesses: Map<Int, Guess>,
                genderOf: (String) -> DialogueAnalyzer2.Role?): DialogueAnalyzer2.Result {
        if (guesses.isEmpty()) return r
        val startToPi = HashMap<Int, Int>()
        var o = 0
        text.split('\n').forEachIndexed { i, p -> startToPi[o] = i; o += p.length + 1 }
        val spans = r.spans.toMutableList()
        val chars = r.characters.associateBy { it.name }.toMutableMap()
        var changed = 0
        for (l in lines) {
            val pi = startToPi[l.start] ?: continue
            val g = guesses[pi] ?: continue
            if (l.quoted || g.speaker.equals(l.rules, ignoreCase = true)) continue
            val ok = if (l.rules.isEmpty()) g.prob >= FILL else g.prob >= (if (l.kind == 0) HARD_OVER else EXPL_OVER)
            if (!ok) continue
            val role = genderOf(g.speaker) ?: chars[g.speaker]?.role ?: DialogueAnalyzer2.Role.SPEECH
            for (i in spans.indices) {
                val s = spans[i]
                if (s.role == DialogueAnalyzer2.Role.NARRATOR) continue
                if (l.speech.none { s.start >= it[0] && s.start < it[1] }) continue
                spans[i] = s.copy(speaker = g.speaker, role = role)
            }
            if (g.speaker !in chars) chars[g.speaker] = DialogueAnalyzer2.Character(g.speaker, role, 0, 1f, emptyList())
            changed++
        }
        if (changed == 0) return r
        Log.i(TAG, "lines given by the model: $changed")
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
    fun improve(c: android.content.Context, scope: String, text: String, r: DialogueAnalyzer2.Result,
                probes: List<DialogueAnalyzer2.Probe>, roster: BookRoster.Roster?, aliases: Map<String, String>,
                threads: Int, stop: () -> Boolean): DialogueAnalyzer2.Result {
        if (!RoleModel.enabled(c) || scope.isEmpty()) return r
        return try {
            val lines = linesOf(r, probes, aliases)
            val names = ((roster?.ranking ?: emptyList()) + r.characters.map { it.name }).distinct()
            val g = RoleModel.guesses(c, scope, text, lines, names, aliases, threads > 0, maxOf(1, threads), stop) ?: return r
            RoleModel.improve(r, text, lines, g) { n -> roster?.genders?.get(n) }
        } catch (t: Throwable) {
            android.util.Log.w("RoleModel", "not used: ${t.message}"); r
        }
    }
}
