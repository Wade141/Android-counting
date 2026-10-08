package com.example.monthlyexpense.notification

import android.util.Log

/** Internal Logcat diagnostics only. Never pass notification text, amounts, merchants or keys. */
internal object NotificationPipelineLog {
    fun event(stage: String) {
        Log.i("BookkeepingPipeline", stage)
    }
}
