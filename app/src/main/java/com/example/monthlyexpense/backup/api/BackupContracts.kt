package com.example.monthlyexpense.backup.api

/** Physical fields live in the aggregate store. Content, JSON and write receipts remain private. */
internal data class BackupOperationState(val exportPreparing: Boolean = false, val restoring: Boolean = false)
