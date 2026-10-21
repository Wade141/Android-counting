package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.refresh.api.HomeReadPort
import java.time.LocalDate
import java.time.ZoneId

internal class HomeRepositoryAdapter(private val repository: ExpenseRepositoryContract) : HomeReadPort {
    override suspend fun loadHomeSnapshot(now: LocalDate, zoneId: ZoneId) = repository.loadHomeSnapshot(now, zoneId)
    override suspend fun loadReadOnlyHomeSnapshot(now: LocalDate, zoneId: ZoneId) = repository.loadReadOnlyHomeSnapshot(now, zoneId)
    override suspend fun refreshHomeSnapshot(now: LocalDate, zoneId: ZoneId, previous: ExpenseHomeSnapshot, domains: Set<DataDomain>) =
        repository.refreshHomeSnapshot(now, zoneId, previous, domains)
}
