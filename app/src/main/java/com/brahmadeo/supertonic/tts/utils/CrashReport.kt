package com.brahmadeo.supertonic.tts.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What to send the developer after a crash. Users on the forum see only "вылет": the
 * reader closes or TeraTTS falls silent, and without a log there is nothing to fix.
 *  - a Java exception in any part of the app (the player, the engine for other readers)
 *    is written to a file by [install] before the process dies;
 *  - from Android 11 on the system also keeps why the process ended: a native crash
 *    in the engine (with its stack), "not responding", killed for lack of memory.
 * The next time the app is opened, [pending] gives the report once, and the app offers to
 * share it (text: paste it on the forum or send it anywhere).
 */
object CrashReport {
    private const val TAG = "TeraCrash"
    private const val FILE = "last_crash.txt"
    private const val PREFS = "TeraCrashReport"
    private const val KEY_SEEN = "exit_seen_ms"
    private const val MAX_TRACE = 24_000

    @Volatile private var installed = false

    /** Write any uncaught exception to a file, then let the system handle it as before. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                File(app.filesDir, FILE).writeText(
                    "Время: ${stamp(System.currentTimeMillis())}\nПоток: ${thread.name}\n\n" + sw.toString().take(MAX_TRACE)
                )
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun stamp(ms: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date(ms))

    private fun header(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "?" }
        return "TeraTTS $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " · ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}"
    }

    /** The report of the last crash not shown yet, or null. Shown once. */
    fun pending(context: Context): String? {
        val parts = ArrayList<String>()
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            try { parts.add(f.readText()) } catch (_: Exception) { }
            f.delete()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { exitInfo(context)?.let { parts.add(it) } } catch (e: Exception) { Log.w(TAG, "exit info: ${e.message}") }
        }
        if (parts.isEmpty()) return null
        return header(context) + "\n\n" + parts.joinToString("\n\n----------\n\n")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun exitInfo(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // the first run with this check: crashes since this version was installed count (a crash in
        // a reader before the app was ever opened after the update used to be taken as "old news")
        val seen = prefs.getLong(KEY_SEEN, -1L).takeIf { it >= 0 } ?: try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Exception) { 0L }
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val all = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val newest = all.maxOfOrNull { it.timestamp } ?: return null
        prefs.edit().putLong(KEY_SEEN, maxOf(newest, seen)).apply()
        val bad = all.filter {
            it.timestamp > seen && it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
            ) &&
                // killed for memory while just lying in the background is normal; while reading — not
                (it.reason != ApplicationExitInfo.REASON_LOW_MEMORY ||
                    it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
        }.sortedByDescending { it.timestamp }
        if (bad.isEmpty()) return null
        return bad.take(3).joinToString("\n\n") { describe(it) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun describe(e: ApplicationExitInfo): String {
        run {
            val reason = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH -> "сбой (Java)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой движка (native)"
                ApplicationExitInfo.REASON_ANR -> "не отвечает (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "закрыт системой: мало памяти"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
                else -> "завершён (причина ${e.reason}, ${e.status})"
            }
            val sb = StringBuilder()
            sb.append("Время: ").append(stamp(e.timestamp)).append(" · ").append(reason)
            sb.append("\nПроцесс: ").append(e.processName)
            sb.append(" · память: ").append(e.pss / 1024).append(" МБ (pss), ").append(e.rss / 1024).append(" МБ (rss)")
            e.description?.let { sb.append("\nОписание: ").append(it) }
            // native crash: the tombstone (binary on Android 12+, text before); ANR: the threads' stacks
            if (e.reason == ApplicationExitInfo.REASON_ANR ||
                (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                try {
                    e.traceInputStream?.use { s ->
                        val text = s.bufferedReader().readText()
                        sb.append("\n\n").append(text.take(MAX_TRACE))
                    }
                } catch (_: Exception) { }
            } else if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                try {
                    e.traceInputStream?.use { s ->
                        // protobuf tombstone: the readable bits are enough (signal, library, function names)
                        val bytes = s.readBytes()
                        val words = Regex("""[\x20-\x7E]{6,}""").findAll(String(bytes, Charsets.ISO_8859_1))
                            .map { it.value }.filter { "/" in it || "SIG" in it || "_" in it || "::" in it }
                            .distinct().take(120).joinToString("\n")
                        sb.append("\n\n").append(words.take(MAX_TRACE))
                    }
                } catch (_: Exception) { }
            }
            return sb.toString()
        }
    }

    /**
     * Everything for the developer, asked for by hand (Settings → "Отправить журнал"): why the
     * app's processes ended lately (any reason) and the app's own system log — logcat keeps the
     * lines of earlier runs too, so a fall inside Moon+ Reader a minute ago is in it even if
     * nothing "crashed" on our side (an app may read its own log without any permission).
     */
    fun shareLog(context: Context) {
        val sb = StringBuilder(header(context)).append("\n\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                am?.getHistoricalProcessExitReasons(context.packageName, 0, 8)?.forEach {
                    sb.append(describe(it).take(6_000)).append("\n\n")
                }
            } catch (e: Exception) { sb.append("exit info: ${e.message}\n") }
        }
        val crash = File(context.filesDir, FILE)
        if (crash.exists()) try { sb.append(crash.readText()).append("\n\n") } catch (_: Exception) { }
        sb.append("----- logcat -----\n")
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "4000"))
            val log = p.inputStream.bufferedReader().readText()
            sb.append(if (log.length > 400_000) log.takeLast(400_000) else log)
        } catch (e: Exception) { sb.append("logcat: ${e.message}") }
        val dir = File(context.cacheDir, "tts_output").apply { mkdirs() }
        val f = File(dir, "teratts_log.txt")
        f.writeText(sb.toString())
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "TeraTTS: журнал")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "TeraTTS: журнал").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Share the report as text (to the forum, a messenger, mail). */
    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "TeraTTS: отчёт о сбое")
            .putExtra(Intent.EXTRA_TEXT, report)
        context.startActivity(Intent.createChooser(send, "TeraTTS: отчёт о сбое").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
