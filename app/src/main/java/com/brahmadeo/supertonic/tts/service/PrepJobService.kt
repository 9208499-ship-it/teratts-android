package com.brahmadeo.supertonic.tts.service

import android.app.job.JobParameters
import android.app.job.JobService
import com.brahmadeo.supertonic.tts.utils.BookPrep

/**
 * The system calls this every 15 minutes (only while charging, if so chosen): if the phone may
 * make audio now, the service is started to voice the open book's next chapters (BookPrep).
 */
class PrepJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        BookPrep.kick(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
