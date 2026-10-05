package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.util.regex.Pattern

/**
 * Bulk imported accent / pronunciation dictionary.
 *
 * Distinct from [LexiconManager]:
 * - LexiconManager: small, hand-curated rules (whole-word or regex).
 *   Iterates every rule against every text. Fine for <100 entries.
 * - AccentDictionaryManager: large word-indexed map (tens of thousands of
 *   entries). One HashMap lookup per word, so cost scales with text
 *   length, not with dictionary size.
 *
 * User lexicon always wins (applied first; if it rewrites a word, the
 * accent dictionary won't see the original form).
 *
 * Expected JSON shape — a flat object, key = original word, value =
 * pronunciation with stress marks (Unicode U+0301 after the stressed vowel):
 *
 * ```json
 * { "замок": "замо́к", "Москва": "Москва́" }
 * ```
 *
 * Lookups are case-insensitive by default; original casing is restored on
 * replacement so "Замок" -> "Замо́к".
 */
object AccentDictionaryManager {
    private const val TAG = "AccentDict"
    // Two on-disk formats. Whichever file is present at load time is used; if
    // both exist we prefer .sacc because mmap is dramatically cheaper than
    // parsing JSON into a HashMap. Switching format means clear() + download
    // again (the in-app menu has separate Text / Binary download options).
    private const val FILE_NAME_JSON = "accent_dictionary.json"
    private const val FILE_NAME_SACC = "accent_dictionary.sacc"
    // 250 MB cap — the full Russian dictionary is ~165 MB. The HashMap that
    // backs it takes ~500 MB of heap; that's fine on phones with ≥ 4 GB of
    // RAM but would OOM a budget device. Users who want the smaller dict can
    // still import russian_accents.json (36 MB) via the file picker.
    private const val MAX_FILE_BYTES = 250L * 1024 * 1024

    enum class DictFormat { TEXT, BINARY }

    data class PrebuiltDict(
        val id: String,
        val displayName: String,
        val format: DictFormat,
        val subtitle: String,
        val sizeBytes: Long,
        val entries: Int,
        val url: String
    )

    /**
     * Pre-built dictionaries published in the davnozdu/supertonic-dictionaries
     * GitHub repository (separate from the app for independent versioning).
     * Two parallel sets per size — .json (heap-resident) and .sacc (mmap'd).
     * The Lexicon screen has two menu items so the user picks format first,
     * then size.
     */
    private const val DICT_BASE_URL =
        "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.1"

    /** Built by silero-stress (export_silero_dict.py); homographs are left to the context resolver. */
    const val SILERO_DICT_URL =
        "https://github.com/9208499-ship-it/teratts-android/releases/download/models-v1/silero_stress_ru.sacc"
    const val SILERO_DICT_NAME = "Silero (рекомендуется)"
    const val SILERO_DICT_SIZE_MB = 172

    /** True if some accent dictionary is installed. */
    fun isInstalled(context: Context): Boolean =
        File(context.filesDir, "accent_dictionary.sacc").exists() ||
            File(context.filesDir, "accent_dictionary.json").exists() ||
            getMetadata(context) != null

