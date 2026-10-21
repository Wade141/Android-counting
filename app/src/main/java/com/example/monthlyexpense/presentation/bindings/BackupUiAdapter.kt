package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.backup.api.BackupNavigation
import com.example.monthlyexpense.presentation.ForegroundUiStore

internal class BackupUiAdapter(private val store: ForegroundUiStore) : BackupNavigation {
    override fun showRestoreConfirmation() = store.commit { it.copy(overlay = ExpenseOverlay.ConfirmRestore) }

    override fun closeRestoreConfirmationIfCurrent(): Boolean =
        store.commitIf({ it.overlay == ExpenseOverlay.ConfirmRestore }) { it.copy(overlay = null) }
}
