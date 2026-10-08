package com.example.monthlyexpense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseSearchViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val category=ExpenseCategory("FOOD","饮食",0xff123456,true,0)
    private fun record(id: Long, name: String = "茶")=ExpenseRecord(id,100,category,name,"",1000,ExpenseSource.MANUAL,null)
    private fun page(id: Long=1, name: String="茶")=ExpenseSearchPage(listOf(record(id,name)),null,false,ExpenseSearchSummary(1,100))
    private fun vm(repository: ExpenseRepositoryContract, dispatcher: CoroutineDispatcher,
        coordinator: ForegroundPersistenceCoordinator=ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false,true,true))) =
        ExpenseViewModel(repository,FakeExpenseBackupManager(),SavedStateHandle(),dispatcher,
            today={LocalDate.of(2026,9,30)},zoneId={ZoneId.of("Asia/Shanghai")},coordinator=coordinator)

    @Test fun typingDebouncesAndOldResultsCannotOverwriteLatestQuery() = runTest {
        val gate=CompletableDeferred<Unit>(); val seen=mutableListOf<String>()
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int): ExpenseSearchPage {
                seen+=query.keyword; if(query.keyword=="旧") gate.await()
                return page(name=query.keyword)
            }
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); runCurrent()
            vm.setSearchKeyword("旧",false); advanceTimeBy(300); runCurrent()
            vm.setSearchKeyword("新",false)
            assertNull(vm.uiState.value.search.summary)
            gate.complete(Unit); runCurrent()
            assertTrue(vm.uiState.value.search.items.isEmpty())
            advanceTimeBy(299); runCurrent(); assertEquals(listOf("","旧"),seen)
            advanceTimeBy(1); runCurrent()
            assertEquals("新",vm.uiState.value.search.items.single().name)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun imeFlushesOnceAndCompositionDoesNotQuery() = runTest {
        val seen=mutableListOf<String>()
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int): ExpenseSearchPage {
                seen+=query.keyword; return page(name=query.keyword)
            }
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); runCurrent()
            vm.setSearchKeyword("cha",true); advanceUntilIdle(); assertEquals(listOf(""),seen)
            vm.setSearchKeyword("茶",false); vm.submitSearch(); runCurrent(); advanceUntilIdle()
            assertEquals(listOf("","茶"),seen)
            vm.setSearchKeyword(" 茶 ",false); advanceUntilIdle(); assertEquals(2,seen.size)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun newestQueryReplacesQueuedQueryWithoutConcurrentReads() = runTest {
        val gate=CompletableDeferred<Unit>(); val seen=mutableListOf<String>(); var active=0; var peak=0
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int): ExpenseSearchPage {
                active++; peak=maxOf(peak,active); seen+=query.keyword
                if(query.keyword=="") gate.await()
                active--; return page(name=query.keyword)
            }
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); runCurrent()
            vm.setSearchKeyword("A",false); vm.submitSearch(); runCurrent()
            vm.setSearchKeyword("B",false); vm.submitSearch(); runCurrent()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf("","B"),seen); assertEquals(1,peak)
            assertEquals("B",vm.uiState.value.search.items.single().name)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun failedAppendRetainsSummaryAndCanRetryWithoutDuplicates() = runTest {
        var fail=true; var calls=0
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int): ExpenseSearchPage {
                calls++
                if(cursor==null) return ExpenseSearchPage(listOf(record(2)),ExpenseSearchCursor(1000,2),true,ExpenseSearchSummary(2,200))
                if(fail) error("disk read")
                return ExpenseSearchPage(listOf(record(1)),null,false,null)
            }
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle(); vm.loadMoreSearch(); vm.loadMoreSearch(); advanceUntilIdle()
            assertEquals(2,calls); assertEquals(listOf(2L),vm.uiState.value.search.items.map { it.id })
            assertNotNull(vm.uiState.value.search.error); assertEquals(2L,vm.uiState.value.search.summary!!.count)
            fail=false; vm.retrySearch(); advanceUntilIdle()
            assertEquals(listOf(2L,1L),vm.uiState.value.search.items.map { it.id }); assertNull(vm.uiState.value.search.error)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun draftsCancelAndInvalidAmountsCannotChangeAppliedFilters() = runTest {
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int)=page()
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle(); vm.openSearchFilters()
            vm.updateSearchFilterDraft(ExpenseSearchFilters(minAmountText="-1")); vm.applySearchFilters()
            assertNotNull(vm.uiState.value.search.filterDraft); assertEquals("",vm.uiState.value.search.appliedFilters.minAmountText)
            vm.cancelSearchFilters(); assertNull(vm.uiState.value.search.filterDraft)
            vm.replaceSearchFilters(ExpenseSearchFilters(categoryKeys=setOf("FOOD"))); advanceUntilIdle()
            assertEquals(setOf("FOOD"),vm.uiState.value.search.appliedFilters.categoryKeys)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun ledgerInvalidatesButReviewAndHiddenPageDoNotQuery() = runTest {
        var count=0
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int)=page((++count).toLong())
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle(); vm.onSearchDataChanged(setOf(DataDomain.REVIEW)); advanceUntilIdle(); assertEquals(1,count)
            vm.onSearchDataChanged(setOf(DataDomain.LEDGER)); advanceUntilIdle(); assertEquals(2L,vm.uiState.value.search.items.single().id)
            vm.closeSearch(); vm.onSearchDataChanged(setOf(DataDomain.LEDGER)); advanceUntilIdle(); assertEquals(2,count)
            vm.openSearch(); advanceUntilIdle(); assertEquals(3,count)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun restoreEpochRejectsOldSearchAndBlocksEditingDuringPendingSettings() = runTest {
        val gate=CompletableDeferred<Unit>()
        val coordinator=ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false,true,true))
        val repo=object: ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int):ExpenseSearchPage { gate.await(); return page() }
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler),coordinator)
        try {
            vm.openSearch(); runCurrent(); coordinator.setDurableRestorePending(true); gate.complete(Unit); advanceUntilIdle()
            assertTrue(vm.uiState.value.search.items.isEmpty())
            vm.onSearchDataChanged(setOf(DataDomain.LEDGER)); advanceUntilIdle()
            assertEquals(1L,vm.uiState.value.search.items.single().id)
            vm.openSearchExpense(1); assertEquals(ExpenseOverlay.Search,vm.uiState.value.overlay)
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun searchEditReturnsToSearchWithoutDependingOnHomeRows() = runTest {
        val fake=FakeExpenseRepository().apply { homeSnapshot=homeSnapshot.copy(categories=listOf(category)) }
        val repo=object: ExpenseRepositoryContract by fake {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery,cursor: ExpenseSearchCursor?,limit: Int)=page()
        }
        val vm=vm(repo,StandardTestDispatcher(testScheduler))
        try {
            vm.refresh(com.example.monthlyexpense.notification.NotificationListenerConnectionState.CONNECTED); advanceUntilIdle()
            vm.openSearch(); advanceUntilIdle(); vm.openSearchExpense(1)
            assertEquals(ExpenseOverlay.EditExpense(1),vm.uiState.value.overlay)
            assertEquals(1L,vm.uiState.value.search.editingExpense!!.expense.id)
            vm.dismissOverlay(); assertEquals(ExpenseOverlay.Search,vm.uiState.value.overlay)
            vm.openSearchExpense(1); vm.updateExpense(1,ExpenseEditInput("新名称","","FOOD")); advanceUntilIdle()
            assertEquals(1,fake.updateExpenseCalls); assertEquals(ExpenseOverlay.Search,vm.uiState.value.overlay)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun staleAppendAndStaleErrorsCannotLeakIntoNewConditions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = object : ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int): ExpenseSearchPage {
                if (cursor != null) { gate.await(); error("old page failed") }
                return ExpenseSearchPage(listOf(record(2, query.keyword)), ExpenseSearchCursor(1000, 2), true, ExpenseSearchSummary(2, 200))
            }
        }
        val vm = vm(repo, StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle(); vm.loadMoreSearch(); runCurrent()
            vm.setSearchKeyword("新", false); vm.submitSearch(); gate.complete(Unit); advanceUntilIdle()
            assertNull(vm.uiState.value.search.error)
            assertEquals("新", vm.uiState.value.search.items.single().name)
            assertFalse(vm.uiState.value.search.loadingMore)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun midnightAndZoneChangeRebuildRelativeDatesAndDiscardCursor() = runTest {
        val queries = mutableListOf<ExpenseSearchQuery>()
        val repo = object : ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int): ExpenseSearchPage {
                queries += query
                return page()
            }
        }
        val vm = vm(repo, StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle()
            vm.replaceSearchFilters(ExpenseSearchFilters(SearchDatePreset.TODAY)); advanceUntilIdle()
            val nextDate = LocalDate.of(2026, 10, 1)
            val zone = ZoneId.of("America/New_York")
            vm.onSearchEnvironmentChanged(nextDate, zone); advanceUntilIdle()
            assertEquals(nextDate.atStartOfDay(zone).toInstant().toEpochMilli(), queries.last().fromInclusiveMillis)
            assertNull(vm.uiState.value.search.nextCursor)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun oldEditorCannotWriteSameIdAfterLedgerEpochChanges() = runTest {
        val fake = FakeExpenseRepository().apply { homeSnapshot = homeSnapshot.copy(categories = listOf(category)) }
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        val repo = object : ExpenseRepositoryContract by fake {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int) = page()
        }
        val vm = vm(repo, StandardTestDispatcher(testScheduler), coordinator)
        try {
            vm.refresh(com.example.monthlyexpense.notification.NotificationListenerConnectionState.CONNECTED); advanceUntilIdle()
            vm.openSearch(); advanceUntilIdle(); vm.openSearchExpense(1)
            coordinator.setDurableRestorePending(true); coordinator.setDurableRestorePending(false)
            vm.updateExpense(1, ExpenseEditInput("过期编辑", "", "FOOD")); advanceUntilIdle()
            assertEquals(0, fake.updateExpenseCalls)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun backgroundRefreshKeepsEditSnapshotEvenIfResultDisappears() = runTest {
        var gone = false
        val repo = object : ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int) =
                if (gone) ExpenseSearchPage(emptyList(), null, false, ExpenseSearchSummary(0, 0)) else page()
        }
        val vm = vm(repo, StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle(); vm.openSearchExpense(1)
            gone = true; vm.onSearchDataChanged(setOf(DataDomain.LEDGER)); advanceUntilIdle()
            assertTrue(vm.uiState.value.search.items.isEmpty())
            assertEquals(1L, vm.uiState.value.search.editingExpense!!.expense.id)
            vm.dismissOverlay(); assertEquals(ExpenseOverlay.Search, vm.uiState.value.overlay)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun ledgerBroadcastReevaluatesDateAndZoneWithoutActivityResume() = runTest {
        var today = LocalDate.of(2026, 9, 30)
        var zone = ZoneId.of("Asia/Shanghai")
        val queries = mutableListOf<ExpenseSearchQuery>()
        val repo = object : ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int): ExpenseSearchPage {
                queries += query; return page()
            }
        }
        val vm = ExpenseViewModel(repo, FakeExpenseBackupManager(), SavedStateHandle(),
            StandardTestDispatcher(testScheduler), today = { today }, zoneId = { zone })
        try {
            vm.openSearch(); advanceUntilIdle()
            vm.replaceSearchFilters(ExpenseSearchFilters(SearchDatePreset.TODAY)); advanceUntilIdle()
            today = today.plusDays(1); zone = ZoneId.of("America/New_York")
            vm.onSearchDataChanged(setOf(DataDomain.LEDGER, DataDomain.SETTINGS)); advanceUntilIdle()
            assertEquals(today.atStartOfDay(zone).toInstant().toEpochMilli(), queries.last().fromInclusiveMillis)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun reopeningSearchCommitsAbandonedCompositionInsteadOfStalling() = runTest {
        val repo = object : ExpenseRepositoryContract by FakeExpenseRepository() {
            override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int) = page(name = query.keyword)
        }
        val vm = vm(repo, StandardTestDispatcher(testScheduler))
        try {
            vm.openSearch(); advanceUntilIdle(); vm.setSearchKeyword("cha", true)
            vm.closeSearch(); vm.openSearch(); advanceUntilIdle()
            assertFalse(vm.uiState.value.search.isComposing)
            assertEquals("cha", vm.uiState.value.search.items.single().name)
        } finally { vm.viewModelScope.cancel() }
    }
}
