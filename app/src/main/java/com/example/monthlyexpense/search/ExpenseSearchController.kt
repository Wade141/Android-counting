package com.example.monthlyexpense.search

import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.ExpenseUiState
import com.example.monthlyexpense.data.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*

/** Scheduling stays on the ViewModel's main scope; SQLite work is dispatched by the repository. */
internal class ExpenseSearchController(
    private val scope: CoroutineScope,
    private val repository: ExpenseRepositoryContract,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val state: () -> ExpenseUiState,
    private val update: ((ExpenseUiState) -> ExpenseUiState) -> Unit,
    private val today: () -> LocalDate,
    private val zone: () -> ZoneId
) {
    private var generation = 0L
    private var debounce: Job? = null
    private var pending: Request? = null
    private var running = false
    private var currentQuery: ExpenseSearchQuery? = null
    private var date = today()
    private var zoneId = zone()
    private data class Request(val generation: Long, val query: ExpenseSearchQuery,
        val cursor: ExpenseSearchCursor?, val ticket: ForegroundReadTicket)
    private val search get() = state().search
    private val visible get() = state().overlay == ExpenseOverlay.Search || search.editingExpense != null
    private fun change(block: (ExpenseSearchUiState) -> ExpenseSearchUiState) = update { it.copy(search = block(it.search)) }

    fun open() {
        date = today(); zoneId = zone()
        update { it.copy(overlay = ExpenseOverlay.Search, search = it.search.copy(editingExpense = null, isComposing = false)) }
        invalidate(clear = true)
        firstPage()
    }
    fun close() {
        invalidate(clear = false)
        update { it.copy(overlay = null, search = it.search.copy(filterDraft = null, editingExpense = null, isComposing = false)) }
    }
    fun resetForRestore() {
        invalidate(clear = true)
        update { it.copy(search = ExpenseSearchUiState()) }
    }
    private fun invalidate(clear: Boolean) {
        generation++; debounce?.cancel(); debounce = null; pending = null; currentQuery = null
        change { it.copy(items = if (clear) emptyList() else it.items,
            summary = if (clear) null else it.summary, nextCursor = null, hasMore = false,
            loading = false, loadingMore = false, updating = false, dirty = true, canEdit = false,
            error = null, failedCursor = null, validationError = null) }
    }
    fun keyword(value: String, composing: Boolean) {
        val before = search
        change { it.copy(keyword = value, isComposing = composing) }
        if (!composing && !before.isComposing && value.trim() == before.keyword.trim()) return
        invalidate(clear = true)
        if (!composing && visible) debounce = scope.launch { delay(300); debounce = null; firstPage() }
    }
    fun submit() {
        debounce?.cancel(); debounce = null
        change { it.copy(isComposing = false) }
        if (search.dirty && !search.loading) firstPage()
    }
    fun openFilters() = change { it.copy(filterDraft = it.appliedFilters, validationError = null) }
    fun draft(filters: ExpenseSearchFilters) = change { it.copy(filterDraft = filters, validationError = null) }
    fun cancelFilters() = change { it.copy(filterDraft = null, validationError = null) }
    fun resetDraft() = draft(ExpenseSearchFilters())
    fun applyFilters() { search.filterDraft?.let(::replaceFilters) }
    fun replaceFilters(filters: ExpenseSearchFilters) {
        val validation = validateSearch(search.keyword, filters, date, zoneId)
        if (validation is SearchValidationResult.Invalid) {
            change { it.copy(validationError = validation.message) }; return
        }
        val changed = search.appliedFilters != filters
        change { it.copy(appliedFilters = filters, filterDraft = null, validationError = null) }
        if (changed) { invalidate(clear = true); if (visible && !search.isComposing) firstPage() }
    }
    fun clear() {
        change { it.copy(keyword = "", isComposing = false, appliedFilters = ExpenseSearchFilters(), filterDraft = null) }
        invalidate(clear = true); if (visible) firstPage()
    }
    fun dataChanged(domains: Set<DataDomain>) {
        if (domains.none { it == DataDomain.LEDGER || it == DataDomain.SETTINGS }) return
        // Date/timezone broadcasts also arrive as ledger revisions while the Activity stays resumed.
        date = today(); zoneId = zone()
        refreshResults()
    }
    private fun refreshResults() {
        invalidate(clear = false)
        if (visible && !search.isComposing) firstPage()
    }
    fun environmentChanged(newDate: LocalDate, newZone: ZoneId) {
        date = newDate; zoneId = newZone
        refreshResults()
    }
    private fun firstPage() {
        if (!visible || search.isComposing) return
        when (val validation = validateSearch(search.keyword, search.appliedFilters, date, zoneId)) {
            is SearchValidationResult.Invalid -> change { it.copy(validationError = validation.message, loading = false) }
            is SearchValidationResult.Valid -> {
                currentQuery = validation.query
                enqueue(validation.query, null)
            }
        }
    }
    fun more() {
        if (!visible || search.dirty || search.loading || search.loadingMore || !search.hasMore || search.error != null) return
        val cursor = search.nextCursor ?: return
        currentQuery?.let { enqueue(it, cursor) }
    }
    fun retry() {
        if (search.loading || search.loadingMore || !visible) return
        val cursor = search.failedCursor
        if (cursor != null && !search.dirty) currentQuery?.let { enqueue(it, cursor) }
        else firstPage()
    }
    fun edit(id: Long) {
        if (!search.canEdit || search.dirty || coordinator.mutationTicket(search.displayedDataEpoch) == null) return
        val expense = search.items.firstOrNull { it.id == id } ?: return
        update { it.copy(overlay = ExpenseOverlay.EditExpense(id),
            search = it.search.copy(editingExpense = SearchEditSnapshot(expense, it.search.displayedDataEpoch))) }
    }
    fun returnFromEdit() {
        update { it.copy(overlay = ExpenseOverlay.Search, search = it.search.copy(editingExpense = null)) }
        if (search.dirty && !search.loading) firstPage()
    }
    private fun enqueue(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?) {
        val ticket = coordinator.readTicket() ?: return
        pending = Request(generation, query, cursor, ticket)
        change { it.copy(loading = cursor == null, loadingMore = cursor != null,
            updating = cursor == null && it.items.isNotEmpty(), canEdit = false,
            error = null, failedCursor = null, validationError = null) }
        if (running) return
        running = true
        scope.launch {
            try {
                while (true) {
                    val request = pending ?: break
                    pending = null
                    execute(request)
                }
            } finally { running = false }
        }
    }
    private fun isCurrent(request: Request) = visible && request.generation == generation && request.query == currentQuery
    private suspend fun execute(request: Request) {
        try {
            val result = coordinator.runRead(request.ticket) {
                repository.loadSearchPage(request.query, request.cursor)
            }
            if (result is CoordinatedMutation.Executed && isCurrent(request)) {
                coordinator.commitReadIfCurrent(request.ticket) { epoch ->
                    val page = result.value
                    change { it.copy(
                        items = if (request.cursor == null) page.items else (it.items + page.items).distinctBy { row -> row.id },
                        summary = if (request.cursor == null) page.summary else it.summary,
                        nextCursor = page.nextCursor, hasMore = page.hasMore,
                        loading = false, loadingMore = false, updating = false, dirty = false,
                        displayedDataEpoch = epoch, canEdit = coordinator.mutationTicket(epoch) != null,
                        error = null, failedCursor = null) }
                }
            }
            if (isCurrent(request) && !coordinator.isReadCurrent(request.ticket)) {
                change { it.copy(loading = false, loadingMore = false, updating = false, dirty = true, canEdit = false) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (isCurrent(request)) coordinator.commitReadIfCurrent(request.ticket) {
                change { it.copy(loading = false, loadingMore = false, updating = false,
                    error = "加载失败，请重试", failedCursor = request.cursor,
                    canEdit = !it.dirty && coordinator.mutationTicket(it.displayedDataEpoch) != null) }
            }
        }
    }
}
