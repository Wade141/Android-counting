package com.example.monthlyexpense.backup.api

import com.example.monthlyexpense.data.ForegroundSettingsBaseline

internal fun interface RestoreUiBoundary {
    /** Called once after beginRestore succeeds, under the shared admission lock, before suspension.
     * Invalidate all old handles, publish one reset, then complete detached waiters/cancel jobs.
     * No IO, new tickets, dynamic registration or durable-gate changes. Must not throw midway. */
    fun begin(baseline: ForegroundSettingsBaseline)
}

internal fun interface BackupSettingsCommit {
    /** Synchronous UI-only baseline projection after a current restore outcome, even if home reads
     * failed. Never changes ledger/display epoch, durable gate or other pending fields. */
    fun applyPersistedSettings(baseline: ForegroundSettingsBaseline)
}
