package com.example.monthlyexpense.history

import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.data.ForegroundMutationTicket
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.data.HistoryPage
import com.example.monthlyexpense.history.api.*
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*

internal class ExpenseHistoryController(
    private val scope: CoroutineScope,
    private val reads: HistoryReadPort,
    private val state: SliceState<HistoryUiState>,
    private val navigation: HistoryNavigation,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val admissionLock: Any,
    private val ioDispatcher: CoroutineDispatcher,
) : HistoryRefreshPort {
    private var historyLoadGeneration = 0L
    /** Private invalidation only; host resets display once under the shared admission lock. */
    fun invalidateForRestore() { historyLoadGeneration++ }
    /** Pure candidate reset; composition publishes it with every other restored slice. */
    fun restoredState() = state.value.copy(loading = false, loadingMore = false)

    fun openHistory() {
        if (state.value.selectedMonth != null) {
            selectHistoryMonth(null)
        }
        var admission: HistoryLoadAdmission? = null
        synchronized(admissionLock) {
            val current = state.value
            var history = current
            if (!history.initialLoadComplete && !history.loading && !history.loadingMore) {
                coordinator.mutationTicket()?.let { ticket ->
                    history = history.copy(
                        loading = true,
                        loadingMore = false,
                        loadFailed = false,
                        failedCursor = null
                    )
                    admission = HistoryLoadAdmission(
                        ticket = ticket,
                        generation = historyLoadGeneration,
                        cursor = null
                    )
                }
            }
            navigation.open(history)
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun loadMoreHistory() {
        var admission: HistoryLoadAdmission? = null
        synchronized(admissionLock) {
            val history = state.value
            val cursor = history.nextCursor
            if (
                history.initialLoadComplete &&
                history.hasMore &&
                cursor != null &&
                !history.loading &&
                !history.loadingMore &&
                !history.loadFailed
            ) {
                coordinator.mutationTicket()?.let { ticket ->
                    state.update { it.copy(loadingMore = true, loadFailed = false, failedCursor = null) }
                    admission = HistoryLoadAdmission(
                        ticket = ticket,
                        generation = historyLoadGeneration,
                        cursor = cursor,
                        month = history.selectedMonth
                    )
                }
            }
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun retryHistoryLoad() {
        var admission: HistoryLoadAdmission? = null
        synchronized(admissionLock) {
            val history = state.value
            if (history.loadFailed && !history.loading && !history.loadingMore) {
                coordinator.mutationTicket()?.let { ticket ->
                    val cursor = history.failedCursor
                    state.update { it.copy(loading = cursor == null, loadingMore = cursor != null, loadFailed = false) }
                    admission = HistoryLoadAdmission(
                        ticket = ticket,
                        generation = historyLoadGeneration,
                        cursor = cursor,
                        month = history.selectedMonth
                    )
                }
            }
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun selectHistoryMonth(month: YearMonth?) {
        var admission: HistoryLoadAdmission? = null
        synchronized(admissionLock) {
            val ticket = coordinator.mutationTicket() ?: return
            val generation = ++historyLoadGeneration
            state.update { HistoryUiState(months = it.months, selectedMonth = month, loading = true) }
            admission = HistoryLoadAdmission(ticket, generation, null, month)
        }
        admission?.let(::launchHistoryPageLoad)
    }

    private fun launchHistoryPageLoad(admission: HistoryLoadAdmission) {
        scope.launch {
            try {
                when (val read = coordinator.runRead(admission.ticket) {
                    withContext(ioDispatcher) {
                        reads.loadHistoryPage(admission.cursor, HISTORY_PAGE_SIZE, admission.month)
                    }
                }) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> coordinator.commitIfCurrent(admission.ticket) {
                        if (historyLoadGeneration == admission.generation) {
                            state.update { it.withPage(admission.cursor, read.value) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coordinator.commitIfCurrent(admission.ticket) {
                    if (historyLoadGeneration == admission.generation) {
                        state.update { it.withLoadFailure(admission.cursor) }
                    }
                }
            }
        }
    }

    override fun prepareRefresh(
        requestedScope: RefreshScope,
        refreshDate: LocalDate,
        previousMonth: YearMonth?
    ): HistoryRefreshPlan {
        val current = state.value
        val monthRollover = previousMonth != null &&
            previousMonth != YearMonth.from(refreshDate)
        val effectiveScope = if (
            requestedScope == RefreshScope.HOME &&
            monthRollover &&
            current.initialLoadComplete
        ) {
            RefreshScope.ALL
        } else {
            requestedScope
        }
        if (effectiveScope == RefreshScope.HOME) {
            if (monthRollover) {
                historyLoadGeneration++
                state.update { HistoryUiState() }
            }
            return HistoryRefreshPlan(
                generation = null,
                loadFirstPage = false,
                previous = current
            )
        }

        val generation = ++historyLoadGeneration
        val loadFirstPage = navigation.isOpen ||
            current.initialLoadComplete
        val history = if (loadFirstPage) {
            current.copy(
                loading = true,
                loadingMore = false,
                loadFailed = false,
                failedCursor = null
            )
        } else {
            HistoryUiState()
        }
        state.update { history }
        return HistoryRefreshPlan(
            generation = generation,
            loadFirstPage = loadFirstPage,
            previous = current
        )
    }

    override suspend fun readForRefresh(plan: HistoryRefreshPlan): HistoryPageRead {
        if (!plan.loadFirstPage) return HistoryPageRead.NotRequested
        return try {
            HistoryPageRead.Loaded(withContext(ioDispatcher) { reads.loadHistoryPage(null, HISTORY_PAGE_SIZE, plan.previous.selectedMonth) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { HistoryPageRead.Failed(error) }
    }
    override fun reduceRefresh(
        read: HistoryPageRead,
        plan: HistoryRefreshPlan
    ): HistoryUiState {
        val current = state.value
        if (
            plan.generation != null &&
            historyLoadGeneration != plan.generation
        ) {
            return current
        }
        return when (read) {
            HistoryPageRead.NotRequested -> current
            is HistoryPageRead.Loaded -> current.withPage(null, read.page)
            is HistoryPageRead.Failed -> current.withLoadFailure(null)
        }
    }

    override fun failRefresh(plan: HistoryRefreshPlan) {
        val generation = plan.generation ?: return
        if (historyLoadGeneration != generation) return
        state.update { if (plan.loadFirstPage) plan.previous.withLoadFailure(null) else HistoryUiState() }
    }

}

private data class HistoryLoadAdmission(
    val ticket: ForegroundMutationTicket,
    val generation: Long,
    val cursor: HistoryCursor?,
    val month: YearMonth? = null
)

private fun HistoryUiState.withPage(
    cursor: HistoryCursor?,
    page: HistoryPage
): HistoryUiState {
    val publishedItems = if (cursor == null) {
        val pageIds = hashSetOf<Long>()
        page.items.filter { pageIds.add(it.id) }
    } else {
        val existingIds = items.asSequence().map { it.id }.toHashSet()
        val appended = page.items.filter { existingIds.add(it.id) }
        items + appended
    }
    return copy(
        months = page.months,
        items = publishedItems,
        nextCursor = page.nextCursor,
        initialLoadComplete = true,
        loading = false,
        loadingMore = false,
        hasMore = page.hasMore,
        loadFailed = false,
        failedCursor = null
    )
}

private fun HistoryUiState.withLoadFailure(cursor: HistoryCursor?): HistoryUiState = copy(
    initialLoadComplete = true,
    loading = false,
    loadingMore = false,
    loadFailed = true,
    failedCursor = cursor
)

