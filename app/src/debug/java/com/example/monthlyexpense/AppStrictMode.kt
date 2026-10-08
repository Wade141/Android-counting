package com.example.monthlyexpense

import android.os.StrictMode

internal object AppStrictMode {
    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build()
        )
    }
}
