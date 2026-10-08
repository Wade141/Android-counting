package com.example.monthlyexpense.screenshot

import androidx.lifecycle.SavedStateHandle

/** Conservative: a draft from a previous process/ledger epoch requires a fresh image. */
internal fun bindScreenshotSession(saved: SavedStateHandle, currentSession: String) {
    val previous = saved.get<String>("ledgerSession")
    if (saved.get<Boolean>("active") == true && saved.get<Long>("savedId") == null && previous != currentSession) {
        saved["requiresNewImage"] = true
    }
    saved["ledgerSession"] = currentSession
}
