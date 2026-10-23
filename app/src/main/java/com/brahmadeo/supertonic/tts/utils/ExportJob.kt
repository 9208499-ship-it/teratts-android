package com.brahmadeo.supertonic.tts.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.brahmadeo.supertonic.tts.R
import com.brahmadeo.supertonic.tts.service.PlaybackService

/**
 * Saving a whole book survives the app being killed: Huawei and Xiaomi close it at night,
 * low memory does elsewhere — and the saving used to stop at that chapter for good.
 *
 * The job is written down when the saving starts and wiped when it ends in any ordinary way
 * (done, stopped by the user, replaced by reading, failed). A job still written down means the
 * process died in the middle. Then the saving goes on by itself:
 *  - the player service is "sticky" while a job is written down: after a kill for memory the
 *    system starts it again, and it continues;
 *  - an alarm looks in every 10 minutes while a job is written down (that restart may never
 *    come); if the system does not let the app start in the background, a notification
 *    offers to continue with one tap;
 *  - opening the app continues it too.
 * Chapters already in the book's folder are skipped (as before), so only the chapter that was
 * cut short is made again. After 6 attempts with no new chapter saved it gives up and says so.
 */
object ExportJob {
    private const val TAG = "TeraExportJob"
    private const val PREFS = "TeraExportJob"
    const val ACTION_RESUME = "TERA_RESUME_EXPORT"
    const val ACTION_STICKY = "TERA_EXPORT_STICKY"
    private const val CHANNEL = "tera_export_job"
    private const val NOTIFY_ID = 7302
    private const val REVIVE_MS = 10 * 60_000L
    private const val MAX_ATTEMPTS = 6

    /** A book is being saved by this process right now. */
    @Volatile var runningHere = false

    class Job(val lang: String, val stylePath: String, val speed: Float, val steps: Int,
              val outputDir: String, val book: String?)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(c: Context, lang: String, stylePath: String, speed: Float, steps: Int, outputDir: String, book: String?) {
        prefs(c).edit().clear()
            .putString("lang", lang).putString("style", stylePath).putFloat("speed", speed)
            .putInt("steps", steps).putString("dir", outputDir).putString("book", book)
            .putInt("saved", 0).putInt("attempts", 0)
            .commit()
        arm(c)
    }

    /** [saved] chapters are in the folder now: a new one resets the attempts. */
    fun chapterDone(c: Context, saved: Int) {
        val p = prefs(c)
        if (p.getString("dir", null) == null) return
        if (saved > p.getInt("saved", 0)) p.edit().putInt("saved", saved).putInt("attempts", 0).apply()
        arm(c)
    }

    fun finish(c: Context) {
        prefs(c).edit().clear().commit()
        try { alarms(c)?.cancel(alarmIntent(c)) } catch (_: Exception) { }
    }

    fun pending(c: Context): Job? {
        val p = prefs(c)
        val dir = p.getString("dir", null) ?: return null
        return Job(p.getString("lang", "ru") ?: "ru", p.getString("style", "") ?: "",
            p.getFloat("speed", 1f), p.getInt("steps", 5), dir, p.getString("book", null))
    }

    private fun alarms(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun alarmIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, NOTIFY_ID, Intent(c, ExportReviver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Look in again in 10 minutes (inexact, also in Doze; no special permission needed). */
    fun arm(c: Context) {
        try {
            val at = System.currentTimeMillis() + REVIVE_MS
            val am = alarms(c) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
            else am.set(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
        } catch (e: Exception) { Log.w(TAG, "alarm: ${e.message}") }
    }

    private fun resumeIntent(c: Context) = Intent(c, PlaybackService::class.java).setAction(ACTION_RESUME)

    /**
     * Continue a job left by a killed process (the alarm, the app being opened). [byUser]: the
     * app was opened — a start from the foreground, always allowed.
     */
    fun revive(c: Context, byUser: Boolean) {
        if (pending(c) == null) return
        if (runningHere || PlaybackService.busy()) { arm(c); return }
        val p = prefs(c)
        val attempts = p.getInt("attempts", 0) + 1
        p.edit().putInt("attempts", attempts).commit()
        if (attempts > MAX_ATTEMPTS) {
            Log.w(TAG, "giving up after $MAX_ATTEMPTS attempts")
            notifyStopped(c, gaveUp = true)
            finish(c)
            return
        }
        arm(c)  // in case this attempt dies too
        Log.i(TAG, "continuing the book's saving (attempt $attempts)")
        try {
            androidx.core.content.ContextCompat.startForegroundService(c, resumeIntent(c))
            if (byUser) android.widget.Toast.makeText(c, R.string.export_job_resumed, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Android 12+: no start from the background unless battery optimisation is off for the app
            Log.w(TAG, "cannot start from the background: ${e.message}")
            notifyStopped(c, gaveUp = false)
        }
    }

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                c.getString(R.string.export_job_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun post(c: Context, text: String, tap: PendingIntent?) {
        try {
            if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
            channel(c)
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(c.getString(R.string.export_job_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .apply { if (tap != null) setContentIntent(tap) }
                .build()
            NotificationManagerCompat.from(c).notify(NOTIFY_ID, n)
        } catch (e: SecurityException) { Log.w(TAG, "notification: ${e.message}") }
    }

    /** The saving stopped and could not go on by itself: one tap continues it ([gaveUp]: it gave up). */
    fun notifyStopped(c: Context, gaveUp: Boolean) {
        val tap = if (gaveUp) null else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(c, NOTIFY_ID, resumeIntent(c), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getService(c, NOTIFY_ID, resumeIntent(c), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(c, c.getString(if (gaveUp) R.string.export_job_gave_up else R.string.export_job_tap), tap)
    }

    /** A continued saving has finished (nobody may be looking at the player). */
    fun notifyDone(c: Context, where: String) = post(c, c.getString(R.string.export_job_done, where), null)

    fun cancelNotification(c: Context) {
        try { NotificationManagerCompat.from(c).cancel(NOTIFY_ID) } catch (_: Exception) { }
    }
}

/** The alarm of [ExportJob]: continues a book's saving left by a killed process. */
class ExportReviver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ExportJob.revive(context.applicationContext, byUser = false)
    }
}
