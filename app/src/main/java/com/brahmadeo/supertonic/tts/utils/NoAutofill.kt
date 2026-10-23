package com.brahmadeo.supertonic.tts.utils

import android.app.Activity
import android.os.Build
import android.view.View

/**
 * The app's windows are left out of autofill. On Android 10 a tap in the text field holding a
 * whole chapter sent all of it (800 KB) to the system's autofill service — "Unreasonably large
 * binder buffer" in the log — and MIUI closed the app. There is nothing to autofill here anyway.
 */
object NoAutofill {
    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                activity.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } catch (_: Exception) { }
        }
    }
}