    private val PREBUILT_DICTS: Map<String, List<PrebuiltDict>> = mapOf(
        "ru" to listOf(
            // ------------- TeraTTS: silero-stress dictionary (recommended) -------------
            PrebuiltDict(
                id = "ru-silero-bin",
                displayName = SILERO_DICT_NAME,
                format = DictFormat.BINARY,
                subtitle = "172 MB · 3.27M entries · ~10-20 MB RAM · stress by the silero-stress network; no homographs — the context resolver handles them",
                sizeBytes = 172L * 1024 * 1024,
                entries = 3_274_981,
                url = SILERO_DICT_URL
            ),
            // ------------- Binary (.sacc, mmap, recommended) -------------
            PrebuiltDict(
                id = "ru-full-bin",
                displayName = "Full (binary)",
                format = DictFormat.BINARY,
                subtitle = "171 MB · 3.26M entries · ~10-20 MB RAM · names, homographs, ё (yoficator-augmented)",
                sizeBytes = 171L * 1024 * 1024,
                entries = 3_263_148,
                url = "$DICT_BASE_URL/russian_accents_full.sacc"
            ),
            PrebuiltDict(
                id = "ru-standard-bin",
                displayName = "Standard (binary)",
                format = DictFormat.BINARY,
                subtitle = "39 MB · 983K entries · ~5-10 MB RAM · words ≤ 9 chars, no homographs, ё (yoficator-augmented)",
                sizeBytes = 39L * 1024 * 1024,
                entries = 982_598,
                url = "$DICT_BASE_URL/russian_accents_max9.sacc"
            ),
            PrebuiltDict(
                id = "ru-compact-bin",
                displayName = "Compact (binary)",
                format = DictFormat.BINARY,
                subtitle = "23 MB · 628K entries · ~3-7 MB RAM · words ≤ 8 chars, ё (yoficator-augmented)",
                sizeBytes = 23L * 1024 * 1024,
                entries = 628_242,
                url = "$DICT_BASE_URL/russian_accents_max8.sacc"
            ),
            // ------------- Text (.json, HashMap, hand-editable) -------------
            PrebuiltDict(
                id = "ru-full-txt",
                displayName = "Full (text)",
                format = DictFormat.TEXT,
                subtitle = "165 MB · 3.26M entries · ~390 MB RAM · names, homographs, ё (yoficator-augmented)",
                sizeBytes = 165L * 1024 * 1024,
                entries = 3_263_148,
                url = "$DICT_BASE_URL/russian_accents_full.json"
            ),
            PrebuiltDict(
                id = "ru-standard-txt",
                displayName = "Standard (text)",
                format = DictFormat.TEXT,
                subtitle = "37 MB · 983K entries · ~150 MB RAM · words ≤ 9 chars, no homographs, ё (yoficator-augmented)",
                sizeBytes = 37L * 1024 * 1024,
                entries = 982_598,
                url = "$DICT_BASE_URL/russian_accents_max9.json"
            ),
            PrebuiltDict(
                id = "ru-compact-txt",
                displayName = "Compact (text)",
                format = DictFormat.TEXT,
                subtitle = "22 MB · 628K entries · ~85 MB RAM · words ≤ 8 chars, ё (yoficator-augmented)",
                sizeBytes = 22L * 1024 * 1024,
                entries = 628_242,
                url = "$DICT_BASE_URL/russian_accents_max8.json"
            )
        )
    )

    fun hasPrebuiltFor(lang: String): Boolean = PREBUILT_DICTS.containsKey(lang.lowercase().substringBefore('-'))

    fun prebuiltOptionsFor(lang: String): List<PrebuiltDict> {
        return PREBUILT_DICTS[lang.lowercase().substringBefore('-')] ?: emptyList()
    }

    fun prebuiltOptionsFor(lang: String, format: DictFormat): List<PrebuiltDict> {
        return prebuiltOptionsFor(lang).filter { it.format == format }
    }

    data class Metadata(val source: String, val entries: Int, val loadedAtMs: Long, val sizeBytes: Long)

    // Two parallel backends. Whichever is non-null at lookup time wins; if
    // the user installed a .sacc binary it takes precedence and `entries`
    // stays empty. The JSON path is kept for compatibility with hand-edited
    // dictionaries and the existing "Import from file" flow.
    @Volatile private var entries: Map<String, String> = emptyMap()
    @Volatile private var binaryDict: BinaryAccentDictionary? = null
    @Volatile private var isLoaded = false
    @Volatile private var isLoading = false
    // Bumped every time a load is requested. The background thread checks its
    // captured value against this on completion — if the user cleared or
    // imported a new dict mid-parse, the stale result is dropped.
    @Volatile private var loadGeneration = 0
    private val loadLock = Object()
    // When ON, load() blocks the calling thread until the dictionary is
    // fully parsed. Used by people who can't tolerate the first sentence
    // after a cold start going un-stressed (lazy default).
    @Volatile private var syncLoadEnabled = false

    // Match a word as "Unicode letters + any combining marks attached to them".
    // The \p{M} part is the fix for the double-stress bug: if a previous step
    // (user Lexicon) already inserted U+0301 into "Москва" -> "Москва́", a
    // bare \p{L}+ would match only "Москва", we'd replace it from the
    // dictionary with another stressed form, and appendTail would re-emit
    // the orphan U+0301 — producing "Москва́́" with two combining accents.
    // Including \p{M} makes the matcher consume the existing diacritic, so
    // its lowercased form misses the (unstressed-key) dictionary and the
    // word is preserved as the user marked it.
    private val wordPattern: Pattern = Pattern.compile("[\\p{L}\\p{M}]+")
    private const val META_PREFS = "AccentDictMeta"
    private const val META_KEY_SOURCE = "source"
    private const val META_KEY_ENTRIES = "entries"
    private const val META_KEY_LOADED_AT = "loaded_at"
    private const val META_KEY_SIZE_BYTES = "size_bytes"
    private const val SYNC_LOAD_PREFS_KEY = "accent_sync_load_enabled"

