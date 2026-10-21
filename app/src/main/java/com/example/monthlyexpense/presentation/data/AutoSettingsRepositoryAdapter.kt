package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.notification.api.AutoSettingsWritePort

internal class AutoSettingsRepositoryAdapter(private val repository: ExpenseRepositoryContract) : AutoSettingsWritePort {
    override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) = repository.setAutoBookkeepingEnabled(enabled)
    override suspend fun setWeChatEnabled(enabled: Boolean) = repository.setWeChatEnabled(enabled)
    override suspend fun setAlipayEnabled(enabled: Boolean) = repository.setAlipayEnabled(enabled)
}
