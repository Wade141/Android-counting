package com.example.monthlyexpense.notification.api

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.data.ExpenseHomeSnapshot

/** Exact suspend write signatures from ExpenseRepositoryContract; no UI effects. */
internal interface AutoSettingsWritePort {
    suspend fun setAutoBookkeepingEnabled(enabled: Boolean)
    suspend fun setWeChatEnabled(enabled: Boolean)
    suspend fun setAlipayEnabled(enabled: Boolean)
}

internal data class SettingGenerations(val autoBookkeeping: Long, val weChat: Long, val alipay: Long)

internal interface SettingsSnapshotPort {
    fun captureGenerations(): SettingGenerations
    /** Computes but never publishes; caller commits with the home snapshot without suspension. */
    fun mergeSnapshot(snapshot: ExpenseHomeSnapshot, captured: SettingGenerations): AutoBookkeepingUiState
}