    /**
     * Kick off loading the dictionary into memory.
     *
     * Returns immediately. The actual parsing runs on a background thread —
     * for the 165 MB Full dictionary this is the ~5-10 s of JSON streaming
     * we used to do synchronously inside `Service.onCreate`, blocking the
     * first synthesis. Now `apply()` is just a no-op until [isReady] flips,
     * which means the first sentence after a cold start may render without
     * stress marks, but the second one onwards is properly stressed.
     *
     * Safe to call multiple times: a parse already in progress isn't
     * restarted, and calls after a successful load are a fast-path no-op.
     */
    fun load(context: Context) {
        if (isLoaded) return
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        syncLoadEnabled = prefs.getBoolean(SYNC_LOAD_PREFS_KEY, false)

        val myGen: Int
        synchronized(loadLock) {
            if (isLoaded || isLoading) return
            isLoading = true
            myGen = ++loadGeneration
        }

        val appContext = context.applicationContext
        // Shared loader body — runs either on the calling thread (sync mode)
        // or on the AccentDict-Loader background thread (lazy mode, default).
        val loader = Runnable {
            try {
                val saccFile = File(appContext.filesDir, FILE_NAME_SACC)
                val jsonFile = File(appContext.filesDir, FILE_NAME_JSON)
                // .sacc wins over .json when both exist. mmap is free and
                // there's no point parsing JSON if the binary is right there.
                var newBinary: BinaryAccentDictionary? = null
                var newEntries: Map<String, String> = emptyMap()
                when {
                    saccFile.exists() && BinaryAccentDictionary.looksLikeSacc(saccFile) -> {
                        newBinary = BinaryAccentDictionary.open(saccFile)
                        if (newBinary == null) {
                            Log.w(TAG, ".sacc present but failed to open, falling back to .json")
                        }
                    }
                }
                if (newBinary == null && jsonFile.exists()) {
                    FileInputStream(jsonFile).use { fis ->
                        newEntries = parseJsonStream(fis, jsonFile.length())
                    }
                }
                synchronized(loadLock) {
                    // Only commit the result if no one (clear / import /
                    // download) preempted us by bumping the generation.
                    if (loadGeneration == myGen) {
                        // Close any previously-mapped file so we don't leak fds.
                        binaryDict?.close()
                        binaryDict = newBinary
                        entries = newEntries
                        isLoaded = true
                    } else {
                        // Stale result: discard the mmap to release the fd.
                        newBinary?.close()
                    }
                }
                val effectiveCount = newBinary?.entryCount ?: newEntries.size
                if (effectiveCount > 0) {
                    val mode = if (newBinary != null) "binary mmap" else "JSON HashMap"
                    Log.i(TAG, "Loaded $effectiveCount accent entries ($mode)")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load accent dictionary", e)
                synchronized(loadLock) {
                    if (loadGeneration == myGen) {
                        binaryDict?.close()
                        binaryDict = null
                        entries = emptyMap()
                        isLoaded = true
                    }
                }
            } finally {
                synchronized(loadLock) {
                    if (loadGeneration == myGen) {
                        isLoading = false
                    }
                }
            }
        }

        if (syncLoadEnabled) {
            // Block the caller. Used by users who can't tolerate the first
            // sentence after a cold start going un-stressed.
            loader.run()
        } else {
            Thread(loader, "AccentDict-Loader").apply {
                // Lowered priority — the parse is heavy on weak phones and
                // we don't want to fight ORT initialisation.
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
            }.start()
        }
    }

    /**
     * Force a re-read from disk next time [load] is called. Used by the
     * "import / download" flows when they want to drop the cached entries
     * and re-parse the new file.
     */
    fun reload(context: Context) {
        synchronized(loadLock) {
            ++loadGeneration
            isLoaded = false
            isLoading = false
        }
        load(context)
    }

    fun isLoading(): Boolean = isLoading

    fun size(): Int = binaryDict?.entryCount ?: entries.size

    fun isReady(): Boolean = binaryDict != null || entries.isNotEmpty()

    fun apply(text: String, lang: String = ""): String {
        // Snapshot the backends once per call so concurrent reload doesn't
        // flip us mid-iteration.
        val bin = binaryDict
        val map = entries
        if (bin == null && map.isEmpty()) return text

        val matcher = wordPattern.matcher(text)
        val sb = StringBuffer()
        while (matcher.find()) {
            val original = matcher.group() ?: continue
            val lower = original.lowercase()
            // Look up in whichever backend is live. The .sacc reader takes
            // UTF-8 bytes; the JSON HashMap is keyed by the lowercased String.
            val dictHit = if (bin != null) {
                bin.lookup(lower.toByteArray(Charsets.UTF_8))
            } else {
                map[lower]
            } ?: continue
            val cased = applyCasing(original, dictHit)
            // appendReplacement treats $ and \ specially — quoteReplacement
            // does the escape in a single pass without two intermediate Strings.
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(cased))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    fun setSyncLoadEnabled(context: Context, enabled: Boolean) {
        syncLoadEnabled = enabled
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(SYNC_LOAD_PREFS_KEY, enabled)
            .apply()
    }

    fun isSyncLoadEnabled(): Boolean = syncLoadEnabled

    fun getMetadata(context: Context): Metadata? {
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val source = prefs.getString(META_KEY_SOURCE, null) ?: return null
        return Metadata(
            source = source,
            entries = prefs.getInt(META_KEY_ENTRIES, 0),
            loadedAtMs = prefs.getLong(META_KEY_LOADED_AT, 0L),
            sizeBytes = prefs.getLong(META_KEY_SIZE_BYTES, 0L)
        )
    }

    private fun writeMetadata(context: Context, source: String, entries: Int, sizeBytes: Long) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putString(META_KEY_SOURCE, source)
            .putInt(META_KEY_ENTRIES, entries)
            .putLong(META_KEY_LOADED_AT, System.currentTimeMillis())
            .putLong(META_KEY_SIZE_BYTES, sizeBytes)
            .apply()
    }

    private fun clearMetadata(context: Context) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .remove(META_KEY_SOURCE)
            .remove(META_KEY_ENTRIES)
            .remove(META_KEY_LOADED_AT)
            .remove(META_KEY_SIZE_BYTES)
            .apply()
    }

