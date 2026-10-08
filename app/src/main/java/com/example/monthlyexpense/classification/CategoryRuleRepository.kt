package com.example.monthlyexpense.classification

import com.example.monthlyexpense.data.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CategoryRuleStore {
    suspend fun load(): List<CategoryRuleSet>?
    suspend fun save(draft: CategoryRuleSet, epoch: Long): RuleSaveResult
}

class CategoryRuleRepository(private val dao: CategoryRuleDao,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val changes: DataChangePublisher,
    private val io: CoroutineDispatcher = Dispatchers.IO) : CategoryRuleStore {
    override suspend fun load(): List<CategoryRuleSet>? {
        val ticket = coordinator.readTicket() ?: return null
        return when (val result = coordinator.runRead(ticket) { withContext(io) { dao.loadRuleSets() } }) {
            is CoordinatedMutation.Executed -> result.value.takeIf { coordinator.isReadCurrent(ticket) }
            CoordinatedMutation.Rejected -> null
        }
    }
    override suspend fun save(draft: CategoryRuleSet, epoch: Long): RuleSaveResult {
        val ticket = coordinator.mutationTicket(epoch) ?: return RuleSaveResult.Stale
        return when (val result = coordinator.runMutation(ticket) { withContext(io) {
            when (val committed = coordinator.commitIfCurrent(ticket) { dao.saveRuleSet(draft, draft.revision) }) {
                is CoordinatedMutation.Executed -> committed.value
                CoordinatedMutation.Rejected -> RuleSaveResult.Stale
            }
        } }) {
            is CoordinatedMutation.Executed -> {
                if (!coordinator.isCurrent(ticket)) RuleSaveResult.Stale else result.value.also {
                    if (it is RuleSaveResult.Success) changes.publish(setOf(DataDomain.SETTINGS))
                }
            }
            CoordinatedMutation.Rejected -> RuleSaveResult.Stale
        }
    }
}
