package com.brahmadeo.supertonic.tts.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates of the app from its GitHub releases (the same APKs as on 4PDA): once a day at start (if
 * not turned off) the latest release is looked up; a newer one is offered — tap, it is downloaded
 * and Android's installer opens. The app's own key signs every release, so it installs over this one.
 */
object Updater {
    private const val REPO = "9208499-ship-it/teratts-android"
    private const val PREFS = "TeraUpdates"
    private const val TAG = "TeraUpdate"
    private const val DAY = 24 * 60 * 60 * 1000L

    /** [beta]: a test version (a GitHub pre-release) — offered only to those who asked for them. */
    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val beta: Boolean = false)

    /** A newer release found (shown as a dialog by whatever screen is open). */
    val found = mutableStateOf<Release?>(null)

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun auto(c: Context) = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) = p(c).edit().putBoolean("auto", on).apply()
    fun skip(c: Context, version: String) = p(c).edit().putString("skipped", version).apply()
    /**
     * Test versions (pre-releases) are never offered: some of them may hardly work, and the author installs
     * them by hand (tolyos). The old «beta versions» switch is gone; one left on no longer counts.
     */
    @Suppress("UNUSED_PARAMETER") fun beta(c: Context) = false
    fun setBeta(c: Context, on: Boolean) = p(c).edit().putBoolean("beta", on).putLong("checked", 0L).apply()

    /** This app's version: "1.21" (of "1.21-tera"). */
    fun current(c: Context): String = try {
        (c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0").substringBefore('-')
    } catch (_: Exception) { "0" }

    private fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }

    /** Is [a] newer than [b] ("1.15.1" > "1.15", "1.21" > "1.9")? */
    fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The newest app release on GitHub with an APK (the models' releases are not app versions), or null. */
    suspend fun latest(beta: Boolean = false): Release? = withContext(Dispatchers.IO) {
        try {
            val con = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", "TeraTTS")
            con.connectTimeout = 15000; con.readTimeout = 15000
            if (con.responseCode != 200) { Log.w(TAG, "releases: HTTP ${con.responseCode}"); return@withContext null }
            val a = JSONArray(con.inputStream.bufferedReader().use { it.readText() })
            var best: Release? = null
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val pre = o.optBoolean("prerelease")
                if (o.optBoolean("draft") || (pre && !beta)) continue
                val tag = o.optString("tag_name")
                if (!Regex("""v\d+(\.\d+)*""").matches(tag)) continue
                val assets = o.optJSONArray("assets") ?: continue
                var url: String? = null; var size = 0L
                for (k in 0 until assets.length()) {
                    val s = assets.getJSONObject(k)
                    val name = s.optString("name")
                    if (name.endsWith(".apk") && name.contains("arm64")) { url = s.optString("browser_download_url"); size = s.optLong("size") }
                }
                if (url.isNullOrEmpty()) continue
                val v = tag.removePrefix("v")
                if (best == null || newer(v, best.version)) best = Release(v, o.optString("body").trim(), url, size, pre)
            }
            best
        } catch (e: Exception) { Log.w(TAG, "check: ${e.message}"); null }
    }

    /** At start: once a day, if allowed, a newer release (not skipped) becomes [found]; offered at every
     *  start until it is installed, put off («Позже») or skipped. */
    suspend fun checkAtStart(c: Context) {
        // the APK of an update already installed is not needed any more
        withContext(Dispatchers.IO) { File(c.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
        if (!auto(c)) return
        val now = System.currentTimeMillis()
        if (now - p(c).getLong("checked", 0L) < DAY) return
        val r = latest(beta(c)) ?: return   // no network: asked again at the next start
        if (newer(r.version, current(c)) && r.version != p(c).getString("skipped", null)) found.value = r
        else p(c).edit().putLong("checked", now).apply()
    }

    /** «Позже»: not asked again for a day (until then the offer comes at every start). */
    fun later(c: Context) { p(c).edit().putLong("checked", System.currentTimeMillis()).apply(); found.value = null }

    /** By hand (Settings → About): true if a newer one was found (then shown as [found]). */
    suspend fun checkNow(c: Context): Boolean? {
        val r = latest(beta(c)) ?: return null
        p(c).edit().putLong("checked", System.currentTimeMillis()).apply()
        return if (newer(r.version, current(c))) { found.value = r; true } else false
    }

    /** Downloads the release's APK into the app's cache ([progress] 0..1); the file, or an exception. */
    suspend fun download(c: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "TeraTTS-${r.version}.apk")
        val part = File(dir, f.name + ".part")
        val con = URL(r.apkUrl).openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "TeraTTS")
        con.connectTimeout = 20000; con.readTimeout = 30000
        con.instanceFollowRedirects = true
        if (con.responseCode != 200) throw IllegalStateException("HTTP ${con.responseCode}")
        val total = con.contentLengthLong.takeIf { it > 0 } ?: r.size
        con.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val pc = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    if (pc != shown) { shown = pc; progress(pc / 100f) }
                }
            }
        }
        if (r.size > 0 && part.length() != r.size) throw IllegalStateException("файл скачался не целиком")
        if (!part.renameTo(f)) throw IllegalStateException("не удалось сохранить файл")
        f
    }

    /** May this app hand an APK to the installer (Android 8+ asks the user once)? */
    fun canInstall(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    /** The system screen where installing from this app is allowed. */
    fun askInstallPermission(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
        } catch (_: Exception) { }
    }

    /** Opens Android's installer for the downloaded [apk]. */
    fun install(a: Activity, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".fileprovider", apk)
        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