    /**
     * Imports a dictionary from a content:// URI selected by the user.
     *
     * Two-pass: stream the URI into a tmp file (we don't know its size from
     * content://, so this enforces the cap), then stream-parse the file.
     * Never materialises the whole JSON in memory.
     *
     * @return number of entries loaded (or a negative ImportError code on failure).
     */
    fun importFromUri(context: Context, uri: Uri): Int {
        synchronized(loadLock) {
            if (isDownloading) return ERR_BUSY
            isDownloading = true
        }
        val tmp = File(context.cacheDir, "accent_import.tmp")
        return try {
            val written = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    copyWithCap(input, out, MAX_FILE_BYTES)
                }
            } ?: return ERR_IO
            if (written < 0) {
                tmp.delete()
                return ERR_TOO_LARGE
            }
            installFromTmpAuto(context, tmp, "Imported from file")
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Import OOM", oom)
            tmp.delete()
            entries = emptyMap()
            ERR_OOM
        } catch (e: Throwable) {
            Log.e(TAG, "Import failed", e)
            tmp.delete()
            ERR_PARSE
        } finally {
            synchronized(loadLock) { isDownloading = false }
        }
    }

    /**
     * Detect the format of [tmp] from its magic bytes and route to the
     * appropriate installer. Returns the entry count on success, or an
     * ERR_* code on failure. The caller is responsible for *not* using
     * [tmp] after this returns — it's been moved into place or deleted.
     */
    private fun installFromTmpAuto(context: Context, tmp: File, sourceName: String): Int {
        return if (BinaryAccentDictionary.looksLikeSacc(tmp)) {
            installSaccFromTmp(context, tmp, sourceName)
        } else {
            val parsed = FileInputStream(tmp).use { parseJsonStream(it, tmp.length()) }
            if (parsed.isEmpty()) {
                tmp.delete()
                return 0
            }
            installFromTmp(context, tmp, parsed, sourceName)
            parsed.size
        }
    }

    fun clear(context: Context) {
        synchronized(loadLock) {
            // Invalidate any pending background load so it doesn't race and
            // restore the deleted dictionary into `entries`.
            ++loadGeneration
            isLoading = false
            binaryDict?.close()
            binaryDict = null
            entries = emptyMap()
            isLoaded = true
        }
        File(context.filesDir, FILE_NAME_JSON).delete()
        File(context.filesDir, FILE_NAME_SACC).delete()
        clearMetadata(context)
    }

    // Negative return codes from importFromUri / downloadPrebuilt so the UI
    // can show a specific reason instead of a generic "failed" toast.
    const val ERR_IO = -1
    const val ERR_OOM = -2
    const val ERR_PARSE = -3
    const val ERR_TOO_LARGE = -4
    const val ERR_NETWORK = -5
    const val ERR_BUSY = -6
    const val ERR_EMPTY = 0

    // Guards the cacheDir/accent_download.tmp file and the install path so a
    // config change (rotation, dark-mode flip) that nukes the modal progress
    // dialog can't accidentally let the user start a second concurrent
    // download — both writers stomping on the same tmp file would corrupt it.
    @Volatile private var isDownloading = false

    /**
     * Downloads and installs the pre-built dictionary for [lang] (currently only "ru").
     * Streams bytes to a temp file with progress callbacks so the UI can show a bar
     * instead of freezing for ~10 s on a 36 MB file. Reports byte counts; the caller
     * decides whether to translate them into a percentage.
     *
     * Returns the number of entries loaded, or a negative ERR_* code on failure.
     */
    fun downloadPrebuilt(
        context: Context,
        urlStr: String,
        sourceName: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Int {
        synchronized(loadLock) {
            if (isDownloading) return ERR_BUSY
            isDownloading = true
        }
        val tmp = File(context.cacheDir, "accent_download.tmp")
        try {
            val conn = URL(urlStr).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
            conn.getInputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var soFar = 0L
                    var lastReport = 0L
                    while (true) {
                        read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        soFar += read
                        // Throttle UI updates to every 200 KB to keep logcat sane.
                        if (soFar - lastReport >= 200 * 1024 || total > 0 && soFar >= total) {
                            onProgress(soFar, total)
                            lastReport = soFar
                        }
                    }
                    onProgress(soFar, soFar)
                }
            }
            if (tmp.length() > MAX_FILE_BYTES) {
                Log.w(TAG, "Downloaded dict ${tmp.length()} bytes exceeds cap")
                tmp.delete()
                return ERR_TOO_LARGE
            }
            onProgress(tmp.length(), tmp.length()) // signal "parsing now"
            // Branch on file format. .sacc is a near-instant mmap; JSON is a
            // stream-parse straight off disk (no readText, no JSONObject).
            val count = installFromTmpAuto(context, tmp, sourceName)
            return if (count == 0) ERR_EMPTY else count
        } catch (oom: OutOfMemoryError) {
            // Parsing the full 165 MB dict allocates ~390 MB for the HashMap
            // alone. With largeHeap=true that fits on 4 GB+ phones, but on
            // smaller devices it OOMs. Tell the user explicitly so they pick a
            // smaller dict instead of staring at a silent "failed" toast.
            Log.e(TAG, "downloadPrebuilt OOM from $urlStr", oom)
            tmp.delete()
            entries = emptyMap()
            return ERR_OOM
        } catch (e: java.io.IOException) {
            Log.e(TAG, "downloadPrebuilt network failure from $urlStr", e)
            tmp.delete()
            return ERR_NETWORK
        } catch (e: Throwable) {
            Log.e(TAG, "downloadPrebuilt failed from $urlStr", e)
            tmp.delete()
            return ERR_PARSE
        } finally {
            synchronized(loadLock) { isDownloading = false }
        }
    }

    /**
     * Move the validated JSON tmp file into place and swap the in-memory map.
     *
     * The big win over the old saveAndCache: we never re-serialise the map
     * via JSONObject.toString() (which would allocate a second 200+ MB
     * String on top of the already-loaded HashMap and OOM most phones).
     * The tmp file is *already* valid JSON — we just rename it.
     *
     * Any existing .sacc dictionary is dropped because the user explicitly
     * picked the JSON format this time. Same the other way around in
     * [installSaccFromTmp].
     */
    private fun installFromTmp(
        context: Context,
        tmp: File,
        parsed: Map<String, String>,
        sourceName: String
    ) {
        val target = File(context.filesDir, FILE_NAME_JSON)
        if (target.exists()) target.delete()
        val size = tmp.length()
        if (!tmp.renameTo(target)) {
            // Cross-device fallback (cacheDir and filesDir can be different
            // mounts on some OEMs); copy then drop the original.
            tmp.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            tmp.delete()
        }
        // Drop the other-format file so we don't have stale data on disk.
        File(context.filesDir, FILE_NAME_SACC).delete()
        synchronized(loadLock) {
            // Same race protection as clear(): the user just downloaded /
            // imported a fresh dict, so any older background load result
            // must not clobber it.
            ++loadGeneration
            isLoading = false
            binaryDict?.close()
            binaryDict = null
            entries = parsed
            isLoaded = true
        }
        val labelled = if (sourceName.contains("[text]") || sourceName.contains("(text)")) {
            sourceName
        } else {
            "[text] $sourceName"
        }
        writeMetadata(context, labelled, parsed.size, size)
    }

    /**
     * Move a verified `.sacc` tmp file into place and open it via mmap.
     * Mirrors [installFromTmp] but for the binary backend. The tmp must
     * have already been validated by [BinaryAccentDictionary.looksLikeSacc];
     * we open it after rename to make sure the actual mapped view is healthy.
     *
     * Returns the entry count on success, or an ERR_* code on failure.
     */
    private fun installSaccFromTmp(context: Context, tmp: File, sourceName: String): Int {
        val target = File(context.filesDir, FILE_NAME_SACC)
        if (target.exists()) target.delete()
        val size = tmp.length()
        if (!tmp.renameTo(target)) {
            tmp.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            tmp.delete()
        }
        val opened = BinaryAccentDictionary.open(target)
        if (opened == null) {
            // Roll back so we don't leave a broken file around that the next
            // load would also fail on. The user gets ERR_PARSE.
            target.delete()
            return ERR_PARSE
        }
        // Drop the other-format file so .sacc fully replaces .json.
        File(context.filesDir, FILE_NAME_JSON).delete()
        val count = opened.entryCount
        synchronized(loadLock) {
            ++loadGeneration
            isLoading = false
            binaryDict?.close()
            binaryDict = opened
            entries = emptyMap()
            isLoaded = true
        }
        // Prefix the displayed source with a [binary] tag so the banner shows
        // which backend is in use — important for users who import their own
        // file and otherwise only see "Imported from file" without format.
        val labelled = if (sourceName.contains("[binary]") || sourceName.contains("(binary)")) {
            sourceName
        } else {
            "[binary] $sourceName"
        }
        writeMetadata(context, labelled, count, size)
        return count
    }

    /**
     * Stream-parse a `{"word": "wórd", ...}` JSON object directly from an
     * InputStream. Uses constant memory (one key/value pair at a time) so the
     * full 165 MB dict only allocates the final HashMap, not the parse tree.
     */
    private fun parseJsonStream(input: InputStream, knownSize: Long): HashMap<String, String> {
        // Pre-size the HashMap roughly so we don't pay a dozen resizes during
        // load. ~10 bytes per entry on disk for Cyrillic JSON is a decent guess.
        val estimatedEntries = if (knownSize > 0) (knownSize / 10).toInt().coerceAtLeast(64) else 64
        val out = HashMap<String, String>(estimatedEntries)
        JsonReader(InputStreamReader(BufferedInputStream(input), Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val value = reader.nextString()
                if (key.isNotBlank() && value.isNotBlank()) {
                    out[key.lowercase()] = value
                }
            }
            reader.endObject()
        }
        return out
    }

    /**
     * Copy [input] to [output] up to [cap] bytes. Returns bytes written,
     * or -1 if the source exceeded the cap.
     */
    private fun copyWithCap(input: InputStream, output: FileOutputStream, cap: Long): Long {
        val buf = ByteArray(64 * 1024)
        var soFar = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            soFar += read
            if (soFar > cap) return -1
            output.write(buf, 0, read)
        }
        return soFar
    }

    /**
     * Reapply the casing pattern of [original] to [replacement], so a
     * lowercase-keyed entry can still respond to "Москва" by returning
     * "Москва́" instead of "москва́".
     */
    private fun applyCasing(original: String, replacement: String): String {
        val origLower = original.lowercase()
        if (original == origLower) return replacement
        if (original == original.uppercase()) return replacement.uppercase()
        if (original.length > 1 &&
            original[0].isUpperCase() &&
            original.substring(1) == original.substring(1).lowercase()
        ) {
            // Title case: capitalize the first letter of the replacement.
            return replacement.replaceFirstChar { it.uppercase() }
        }
        return replacement
    }
}
