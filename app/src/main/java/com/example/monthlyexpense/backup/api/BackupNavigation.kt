package com.example.monthlyexpense.backup.api

internal interface BackupNavigation {
    /** Synchronous single UI publication, no IO or validation. */
    fun showRestoreConfirmation()
    /** False means the user has already navigated elsewhere. */
    fun closeRestoreConfirmationIfCurrent(): Boolean
}
