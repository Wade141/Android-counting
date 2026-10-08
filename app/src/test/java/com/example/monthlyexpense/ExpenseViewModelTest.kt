package com.example.monthlyexpense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.monthlyexpense.backup.ExpenseBackupSettings
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundRestoreTicket
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.data.HistoryPage
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test fun pendingRestoreSettingsStillLoadsRestoredHomeAfterRestartAndBlocksSettingWrites() = runTest {
        val fake = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(expenseId = 83L) }
        val repository = object : ExpenseRepositoryContract by fake {
            override suspend fun loadHomeSnapshot(now: LocalDate, zoneId: java.time.ZoneId): ExpenseHomeSnapshot =
                error("Pending settings must use the read-only loader")
            override suspend fun loadReadOnlyHomeSnapshot(now: LocalDate, zoneId: java.time.ZoneId): ExpenseHomeSnapshot = fake.homeSnapshot
        }
        val vm = createViewModel(repository, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val coordinator = vm.coordinatorForTest()
        coordinator.setDurableRestorePending(true)
        vm.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.initialLoadComplete)
        assertEquals(listOf(83L), vm.uiState.value.expenses.map { it.id })
        assertNull(coordinator.mutationTicket())
        vm.setAutoBookkeepingEnabled(false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.autoBookkeeping.enabled)
        assertEquals(0, fake.autoWriteCalls)
        fake.homeSnapshot = sampleHomeSnapshot(expenseId = 84L)
        vm.refreshDomains(setOf(com.example.monthlyexpense.data.DataDomain.LEDGER))
        advanceUntilIdle()
        assertEquals(listOf(84L), vm.uiState.value.expenses.map { it.id })
    }

    @Test fun settingsRefreshAcrossMidnightRevalidatesLedgerAndSuccessfulRetryClearsFailedDomains() = runTest {
        var date = LocalDate.of(2026, 9, 22)
        val fake = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(expenseId = 1L) }
        val domainsSeen = mutableListOf<Set<com.example.monthlyexpense.data.DataDomain>>()
        var fail = false
        val repository = object : ExpenseRepositoryContract by fake {
            override suspend fun refreshHomeSnapshot(now: LocalDate, zoneId: java.time.ZoneId,
                previous: ExpenseHomeSnapshot, domains: Set<com.example.monthlyexpense.data.DataDomain>): ExpenseHomeSnapshot {
                domainsSeen += domains
                if (fail) throw IOException("temporary failure")
                return fake.loadHomeSnapshot(now, zoneId)
            }
        }
        val vm = createViewModel(repository, ioDispatcher = UnconfinedTestDispatcher(testScheduler), today = { date })
        vm.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        date = date.plusDays(1)
        val ledger = com.example.monthlyexpense.data.DataDomain.LEDGER
        val settings = com.example.monthlyexpense.data.DataDomain.SETTINGS
        val before = fake.loadHomeSnapshotCalls
        vm.refreshDomains(setOf(settings))
        advanceUntilIdle()
        assertTrue(fake.loadHomeSnapshotCalls > before)
        assertTrue(domainsSeen.isEmpty() || ledger in domainsSeen.last())
        domainsSeen.clear()
        fail = true
        vm.refreshDomains(setOf(settings))
        advanceUntilIdle()
        fail = false
        vm.refreshDomains(setOf(ledger))
        advanceUntilIdle()
        vm.refreshDomains(setOf(ledger))
        advanceUntilIdle()
        assertEquals(setOf(ledger, settings), domainsSeen[1])
        assertEquals(setOf(ledger), domainsSeen[2])
    }

    @Test
    fun selectedHistoryMonthKeepsItsTotalAcrossPagingAndRetryAndReopensAtMonths() = runTest {
        val month = YearMonth.of(2026, 8)
        val cursor = HistoryCursor(3000L, 201L)
        val months = listOf(com.example.monthlyexpense.data.HistoryMonth(month, 900L, 2))
        val repository = FakeExpenseRepository().apply {
            historyPages[null] = HistoryPage(emptyList(), null, false, months)
            monthlyHistoryPages[month to null] = HistoryPage(
                listOf(sampleHistoryExpense(201L, 3000L)), cursor, true, months)
            monthlyHistoryPages[month to cursor] = HistoryPage(
                listOf(sampleHistoryExpense(202L, 2000L)), null, false, months)
        }
        val viewModel = createViewModel(repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        viewModel.openHistory()
        advanceUntilIdle()
        assertEquals(months, viewModel.uiState.value.history.months)
        viewModel.selectHistoryMonth(month)
        advanceUntilIdle()
        assertEquals(listOf(201L), viewModel.uiState.value.history.items.map { it.id })
        assertEquals(900L, viewModel.uiState.value.history.months.single().totalCents)
        repository.loadHistoryPageError = IOException("retry")
        viewModel.loadMoreHistory()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.history.loadFailed)
        repository.loadHistoryPageError = null
        viewModel.retryHistoryLoad()
        advanceUntilIdle()
        assertEquals(listOf(201L, 202L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.hasMore)
        assertEquals(month, viewModel.uiState.value.history.selectedMonth)
        viewModel.dismissOverlay()
        viewModel.openHistory()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.history.selectedMonth)
        assertEquals(months, viewModel.uiState.value.history.months)
    }

    @Test
    fun switchingMonthsRejectsAnOlderInFlightPage() = runTest {
        val august = YearMonth.of(2026, 8)
        val july = YearMonth.of(2026, 7)
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            monthlyHistoryPages[august to null] = HistoryPage(listOf(sampleHistoryExpense(201L, 3000L)), null, false)
            monthlyHistoryPages[july to null] = HistoryPage(listOf(sampleHistoryExpense(202L, 2000L)), null, false)
            loadHistoryPageGates += gate
        }
        val viewModel = createViewModel(repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        viewModel.selectHistoryMonth(august)
        runCurrent()
        viewModel.selectHistoryMonth(july)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(july, viewModel.uiState.value.history.selectedMonth)
        assertEquals(listOf(202L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun refreshPublishesOneCoherentSnapshot() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 7L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        assertEquals(listOf(7L), viewModel.uiState.value.expenses.map { it.id })
        assertEquals(4_200L, viewModel.uiState.value.monthlyBudgetCents)
        assertEquals(700L, viewModel.uiState.value.dailyBudgetCents)
        assertEquals(340L, viewModel.uiState.value.todayTotalCents)
        assertEquals(true, viewModel.uiState.value.autoBookkeeping.enabled)
        assertEquals(false, viewModel.uiState.value.autoBookkeeping.weChatEnabled)
        assertTrue(viewModel.uiState.value.initialLoadComplete)
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            viewModel.uiState.value.notificationState
        )
    }

    @Test
    fun refreshFailureKeepsLastGoodStateAndEmitsOnce() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 9L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val before = viewModel.uiState.value
        repository.loadHomeSnapshotError = IOException("read failed")
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(1).toList(events)
        }

        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value)
        assertEquals(
            listOf(UiEvent.ShowMessage(UiMessage("页面刷新失败，请重试"))),
            events
        )
    }

    @Test
    fun initialHomeRefreshLoadsHomeWithoutHistory() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 101L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        assertEquals(1, repository.loadHomeSnapshotCalls)
        assertEquals(0, repository.loadHistoryPageCalls)
        assertEquals(listOf(101L), viewModel.uiState.value.expenses.map { it.id })
        assertFalse(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun openHistoryFirstTimeLoadsFirstPageOnce() = runTest {
        val firstCursor = HistoryCursor(spentAt = 3_000L, id = 103L)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 102L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(103L, 3_000L)),
                nextCursor = firstCursor,
                hasMore = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        viewModel.openHistory()
        assertEquals(ExpenseOverlay.History, viewModel.uiState.value.overlay)
        advanceUntilIdle()

        assertEquals(1, repository.loadHistoryPageCalls)
        assertEquals(listOf(null to HISTORY_PAGE_SIZE), repository.loadHistoryPageArguments)
        assertEquals(listOf(103L), viewModel.uiState.value.history.items.map { it.id })
        assertEquals(firstCursor, viewModel.uiState.value.history.nextCursor)
        assertTrue(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun closeAndReopenValidHistoryDoesNotLoadAgain() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 104L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(105L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        viewModel.dismissOverlay()
        viewModel.openHistory()
        advanceUntilIdle()

        assertEquals(ExpenseOverlay.History, viewModel.uiState.value.overlay)
        assertEquals(1, repository.loadHistoryPageCalls)
    }

    @Test
    fun loadMoreHistoryUsesCurrentCursorAndAppendsInOrder() = runTest {
        val cursor = HistoryCursor(spentAt = 2_000L, id = 107L)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 106L)
            historyPages[null] = HistoryPage(
                items = listOf(
                    sampleHistoryExpense(109L, 4_000L),
                    sampleHistoryExpense(108L, 3_000L)
                ),
                nextCursor = cursor,
                hasMore = true
            )
            historyPages[cursor] = HistoryPage(
                items = listOf(sampleHistoryExpense(107L, 2_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        viewModel.loadMoreHistory()
        advanceUntilIdle()

        assertEquals(
            listOf(null to HISTORY_PAGE_SIZE, cursor to HISTORY_PAGE_SIZE),
            repository.loadHistoryPageArguments
        )
        assertEquals(listOf(109L, 108L, 107L), viewModel.uiState.value.history.items.map { it.id })
        assertNull(viewModel.uiState.value.history.nextCursor)
        assertFalse(viewModel.uiState.value.history.hasMore)
    }

    @Test
    fun defensiveHistoryMergePublishesDuplicateIdsOnlyOnce() = runTest {
        val cursor = HistoryCursor(spentAt = 2_000L, id = 111L)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 110L)
            historyPages[null] = HistoryPage(
                items = listOf(
                    sampleHistoryExpense(113L, 4_000L),
                    sampleHistoryExpense(112L, 3_000L)
                ),
                nextCursor = cursor,
                hasMore = true
            )
            historyPages[cursor] = HistoryPage(
                items = listOf(
                    sampleHistoryExpense(112L, 3_000L),
                    sampleHistoryExpense(111L, 2_000L),
                    sampleHistoryExpense(111L, 2_000L)
                ),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        viewModel.loadMoreHistory()
        advanceUntilIdle()

        assertEquals(listOf(113L, 112L, 111L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun failedFirstPagePreservesExistingItemsAndRetryReplacesThem() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 114L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(115L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
            addCategoryResult = CategoryMutationResult.Success(homeSnapshot.categories.single())
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHistoryPageError = IOException("first page failed")

        viewModel.addCategory(CategoryInput("新分类", 1L))
        advanceUntilIdle()

        val failed = viewModel.uiState.value.history
        assertEquals(listOf(115L), failed.items.map { it.id })
        assertTrue(failed.initialLoadComplete)
        assertFalse(failed.loading)
        assertFalse(failed.loadingMore)
        assertTrue(failed.loadFailed)
        assertNull(failed.failedCursor)

        repository.loadHistoryPageError = null
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(116L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.retryHistoryLoad()
        advanceUntilIdle()

        assertEquals(listOf(116L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.loadFailed)
    }

    @Test
    fun failedLoadMorePreservesItemsAndCursorAndRetryUsesCapturedCursor() = runTest {
        val cursor = HistoryCursor(spentAt = 2_000L, id = 118L)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 117L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(119L, 3_000L)),
                nextCursor = cursor,
                hasMore = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHistoryPageError = IOException("load more failed")

        viewModel.loadMoreHistory()
        advanceUntilIdle()

        val failed = viewModel.uiState.value.history
        assertEquals(listOf(119L), failed.items.map { it.id })
        assertEquals(cursor, failed.nextCursor)
        assertFalse(failed.loadingMore)
        assertTrue(failed.loadFailed)
        assertEquals(cursor, failed.failedCursor)

        repository.loadHistoryPageError = null
        repository.historyPages[cursor] = HistoryPage(
            items = listOf(sampleHistoryExpense(118L, 2_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.retryHistoryLoad()
        advanceUntilIdle()

        assertEquals(cursor, repository.loadHistoryPageArguments.last().first)
        assertEquals(listOf(119L, 118L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun expenseAddWithUnloadedHistoryRefreshesOnlyHomeAndLeavesHistoryUnloaded() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 120L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(0, repository.loadHistoryPageCalls)
        assertFalse(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun currentExpenseMutationLeavesLoadedOpenHistoryUntouched() = runTest {
        val cursor = HistoryCursor(spentAt = 2_000L, id = 122L)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 121L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(123L, 3_000L)),
                nextCursor = cursor,
                hasMore = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(124L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(listOf(null), repository.loadHistoryPageArguments.map { it.first })
        assertEquals(listOf(123L), viewModel.uiState.value.history.items.map { it.id })
        assertEquals(ExpenseOverlay.History, viewModel.uiState.value.overlay)
    }

    @Test
    fun currentExpenseMutationsRefreshHomeWithoutReloadingLoadedHistory() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 133L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(134L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        advanceUntilIdle()
        viewModel.updateExpense(133L, ExpenseEditInput("早餐", "", "FOOD"))
        advanceUntilIdle()
        viewModel.deleteExpense(133L)
        advanceUntilIdle()

        assertEquals(4, repository.loadHomeSnapshotCalls)
        assertEquals(1, repository.loadHistoryPageCalls)
        assertEquals(listOf(134L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun budgetMutationRefreshesHomeWithoutReloadingLoadedHistory() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 135L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(136L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        viewModel.setBudget(BudgetKind.DAILY, 800L)
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(1, repository.loadHistoryPageCalls)
        assertEquals(listOf(136L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun categoryMutationsWithUnloadedHistoryRefreshHomeOnly() = runTest {
        val customCategory = ExpenseCategory(
            key = "custom-unloaded",
            name = "未加载历史",
            colorArgb = 1L,
            builtIn = false,
            sortOrder = 10
        )
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 137L)
            addCategoryResult = CategoryMutationResult.Success(customCategory)
            updateCategoryResult = CategoryMutationResult.Success(customCategory)
            deleteCategoryResult = CategoryDeleteResult.Deleted
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.History)

        viewModel.addCategory(CategoryInput("未加载历史", 1L))
        advanceUntilIdle()
        viewModel.updateCategory("FOOD", CategoryInput(null, 2L))
        advanceUntilIdle()
        viewModel.deleteCategory(customCategory.key)
        advanceUntilIdle()

        assertEquals(4, repository.loadHomeSnapshotCalls)
        assertEquals(0, repository.loadHistoryPageCalls)
        assertEquals(1, repository.addCategoryCalls)
        assertEquals(1, repository.updateCategoryCalls)
        assertEquals(1, repository.deleteCategoryCalls)
        assertFalse(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun categoryMutationsWithLoadedHistoryRefreshHomeAndFirstHistoryPage() = runTest {
        val customCategory = ExpenseCategory(
            key = "custom-loaded",
            name = "已加载历史",
            colorArgb = 2L,
            builtIn = false,
            sortOrder = 10
        )
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 138L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(139L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
            addCategoryResult = CategoryMutationResult.Success(customCategory)
            updateCategoryResult = CategoryMutationResult.Success(customCategory)
            deleteCategoryResult = CategoryDeleteResult.Deleted
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(140L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )

        viewModel.addCategory(CategoryInput("已加载历史", 2L))
        advanceUntilIdle()
        viewModel.updateCategory("FOOD", CategoryInput(null, 3L))
        advanceUntilIdle()
        viewModel.deleteCategory(customCategory.key)
        advanceUntilIdle()

        assertEquals(4, repository.loadHomeSnapshotCalls)
        assertEquals(4, repository.loadHistoryPageCalls)
        assertEquals(1, repository.addCategoryCalls)
        assertEquals(1, repository.updateCategoryCalls)
        assertEquals(1, repository.deleteCategoryCalls)
        assertEquals(listOf(140L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun committedCategoryMutationWithFailedAllHomeReadLeavesHistoryRetryable() = runTest {
        val customCategory = ExpenseCategory(
            key = "custom-failed-refresh",
            name = "刷新失败分类",
            colorArgb = 2L,
            builtIn = false,
            sortOrder = 10
        )
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 154L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(155L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
            addCategoryResult = CategoryMutationResult.Success(customCategory)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHomeSnapshotError = IOException("required ALL home read failed")

        viewModel.addCategory(CategoryInput(customCategory.name, customCategory.colorArgb))
        advanceUntilIdle()

        val failedHistory = viewModel.uiState.value.history
        assertEquals(listOf(155L), failedHistory.items.map { it.id })
        assertFalse(failedHistory.loading)
        assertFalse(failedHistory.loadingMore)
        assertTrue(failedHistory.loadFailed)
        assertNull(failedHistory.failedCursor)

        repository.loadHomeSnapshotError = null
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(156L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.retryHistoryLoad()
        advanceUntilIdle()

        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(listOf(156L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.loadFailed)
    }

    @Test
    fun committedHistoryExpenseMutationWithFailedAllHomeReadLeavesHistoryRetryable() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 157L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(158L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHomeSnapshotError = IOException("required ALL home read failed")

        viewModel.updateExpense(158L, ExpenseEditInput("历史修改", "", "FOOD"))
        advanceUntilIdle()

        val failedHistory = viewModel.uiState.value.history
        assertEquals(listOf(158L), failedHistory.items.map { it.id })
        assertTrue(failedHistory.loadFailed)
        assertNull(failedHistory.failedCursor)

        repository.loadHomeSnapshotError = null
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(159L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.retryHistoryLoad()
        advanceUntilIdle()

        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(listOf(159L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.loadFailed)
    }

    @Test
    fun historyExpenseUpdateAndDeleteRefreshHomeAndFirstHistoryPage() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 141L)
            historyPages[null] = HistoryPage(
                items = listOf(
                    sampleHistoryExpense(143L, 4_000L),
                    sampleHistoryExpense(142L, 3_000L)
                ),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        viewModel.updateExpense(143L, ExpenseEditInput("历史修改", "", "FOOD"))
        advanceUntilIdle()
        viewModel.deleteExpense(142L)
        advanceUntilIdle()

        assertEquals(3, repository.loadHomeSnapshotCalls)
        assertEquals(3, repository.loadHistoryPageCalls)
        assertEquals(1, repository.updateExpenseCalls)
        assertEquals(1, repository.deleteExpenseCalls)
    }

    @Test
    fun restoreRefreshesHomeAndLoadedHistory() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 144L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(145L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = ExpenseBackupSettings(
                dailyBudgetCents = 700L,
                autoBookkeepingEnabled = true,
                weChatEnabled = false,
                alipayEnabled = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()

        viewModel.confirmRestore()
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(1, backupManager.restoreCalls)
    }

    @Test
    fun monthRolloverPromotesExternalHomeRefreshWhenHistoryIsLoaded() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 146L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(147L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()

        currentDate = LocalDate.of(2026, 10, 1)
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(2, repository.loadHistoryPageCalls)
        assertTrue(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun mutationHomeRefreshAfterMonthRolloverReloadsLoadedHistory() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 160L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(161L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(162L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )

        currentDate = LocalDate.of(2026, 10, 1)
        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "跨月午餐", ""))
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(listOf(162L), viewModel.uiState.value.history.items.map { it.id })
    }

    @Test
    fun externalHomeQueuedBeforeMonthBoundaryReloadsHistoryWhenExecutedAfterBoundary() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val activeRefreshGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 163L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(164L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += activeRefreshGate

        viewModel.refresh(NotificationListenerConnectionState.DISCONNECTED)
        runCurrent()
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        runCurrent()

        currentDate = LocalDate.of(2026, 10, 1)
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(165L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        activeRefreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, repository.loadHomeSnapshotCalls)
        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(listOf(165L), viewModel.uiState.value.history.items.map { it.id })
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            viewModel.uiState.value.notificationState
        )
    }

    @Test
    fun monthRolloverKeepsExternalRefreshHomeOnlyWhenHistoryIsUnloaded() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 148L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        currentDate = LocalDate.of(2026, 10, 1)
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(0, repository.loadHistoryPageCalls)
        assertFalse(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun unloadedMonthRolloverInvalidatesGatedFirstHistoryPageWithoutQueryingHistory() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val oldPageGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 151L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(152L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        repository.loadHistoryPageGates += oldPageGate

        viewModel.openHistory()
        runCurrent()
        assertEquals(1, repository.loadHistoryPageCalls)
        assertFalse(viewModel.uiState.value.history.initialLoadComplete)
        assertTrue(viewModel.uiState.value.history.loading)

        currentDate = LocalDate.of(2026, 10, 1)
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        runCurrent()

        assertEquals(1, repository.loadHistoryPageCalls)

        oldPageGate.complete(Unit)
        advanceUntilIdle()

        val invalidatedHistory = viewModel.uiState.value.history
        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(1, repository.loadHistoryPageCalls)
        assertTrue(invalidatedHistory.items.isEmpty())
        assertFalse(invalidatedHistory.initialLoadComplete)
        assertFalse(invalidatedHistory.loading)
        assertFalse(invalidatedHistory.loadingMore)

        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(153L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.openHistory()
        advanceUntilIdle()

        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(listOf(153L), viewModel.uiState.value.history.items.map { it.id })
        assertTrue(viewModel.uiState.value.history.initialLoadComplete)
    }

    @Test
    fun overlappingMutationRefreshAndPaymentSignalRunOneTrailingReadWithCommittedRecord() = runTest {
        val activeRefreshGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 149L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += activeRefreshGate

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        assertEquals(2, repository.loadHomeSnapshotCalls)

        repository.homeSnapshot = sampleHomeSnapshot(expenseId = 150L)
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        runCurrent()
        activeRefreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, repository.loadHomeSnapshotCalls)
        assertEquals(listOf(150L), viewModel.uiState.value.expenses.map { it.id })
        assertFalse(viewModel.uiState.value.pending.entrySaving)
    }

    @Test
    fun monthResetGenerationSuppressesLateOldLoadMorePage() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val cursor = HistoryCursor(spentAt = 2_000L, id = 126L)
        val oldPageGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 125L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(127L, 3_000L)),
                nextCursor = cursor,
                hasMore = true
            )
            historyPages[cursor] = HistoryPage(
                items = listOf(sampleHistoryExpense(126L, 2_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHistoryPageGates += oldPageGate

        viewModel.loadMoreHistory()
        runCurrent()
        currentDate = LocalDate.of(2026, 10, 1)
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(128L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        runCurrent()
        oldPageGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(128L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.items.any { it.id == 126L })
    }

    @Test
    fun failedMonthResetClearsOrphanedLoadingFlagsAndRequiresFirstPageRetry() = runTest {
        var currentDate = LocalDate.of(2026, 9, 30)
        val cursor = HistoryCursor(spentAt = 2_000L, id = 130L)
        val oldPageGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 129L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(131L, 3_000L)),
                nextCursor = cursor,
                hasMore = true
            )
            historyPages[cursor] = HistoryPage(
                items = listOf(sampleHistoryExpense(130L, 2_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { currentDate }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        repository.loadHistoryPageGates += oldPageGate

        viewModel.loadMoreHistory()
        runCurrent()
        assertTrue(viewModel.uiState.value.history.loadingMore)

        currentDate = LocalDate.of(2026, 10, 1)
        repository.loadHomeSnapshotError = IOException("month reset failed")
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        runCurrent()
        oldPageGate.complete(Unit)
        advanceUntilIdle()

        val failedHistory = viewModel.uiState.value.history
        assertEquals(listOf(131L), failedHistory.items.map { it.id })
        assertEquals(cursor, failedHistory.nextCursor)
        assertTrue(failedHistory.hasMore)
        assertTrue(failedHistory.initialLoadComplete)
        assertFalse(failedHistory.loading)
        assertFalse(failedHistory.loadingMore)
        assertTrue(failedHistory.loadFailed)
        assertNull(failedHistory.failedCursor)

        repository.loadHomeSnapshotError = null
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(132L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.retryHistoryLoad()
        advanceUntilIdle()

        assertEquals(3, repository.loadHistoryPageCalls)
        assertEquals(listOf(132L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.loading)
        assertFalse(viewModel.uiState.value.history.loadingMore)
        assertFalse(viewModel.uiState.value.history.loadFailed)
    }

    @Test
    fun restoreGenerationSuppressesLateOldHistoryPage() = runTest {
        val cursor = HistoryCursor(spentAt = 2_000L, id = 130L)
        val oldPageGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 129L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(131L, 3_000L)),
                nextCursor = cursor,
                hasMore = true
            )
            historyPages[cursor] = HistoryPage(
                items = listOf(sampleHistoryExpense(130L, 2_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = null
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        repository.loadHistoryPageGates += oldPageGate

        viewModel.loadMoreHistory()
        runCurrent()
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(132L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.confirmRestore()
        oldPageGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(132L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.items.any { it.id == 130L })
    }

    @Test
    fun oneHundredOverlappingRefreshesRunOneActiveAndOneTrailingRead() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 90L)
            loadHomeSnapshotGates += firstGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        viewModel.refresh(NotificationListenerConnectionState.DISCONNECTED)
        runCurrent()
        repeat(100) {
            viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        }

        firstGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            viewModel.uiState.value.notificationState
        )
    }

    @Test
    fun firstCallFailureStillDrains_queued_refresh() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 91L)
            loadHomeSnapshotGates += firstGate
        }
        var failFirstCall = true
        val firstCallFailingRepository = object : ExpenseRepositoryContract by repository {
            override suspend fun loadHomeSnapshot(
                now: LocalDate,
                zoneId: java.time.ZoneId
            ): ExpenseHomeSnapshot {
                val snapshot = repository.loadHomeSnapshot(now, zoneId)
                if (failFirstCall) {
                    failFirstCall = false
                    throw IOException("first refresh failed")
                }
                return snapshot
            }
        }
        val viewModel = createViewModel(
            repository = firstCallFailingRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        viewModel.refresh(NotificationListenerConnectionState.DISCONNECTED)
        runCurrent()
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)

        firstGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertEquals(listOf(91L), viewModel.uiState.value.expenses.map { it.id })
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            viewModel.uiState.value.notificationState
        )
    }

    @Test
    fun mutationPendingWaitsForIts_queued_refreshBehindExternalRefresh() = runTest {
        val mutationGate = CompletableDeferred<Unit>()
        val externalRefreshGate = CompletableDeferred<Unit>()
        val mergedRefreshGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 92L)
            addExpenseGates += mutationGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += externalRefreshGate
        repository.loadHomeSnapshotGates += mergedRefreshGate

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        viewModel.refresh(NotificationListenerConnectionState.DISCONNECTED)
        runCurrent()

        mutationGate.complete(Unit)
        runCurrent()

        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertTrue(viewModel.uiState.value.pending.entrySaving)

        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        externalRefreshGate.complete(Unit)
        runCurrent()

        assertEquals(3, repository.loadHomeSnapshotCalls)
        assertTrue(viewModel.uiState.value.pending.entrySaving)

        mergedRefreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, repository.loadHomeSnapshotCalls)
        assertFalse(viewModel.uiState.value.pending.entrySaving)
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            viewModel.uiState.value.notificationState
        )
    }

    @Test
    fun newerMutationRefreshCannotOverwritePendingExternalNotificationSample() = runTest {
        val mutationGate = CompletableDeferred<Unit>()
        val activeRefreshGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 166L)
            addExpenseGates += mutationGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.NOT_AUTHORIZED)
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += activeRefreshGate

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        viewModel.refresh(NotificationListenerConnectionState.DISCONNECTED)
        runCurrent()
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        runCurrent()

        mutationGate.complete(Unit)
        runCurrent()
        activeRefreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, repository.loadHomeSnapshotCalls)
        assertFalse(viewModel.uiState.value.pending.entrySaving)
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            viewModel.uiState.value.notificationState
        )
    }

    @Test
    fun submitNewExpenseSavesRefreshesAndClosesEntry() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 18L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.Entry)

        viewModel.submitNewExpense(
            NewExpenseInput(
                amountCents = 100L,
                categoryKey = "FOOD",
                name = "午餐",
                note = ""
            )
        )
        advanceUntilIdle()

        assertEquals(1, repository.addExpenseCalls)
        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertNull(viewModel.uiState.value.overlay)
        assertFalse(viewModel.uiState.value.pending.entrySaving)
    }

    @Test
    fun submitNewExpenseSuppressesDuplicateWhilePending() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 19L)
            addExpenseGates += gate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val input = NewExpenseInput(100L, "FOOD", "午餐", "")

        viewModel.submitNewExpense(input)
        viewModel.submitNewExpense(input)
        runCurrent()

        assertEquals(1, repository.addExpenseCalls)
        assertTrue(viewModel.uiState.value.pending.entrySaving)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.pending.entrySaving)
    }

    @Test
    fun staleExpenseEpochDoesNotPersistOrEmitAnEvent() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 20L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        requireNotNull(viewModel.coordinatorForTest().beginRestore())
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        viewModel.updateExpense(20L, ExpenseEditInput("", "", "FOOD"))
        viewModel.setBudget(BudgetKind.MONTHLY, -1L)
        advanceUntilIdle()

        assertEquals(0, repository.addExpenseCalls)
        assertEquals(0, repository.updateExpenseCalls)
        assertEquals(0, repository.monthlyBudgetCalls)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun expenseInputsAreRevalidatedBeforePersistence() = runTest {
        val repository = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(37L) }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(7).toList(events)
        }

        listOf(
            NewExpenseInput(0L, "FOOD", "午餐", ""),
            NewExpenseInput(MoneyLimits.MAX_CENTS + 1L, "FOOD", "午餐", ""),
            NewExpenseInput(100L, "FOOD", " ", ""),
            NewExpenseInput(100L, "FOOD", "午餐", "x".repeat(101)),
            NewExpenseInput(100L, "MISSING", "午餐", "")
        ).forEach(viewModel::submitNewExpense)
        viewModel.updateExpense(37L, ExpenseEditInput("午餐", "x".repeat(101), "FOOD"))
        viewModel.updateExpense(37L, ExpenseEditInput("午餐", "", "MISSING"))
        advanceUntilIdle()

        assertEquals(0, repository.addExpenseCalls)
        assertEquals(0, repository.updateExpenseCalls)
        assertEquals(
            List(5) { UiEvent.ShowMessage(UiMessage("请检查金额、名称和分类")) } +
                List(2) { UiEvent.ShowMessage(UiMessage("名称不能为空，备注最多 100 个字")) },
            events
        )
    }

    @Test
    fun committedExpenseWithRefreshFailureEmitsSavedButRefreshFailed() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 21L)
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        repository.loadHomeSnapshotError = IOException("refresh failed")
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        advanceUntilIdle()

        assertEquals(
            UiEvent.ShowMessage(UiMessage("操作已保存，但页面刷新失败")),
            event.await()
        )
    }

    @Test
    fun categoryMutationResultsKeepExactExistingMessagesAndCloseOnlyOnSuccess() = runTest {
        val category = sampleHomeSnapshot(expenseId = 22L).categories.single()
        val repository = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(expenseId = 22L) }
        val viewModel = createViewModel(repository, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(4).toList(events)
        }

        viewModel.showCategoryEditor(CategoryEditorTarget.New)
        repository.addCategoryResult = CategoryMutationResult.DuplicateName
        viewModel.addCategory(CategoryInput("  餐饮  ", 1L))
        advanceUntilIdle()
        assertEquals(CategoryEditorTarget.New, viewModel.uiState.value.categoryManagement.editor)
        repository.addCategoryResult = CategoryMutationResult.InvalidName
        viewModel.addCategory(CategoryInput("", 1L))
        advanceUntilIdle()
        repository.updateCategoryResult = CategoryMutationResult.BuiltInNameLocked
        viewModel.updateCategory(category.key, CategoryInput(null, 1L))
        advanceUntilIdle()
        repository.updateCategoryResult = CategoryMutationResult.NotFound
        viewModel.updateCategory("MISSING", CategoryInput("x", 1L))
        advanceUntilIdle()

        assertEquals(
            listOf(
                UiEvent.ShowMessage(UiMessage("分类名称已存在")),
                UiEvent.ShowMessage(UiMessage("分类名称应为 1 至 20 个字符")),
                UiEvent.ShowMessage(UiMessage("内置分类名称不能修改")),
                UiEvent.ShowMessage(UiMessage("分类不存在"))
            ),
            events
        )
    }

    @Test
    fun successfulCategoryMutationTrimsCustomNameAndClosesMatchingEditor() = runTest {
        val category = sampleHomeSnapshot(expenseId = 23L).categories.single()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 23L)
            addCategoryResult = CategoryMutationResult.Success(category)
        }
        val viewModel = createViewModel(repository, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showCategoryEditor(CategoryEditorTarget.New)

        viewModel.addCategory(CategoryInput("  自定义  ", 1L))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.categoryManagement.editor)
        assertEquals(1, repository.addCategoryCalls)
    }

    @Test
    fun categoryDeleteResultsKeepExactExistingMessagesAndCloseOnlyAfterDeletion() = runTest {
        val category = sampleHomeSnapshot(expenseId = 24L).categories.single()
        val repository = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(expenseId = 24L) }
        val viewModel = createViewModel(repository, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(4).toList(events)
        }

        viewModel.requestCategoryDelete(category.key)
        repository.deleteCategoryResult = CategoryDeleteResult.InUse(2)
        viewModel.deleteCategory(category.key)
        advanceUntilIdle()
        assertEquals(category.key, viewModel.uiState.value.categoryManagement.deleteTargetKey)
        repository.deleteCategoryResult = CategoryDeleteResult.BuiltInLocked
        viewModel.deleteCategory(category.key)
        advanceUntilIdle()
        repository.deleteCategoryResult = CategoryDeleteResult.NotFound
        viewModel.deleteCategory(category.key)
        advanceUntilIdle()
        repository.deleteCategoryResult = CategoryDeleteResult.Deleted
        viewModel.deleteCategory(category.key)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.categoryManagement.deleteTargetKey)
        assertEquals(
            listOf(
                UiEvent.ShowMessage(UiMessage("该分类仍有 2 条消费记录，请先编辑这些记录并更改分类")),
                UiEvent.ShowMessage(UiMessage("内置分类不能删除")),
                UiEvent.ShowMessage(UiMessage("分类不存在")),
                UiEvent.ShowMessage(UiMessage("分类“餐饮”已删除"))
            ),
            events
        )
    }

    @Test
    fun budgetUsesTodayMonthAndDailyStorageAndRejectsInvalidAmounts() = runTest {
        val repository = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(expenseId = 25L) }
        var savedMonth: YearMonth? = null
        val recordingRepository = object : ExpenseRepositoryContract by repository {
            override suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean {
                savedMonth = month
                return repository.setMonthlyBudget(month, amountCents)
            }
        }
        val viewModel = ExpenseViewModel(
            repository = recordingRepository,
            backupManager = FakeExpenseBackupManager(),
            savedStateHandle = SavedStateHandle(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { LocalDate.of(2026, 8, 29) }
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(2).toList(events)
        }

        viewModel.setBudget(BudgetKind.MONTHLY, 500L)
        advanceUntilIdle()
        viewModel.setBudget(BudgetKind.DAILY, 300L)
        advanceUntilIdle()
        viewModel.setBudget(BudgetKind.MONTHLY, -1L)
        viewModel.setBudget(BudgetKind.DAILY, MoneyLimits.MAX_CENTS + 1)
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 8), savedMonth)
        assertEquals(1, repository.monthlyBudgetCalls)
        assertEquals(1, repository.dailyBudgetCalls)
        assertEquals(
            listOf(
                UiEvent.ShowMessage(UiMessage("预算金额无效")),
                UiEvent.ShowMessage(UiMessage("预算金额无效"))
            ),
            events
        )
    }

    @Test
    fun budgetSuppressesDuplicatePendingWrite() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 26L)
            dailyBudgetGates += gate
        }
        val viewModel = createViewModel(repository, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        viewModel.setBudget(BudgetKind.DAILY, 100L)
        viewModel.setBudget(BudgetKind.DAILY, 200L)
        runCurrent()

        assertEquals(1, repository.dailyBudgetCalls)
        assertTrue(viewModel.uiState.value.pending.budgetSaving)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.pending.budgetSaving)
    }

    @Test
    fun updateExpenseTrimsValuesRefreshesAndClosesMatchingOverlay() = runTest {
        val repository = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(27L) }
        var captured: List<Any?>? = null
        val recordingRepository = object : ExpenseRepositoryContract by repository {
            override suspend fun updateExpense(
                id: Long,
                name: String,
                note: String,
                categoryKey: String,
                date: LocalDate?,
                time: java.time.LocalTime?
            ): Boolean {
                captured = listOf(id, name, note, categoryKey, date, time)
                return repository.updateExpense(id, name, note, categoryKey, date, time)
            }
        }
        val viewModel = createViewModel(
            recordingRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.EditExpense(27L))

        viewModel.updateExpense(27L, ExpenseEditInput("  午餐  ", "  备注  ", "FOOD", LocalDate.of(2026, 7, 12), java.time.LocalTime.of(14, 35)))
        advanceUntilIdle()

        assertEquals(listOf(27L, "午餐", "备注", "FOOD", LocalDate.of(2026, 7, 12), java.time.LocalTime.of(14, 35)), captured)
        assertEquals(1, repository.updateExpenseCalls)
        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertNull(viewModel.uiState.value.overlay)
        assertFalse(viewModel.uiState.value.pending.expenseEditSaving)
    }

    @Test
    fun updateExpenseRejectsInvalidInputAndRepositoryRejectionKeepsEditorOpen() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(28L)
            updateExpenseResult = false
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.EditExpense(28L))
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(2).toList(events)
        }

        viewModel.updateExpense(28L, ExpenseEditInput("", "", "FOOD"))
        viewModel.updateExpense(28L, ExpenseEditInput("午餐", "", "FOOD"))
        advanceUntilIdle()

        assertEquals(1, repository.updateExpenseCalls)
        assertEquals(ExpenseOverlay.EditExpense(28L), viewModel.uiState.value.overlay)
        assertEquals(
            listOf(
                UiEvent.ShowMessage(UiMessage("名称不能为空，备注最多 100 个字")),
                UiEvent.ShowMessage(UiMessage("名称不能为空，备注最多 100 个字"))
            ),
            events
        )
    }

    @Test
    fun deleteExpenseUpdatesDisplayedListsBeforeRefreshing() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply { homeSnapshot = sampleHomeSnapshot(29L) }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += refreshGate

        viewModel.deleteExpense(29L)
        runCurrent()

        assertEquals(1, repository.deleteExpenseCalls)
        assertTrue(viewModel.uiState.value.expenses.isEmpty())
        assertTrue(viewModel.uiState.value.pending.expenseEditSaving)

        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.pending.expenseEditSaving)
    }

    @Test
    fun rejectedQueuedExpenseMutationIsSilentAndNeverReachesRepository() = runTest {
        val lockGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(30L)
            autoWriteGates += lockGate
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.setAutoBookkeepingEnabled(false)
        runCurrent()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        assertTrue(viewModel.uiState.value.pending.entrySaving)
        requireNotNull(viewModel.coordinatorForTest().beginRestore())
        lockGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, repository.addExpenseCalls)
        assertFalse(viewModel.uiState.value.pending.entrySaving)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun categoryWritesTrimCustomNamesAndForceBuiltInNameToNull() = runTest {
        val builtIn = sampleHomeSnapshot(31L).categories.single()
        val custom = ExpenseCategory(
            key = "custom-1",
            name = "旧名称",
            colorArgb = 2L,
            builtIn = false,
            sortOrder = 10
        )
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(31L).copy(categories = listOf(builtIn, custom))
            addCategoryResult = CategoryMutationResult.Success(custom)
            updateCategoryResult = CategoryMutationResult.Success(custom)
        }
        var addedName: String? = null
        val updatedNames = mutableListOf<Pair<String, String?>>()
        val recordingRepository = object : ExpenseRepositoryContract by repository {
            override suspend fun addCustomCategory(
                name: String,
                colorArgb: Long
            ): CategoryMutationResult {
                addedName = name
                return repository.addCustomCategory(name, colorArgb)
            }

            override suspend fun updateCategory(
                categoryKey: String,
                name: String?,
                colorArgb: Long
            ): CategoryMutationResult {
                updatedNames += categoryKey to name
                return repository.updateCategory(categoryKey, name, colorArgb)
            }
        }
        val viewModel = createViewModel(
            recordingRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        viewModel.showCategoryEditor(CategoryEditorTarget.New)
        viewModel.addCategory(CategoryInput("  新分类  ", 3L))
        advanceUntilIdle()
        viewModel.showCategoryEditor(CategoryEditorTarget.Existing(builtIn.key))
        viewModel.updateCategory(builtIn.key, CategoryInput("不得写入", 4L))
        advanceUntilIdle()
        viewModel.showCategoryEditor(CategoryEditorTarget.Existing(custom.key))
        viewModel.updateCategory(custom.key, CategoryInput("  新名称  ", 5L))
        advanceUntilIdle()

        assertEquals("新分类", addedName)
        assertEquals(listOf(builtIn.key to null, custom.key to "新名称"), updatedNames)
        assertNull(viewModel.uiState.value.categoryManagement.editor)
    }

    @Test
    fun categorySaveSuppressesDuplicateAndConcurrentDelete() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(32L)
            addCategoryGates += gate
            addCategoryResult = CategoryMutationResult.Success(homeSnapshot.categories.single())
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        viewModel.addCategory(CategoryInput("新分类", 1L))
        viewModel.addCategory(CategoryInput("重复提交", 2L))
        viewModel.deleteCategory("FOOD")
        runCurrent()

        assertEquals(1, repository.addCategoryCalls)
        assertEquals(0, repository.deleteCategoryCalls)
        assertTrue(viewModel.uiState.value.pending.categorySaving)
        assertFalse(viewModel.uiState.value.pending.categoryDeleting)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.pending.categorySaving)
    }

    @Test
    fun budgetRepositoryRejectionAndFailureKeepDialogAndUseExistingMessages() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(33L)
            monthlyBudgetResult = false
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(2).toList(events)
        }

        viewModel.showOverlay(ExpenseOverlay.EditBudget(BudgetKind.MONTHLY))
        viewModel.setBudget(BudgetKind.MONTHLY, 500L)
        advanceUntilIdle()
        repository.dailyBudgetError = IOException("write failed")
        viewModel.showOverlay(ExpenseOverlay.EditBudget(BudgetKind.DAILY))
        viewModel.setBudget(BudgetKind.DAILY, 300L)
        advanceUntilIdle()

        assertEquals(ExpenseOverlay.EditBudget(BudgetKind.DAILY), viewModel.uiState.value.overlay)
        assertEquals(
            listOf(
                UiEvent.ShowMessage(UiMessage("预算金额无效")),
                UiEvent.ShowMessage(UiMessage("预算保存失败，请重试"))
            ),
            events
        )
        assertFalse(viewModel.uiState.value.pending.budgetSaving)
    }

    @Test
    fun cancelledExpenseMutationIsSilentAndClearsPending() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(34L)
            addExpenseGates += gate
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        assertTrue(viewModel.uiState.value.pending.entrySaving)

        viewModel.viewModelScope.cancel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pending.entrySaving)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun cancelledCategoryAndBudgetMutationsAreSilentAndClearPending() = runTest {
        val categoryGate = CompletableDeferred<Unit>()
        val categoryRepository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(38L)
            addCategoryGates += categoryGate
        }
        val categoryViewModel = createViewModel(
            categoryRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        categoryViewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val categoryEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            categoryViewModel.events.first()
        }
        categoryViewModel.addCategory(CategoryInput("新分类", 1L))
        runCurrent()
        categoryViewModel.viewModelScope.cancel()
        advanceUntilIdle()

        val budgetGate = CompletableDeferred<Unit>()
        val budgetRepository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(39L)
            dailyBudgetGates += budgetGate
        }
        val budgetViewModel = createViewModel(
            budgetRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        budgetViewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val budgetEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            budgetViewModel.events.first()
        }
        budgetViewModel.setBudget(BudgetKind.DAILY, 100L)
        runCurrent()
        budgetViewModel.viewModelScope.cancel()
        advanceUntilIdle()

        assertFalse(categoryViewModel.uiState.value.pending.categorySaving)
        assertFalse(budgetViewModel.uiState.value.pending.budgetSaving)
        assertFalse(categoryEvent.isCompleted)
        assertFalse(budgetEvent.isCompleted)
        categoryEvent.cancel()
        budgetEvent.cancel()
    }

    @Test
    fun expenseMutationFailuresUseOperationSpecificMessages() = runTest {
        val updateRepository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(35L)
            updateExpenseError = IOException("update failed")
        }
        val updateViewModel = createViewModel(
            updateRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        updateViewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val updateEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            updateViewModel.events.first()
        }
        updateViewModel.updateExpense(35L, ExpenseEditInput("午餐", "", "FOOD"))
        advanceUntilIdle()

        val deleteRepository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(36L)
            deleteExpenseError = IOException("delete failed")
        }
        val deleteViewModel = createViewModel(
            deleteRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        deleteViewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val deleteEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            deleteViewModel.events.first()
        }
        deleteViewModel.deleteExpense(36L)
        advanceUntilIdle()

        assertEquals(UiEvent.ShowMessage(UiMessage("保存失败，请重试")), updateEvent.await())
        assertEquals(UiEvent.ShowMessage(UiMessage("删除失败，请重试")), deleteEvent.await())
        assertFalse(updateViewModel.uiState.value.pending.expenseEditSaving)
        assertFalse(deleteViewModel.uiState.value.pending.expenseEditSaving)
    }

    @Test
    fun completedEntryMutationDoesNotCloseReplacementOverlay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(40L)
            addExpenseGates += gate
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.Entry)

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        viewModel.showOverlay(ExpenseOverlay.History)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ExpenseOverlay.History, viewModel.uiState.value.overlay)
    }

    @Test
    fun completedExpenseEditDoesNotCloseDifferentExpenseOverlay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(41L)
            updateExpenseGates += gate
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.EditExpense(41L))

        viewModel.updateExpense(41L, ExpenseEditInput("午餐", "", "FOOD"))
        runCurrent()
        viewModel.showOverlay(ExpenseOverlay.EditExpense(99L))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ExpenseOverlay.EditExpense(99L), viewModel.uiState.value.overlay)
    }

    @Test
    fun completedBudgetMutationClosesOnlyMatchingBudgetOverlay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(42L)
            dailyBudgetGates += gate
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.EditBudget(BudgetKind.MONTHLY))

        viewModel.setBudget(BudgetKind.MONTHLY, 500L)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.overlay)

        viewModel.showOverlay(ExpenseOverlay.EditBudget(BudgetKind.DAILY))
        viewModel.setBudget(BudgetKind.DAILY, 300L)
        runCurrent()
        viewModel.showOverlay(ExpenseOverlay.History)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ExpenseOverlay.History, viewModel.uiState.value.overlay)
    }

    @Test
    fun validationFailureIsSilentWhenRestoreMakesCapturedTicketStale() = runTest {
        val validationEntered = CountDownLatch(1)
        val releaseValidation = CountDownLatch(1)
        val baseSnapshot = sampleHomeSnapshot(43L)
        val blockingCategories = object : AbstractList<ExpenseCategory>() {
            override val size: Int = 1

            override fun get(index: Int): ExpenseCategory {
                validationEntered.countDown()
                check(releaseValidation.await(1, TimeUnit.SECONDS))
                return baseSnapshot.categories.single()
            }
        }
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = baseSnapshot.copy(categories = blockingCategories)
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }
        var submitFailure: Throwable? = null
        val submitThread = thread {
            try {
                viewModel.submitNewExpense(
                    NewExpenseInput(100L, "MISSING", "午餐", "")
                )
            } catch (error: Throwable) {
                submitFailure = error
            }
        }
        assertTrue(validationEntered.await(1, TimeUnit.SECONDS))

        requireNotNull(viewModel.coordinatorForTest().beginRestore())
        releaseValidation.countDown()
        submitThread.join()
        advanceUntilIdle()

        assertNull(submitFailure)
        assertEquals(0, repository.addExpenseCalls)
        assertFalse(viewModel.uiState.value.pending.entrySaving)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun malformedDuplicatesAreSilentWhileMatchingMutationIsPending() = runTest {
        val addGate = CompletableDeferred<Unit>()
        val updateGate = CompletableDeferred<Unit>()
        val budgetGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(44L)
            addExpenseGates += addGate
            updateExpenseGates += updateGate
            dailyBudgetGates += budgetGate
        }
        val viewModel = createViewModel(
            repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        viewModel.submitNewExpense(NewExpenseInput(0L, "MISSING", "", ""))

        viewModel.updateExpense(44L, ExpenseEditInput("午餐", "", "FOOD"))
        runCurrent()
        viewModel.updateExpense(44L, ExpenseEditInput("", "", "MISSING"))

        viewModel.setBudget(BudgetKind.DAILY, 100L)
        runCurrent()
        viewModel.setBudget(BudgetKind.DAILY, -1L)

        assertFalse(event.isCompleted)

        addGate.complete(Unit)
        updateGate.complete(Unit)
        budgetGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.addExpenseCalls)
        assertEquals(1, repository.updateExpenseCalls)
        assertEquals(1, repository.dailyBudgetCalls)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun latestAutoSettingWriteWins() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 10L)
            autoWriteGates += firstGate
            autoWriteGates += secondGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()

        viewModel.setAutoBookkeepingEnabled(false)
        runCurrent()
        viewModel.setAutoBookkeepingEnabled(true)

        assertTrue(viewModel.uiState.value.autoBookkeeping.enabled)
        assertTrue(viewModel.uiState.value.autoBookkeeping.enabledWritePending)

        firstGate.complete(Unit)
        runCurrent()

        assertTrue(viewModel.uiState.value.autoBookkeeping.enabled)
        assertTrue(viewModel.uiState.value.autoBookkeeping.enabledWritePending)

        secondGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.autoBookkeeping.enabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
    }

    @Test
    fun olderSettingFailureIsSilentAndCannotChangeLatestState() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 15L, autoBookkeepingEnabled = false)
            autoWriteGates += firstGate
            autoWriteGates += secondGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.setAutoBookkeepingEnabled(true)
        runCurrent()
        viewModel.setAutoBookkeepingEnabled(false)
        repository.autoWriteError = IOException("older write failed")
        firstGate.complete(Unit)
        runCurrent()

        assertFalse(viewModel.uiState.value.autoBookkeeping.enabled)
        assertTrue(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
        assertFalse(event.isCompleted)

        repository.autoWriteError = null
        secondGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.autoBookkeeping.enabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun latestOrdinaryFailureRollsBackSilentlyWhenTicketBecomesStale() = runTest {
        val writeGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 17L, autoBookkeepingEnabled = false)
            autoWriteGates += writeGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.setAutoBookkeepingEnabled(true)
        runCurrent()
        requireNotNull(viewModel.coordinatorForTest().beginRestore())
        repository.autoWriteError = IOException("write failed after restore admission")
        writeGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.autoBookkeeping.enabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun latestSettingFailuresRollbackToPersistedBaselines() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(
                expenseId = 11L,
                autoBookkeepingEnabled = false,
                weChatEnabled = true,
                alipayEnabled = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(3).toList(events)
        }

        viewModel.setAutoBookkeepingEnabled(true)
        advanceUntilIdle()
        repository.autoWriteError = IOException("auto write failed")
        viewModel.setAutoBookkeepingEnabled(false)
        advanceUntilIdle()

        repository.weChatWriteError = IOException("WeChat write failed")
        viewModel.setWeChatEnabled(false)
        advanceUntilIdle()

        repository.alipayWriteError = IOException("Alipay write failed")
        viewModel.setAlipayEnabled(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.autoBookkeeping.enabled)
        assertTrue(viewModel.uiState.value.autoBookkeeping.weChatEnabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.alipayEnabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
        assertFalse(viewModel.uiState.value.autoBookkeeping.weChatWritePending)
        assertFalse(viewModel.uiState.value.autoBookkeeping.alipayWritePending)
        assertEquals(
            listOf(
                UiEvent.ShowMessage(UiMessage("自动记账设置保存失败")),
                UiEvent.ShowMessage(UiMessage("微信自动记账设置保存失败")),
                UiEvent.ShowMessage(UiMessage("支付宝自动记账设置保存失败"))
            ),
            events
        )
    }

    @Test
    fun refreshDoesNotOverwriteNewerPendingSettings() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val autoWriteGate = CompletableDeferred<Unit>()
        val weChatWriteGate = CompletableDeferred<Unit>()
        val alipayWriteGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(
                expenseId = 12L,
                autoBookkeepingEnabled = false,
                weChatEnabled = true,
                alipayEnabled = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += refreshGate
        repository.autoWriteGates += autoWriteGate
        repository.weChatWriteGates += weChatWriteGate
        repository.alipayWriteGates += alipayWriteGate

        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        viewModel.setAutoBookkeepingEnabled(true)
        viewModel.setWeChatEnabled(false)
        viewModel.setAlipayEnabled(true)
        refreshGate.complete(Unit)
        runCurrent()

        val pendingState = viewModel.uiState.value.autoBookkeeping
        assertTrue(pendingState.enabled)
        assertFalse(pendingState.weChatEnabled)
        assertTrue(pendingState.alipayEnabled)
        assertTrue(pendingState.enabledWritePending)
        assertTrue(pendingState.weChatWritePending)
        assertTrue(pendingState.alipayWritePending)

        autoWriteGate.complete(Unit)
        runCurrent()
        weChatWriteGate.complete(Unit)
        runCurrent()
        alipayWriteGate.complete(Unit)
        advanceUntilIdle()

        val completedState = viewModel.uiState.value.autoBookkeeping
        assertTrue(completedState.enabled)
        assertFalse(completedState.weChatEnabled)
        assertTrue(completedState.alipayEnabled)
        assertFalse(completedState.enabledWritePending)
        assertFalse(completedState.weChatWritePending)
        assertFalse(completedState.alipayWritePending)
    }

    @Test
    fun settingCancellationIsSilentAndClearsMatchingPendingState() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 13L, autoBookkeepingEnabled = false)
            autoWriteError = CancellationException("cancelled write")
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.setAutoBookkeepingEnabled(true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.autoBookkeeping.enabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun cancellationCleanupCannotClearReentrantNewerSettingWrite() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 16L, autoBookkeepingEnabled = false)
            autoWriteGates += firstGate
            autoWriteGates += secondGate
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        var cleanupArmed = false
        var newerWriteStarted = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { state ->
                if (
                    cleanupArmed &&
                    !state.autoBookkeeping.enabled &&
                    !newerWriteStarted
                ) {
                    newerWriteStarted = true
                    viewModel.setAutoBookkeepingEnabled(true)
                }
            }
        }

        viewModel.setAutoBookkeepingEnabled(true)
        runCurrent()
        cleanupArmed = true
        repository.autoWriteError = CancellationException("cancel first write")
        firstGate.complete(Unit)
        runCurrent()

        assertTrue(newerWriteStarted)
        assertTrue(viewModel.uiState.value.autoBookkeeping.enabled)
        assertTrue(viewModel.uiState.value.autoBookkeeping.enabledWritePending)

        repository.autoWriteError = null
        secondGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.autoBookkeeping.enabled)
        assertFalse(viewModel.uiState.value.autoBookkeeping.enabledWritePending)
        collector.cancel()
    }

    @Test
    fun cancelledViewModelScopeDoesNotLeaveOptimisticSettingState() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(
                expenseId = 14L,
                autoBookkeepingEnabled = false,
                weChatEnabled = true,
                alipayEnabled = false
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.viewModelScope.cancel()
        viewModel.setAutoBookkeepingEnabled(true)
        viewModel.setWeChatEnabled(false)
        viewModel.setAlipayEnabled(true)
        runCurrent()

        val settings = viewModel.uiState.value.autoBookkeeping
        assertFalse(settings.enabled)
        assertTrue(settings.weChatEnabled)
        assertFalse(settings.alipayEnabled)
        assertFalse(settings.enabledWritePending)
        assertFalse(settings.weChatWritePending)
        assertFalse(settings.alipayWritePending)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun exportPreparationEmitsOneDocumentAndCompletionMessagesWaitForWriter() = runTest {
        val backupManager = FakeExpenseBackupManager().apply {
            jsonExport = "json-content"
            csvExport = "csv-content"
        }
        val viewModel = createViewModel(
            repository = FakeExpenseRepository(),
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(4).toList(events)
        }

        val jsonWriteId = requireNotNull(
            viewModel.prepareExport(BackupExportFormat.JSON, "content://backup.json")
        )
        advanceUntilIdle()

        val jsonWrite = events.single() as UiEvent.WriteDocument
        assertEquals(jsonWriteId, jsonWrite.writeId)
        assertEquals("content://backup.json", jsonWrite.targetUri)
        assertEquals("json-content", jsonWrite.content)
        assertTrue(viewModel.uiState.value.pending.exportPreparing)
        assertNull(viewModel.prepareExport(BackupExportFormat.CSV, "content://duplicate.csv"))

        viewModel.documentWriteCompleted(jsonWrite.writeId, true)
        assertEquals(
            listOf(
                jsonWrite,
                UiEvent.ShowMessage(UiMessage("JSON 备份已导出"))
            ),
            events
        )
        assertFalse(viewModel.uiState.value.pending.exportPreparing)

        val csvWriteId = requireNotNull(
            viewModel.prepareExport(BackupExportFormat.CSV, "content://backup.csv")
        )
        advanceUntilIdle()

        val csvWrite = events[2] as UiEvent.WriteDocument
        assertEquals(csvWriteId, csvWrite.writeId)
        assertEquals("content://backup.csv", csvWrite.targetUri)
        assertEquals("csv-content", csvWrite.content)
        assertNotEquals(jsonWrite.writeId, csvWrite.writeId)
        viewModel.documentWriteCompleted(csvWrite.writeId, false)

        assertEquals(UiEvent.ShowMessage(UiMessage("CSV 表格导出失败")), events[3])
        assertEquals(1, backupManager.jsonExportCalls)
        assertEquals(1, backupManager.csvExportCalls)
        assertFalse(viewModel.uiState.value.pending.exportPreparing)
    }

    @Test
    fun staleSameFormatCompletionAfterRestoreDoesNotCompleteCurrentExportOrEmitSuccess() = runTest {
        val backupManager = FakeExpenseBackupManager().apply {
            jsonExport = "json-content"
            restoreResult = ExpenseBackupSettings(
                dailyBudgetCents = 0L,
                autoBookkeepingEnabled = false,
                weChatEnabled = true,
                alipayEnabled = true
            )
        }
        val viewModel = createViewModel(
            repository = FakeExpenseRepository(),
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(4).toList(events)
        }
        viewModel.prepareExport(BackupExportFormat.JSON, "content://old.json")
        advanceUntilIdle()
        val staleWrite = events.single() as UiEvent.WriteDocument
        assertEquals("content://old.json", staleWrite.targetUri)
        assertEquals("json-content", staleWrite.content)

        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        viewModel.confirmRestore()
        advanceUntilIdle()
        viewModel.prepareExport(BackupExportFormat.JSON, "content://new.json")
        advanceUntilIdle()

        assertEquals(UiEvent.ShowMessage(UiMessage("备份恢复完成")), events[1])
        val currentWrite = events[2] as UiEvent.WriteDocument
        assertEquals("content://new.json", currentWrite.targetUri)
        assertEquals("json-content", currentWrite.content)
        assertNotEquals(staleWrite.writeId, currentWrite.writeId)
        assertTrue(viewModel.uiState.value.pending.exportPreparing)
        viewModel.documentWriteCompleted(staleWrite.writeId, true)
        assertTrue(viewModel.uiState.value.pending.exportPreparing)
        assertEquals(3, events.size)

        viewModel.documentWriteCompleted(currentWrite.writeId, true)
        assertFalse(viewModel.uiState.value.pending.exportPreparing)
        assertEquals(UiEvent.ShowMessage(UiMessage("JSON 备份已导出")), events[3])
    }

    @Test
    fun exportQueuedBeforeRestoreIsRejectedSilentlyAndClearsPending() = runTest {
        val backupManager = FakeExpenseBackupManager()
        val viewModel = createViewModel(
            repository = FakeExpenseRepository(),
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        requireNotNull(viewModel.prepareExport(BackupExportFormat.JSON, "content://stale.json"))
        requireNotNull(viewModel.coordinatorForTest().beginRestore())
        assertNull(viewModel.prepareExport(BackupExportFormat.CSV, "content://restore.csv"))
        advanceUntilIdle()

        assertEquals(0, backupManager.jsonExportCalls)
        assertFalse(viewModel.uiState.value.pending.exportPreparing)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun invalidImportNeverOpensConfirmationAndReadFailureUsesExistingCopy() = runTest {
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = false
        }
        val handle = SavedStateHandle()
        val viewModel = createViewModel(
            repository = FakeExpenseRepository(),
            backupManager = backupManager,
            savedStateHandle = handle,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(2).toList(events)
        }

        viewModel.acceptImportJson("not-an-expense-backup")
        advanceUntilIdle()

        assertEquals(
            listOf(
                UiEvent.ShowMessage(
                    UiMessage("文件不是有效的 expense_report JSON 备份")
                )
            ),
            events
        )
        assertNull(viewModel.uiState.value.overlay)
        assertTrue(handle.keys().isEmpty())

        viewModel.importReadFailed()

        assertEquals(
            UiEvent.ShowMessage(UiMessage("无法读取所选文件")),
            events[1]
        )
        assertNull(viewModel.uiState.value.overlay)
    }

    @Test
    fun confirmRestoreSynchronouslyInvalidatesQueuedIntentsAndObsoleteUi() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 80L)
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = null
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        viewModel.showOverlay(ExpenseOverlay.EditExpense(80L))
        viewModel.showCategoryEditor(CategoryEditorTarget.New)
        viewModel.requestCategoryDelete("FOOD")

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        viewModel.updateExpense(80L, ExpenseEditInput("早餐", "", "FOOD"))
        viewModel.addCategory(CategoryInput("交通", 0xFF00FF00))
        viewModel.setBudget(BudgetKind.DAILY, 500L)
        viewModel.setAutoBookkeepingEnabled(false)
        viewModel.setWeChatEnabled(true)
        viewModel.setAlipayEnabled(false)
        viewModel.prepareExport(BackupExportFormat.JSON, "content://queued.json")

        viewModel.confirmRestore()

        val admittedState = viewModel.uiState.value
        assertTrue(viewModel.coordinatorForTest().isRestoring)
        assertTrue(admittedState.pending.restoring)
        assertFalse(admittedState.pending.entrySaving)
        assertFalse(admittedState.pending.expenseEditSaving)
        assertFalse(admittedState.pending.categorySaving)
        assertFalse(admittedState.pending.budgetSaving)
        assertFalse(admittedState.pending.exportPreparing)
        assertFalse(admittedState.autoBookkeeping.enabledWritePending)
        assertFalse(admittedState.autoBookkeeping.weChatWritePending)
        assertFalse(admittedState.autoBookkeeping.alipayWritePending)
        assertNull(admittedState.overlay)
        assertNull(admittedState.categoryManagement.editor)
        assertNull(admittedState.categoryManagement.deleteTargetKey)

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        advanceUntilIdle()

        assertEquals(0, repository.addExpenseCalls)
        assertEquals(0, repository.updateExpenseCalls)
        assertEquals(0, repository.addCategoryCalls)
        assertEquals(0, repository.dailyBudgetCalls)
        assertEquals(0, repository.autoWriteCalls)
        assertEquals(0, repository.weChatWriteCalls)
        assertEquals(0, repository.alipayWriteCalls)
        assertEquals(0, backupManager.jsonExportCalls)
        assertFalse(viewModel.uiState.value.pending.restoring)
    }

    @Test
    fun restoredSettingsSurviveFailedSnapshotWithoutAdvancingDisplayedEpoch() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(
                expenseId = 81L,
                autoBookkeepingEnabled = false,
                weChatEnabled = true,
                alipayEnabled = false
            )
        }
        val restoredSettings = ExpenseBackupSettings(
            dailyBudgetCents = 900L,
            autoBookkeepingEnabled = true,
            weChatEnabled = false,
            alipayEnabled = true
        )
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = restoredSettings
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        val displayedEpochBeforeRestore = viewModel.uiState.value.displayedDataEpoch
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(2).toList(events)
        }
        repository.loadHomeSnapshotError = IOException("post-restore snapshot failed")

        viewModel.confirmRestore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(displayedEpochBeforeRestore, state.displayedDataEpoch)
        assertEquals(true, state.autoBookkeeping.enabled)
        assertEquals(false, state.autoBookkeeping.weChatEnabled)
        assertEquals(true, state.autoBookkeeping.alipayEnabled)
        assertEquals(
            ForegroundSettingsBaseline(true, false, true),
            viewModel.coordinatorForTest().persistedSettings
        )
        assertFalse(state.pending.restoring)
        assertEquals(
            listOf(UiEvent.ShowMessage(UiMessage("操作已保存，但页面刷新失败"))),
            events
        )

        repository.weChatWriteError = IOException("setting failed")
        viewModel.setWeChatEnabled(true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.autoBookkeeping.weChatEnabled)
    }

    @Test
    fun successfulRestoreWithFailedAuthoritativeHomeReadLeavesHistoryRetryable() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 167L)
            historyPages[null] = HistoryPage(
                items = listOf(sampleHistoryExpense(168L, 3_000L)),
                nextCursor = null,
                hasMore = false
            )
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = ExpenseBackupSettings(
                dailyBudgetCents = 900L,
                autoBookkeepingEnabled = true,
                weChatEnabled = false,
                alipayEnabled = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.openHistory()
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        repository.loadHomeSnapshotError = IOException("post-restore home read failed")

        viewModel.confirmRestore()
        advanceUntilIdle()

        val failedHistory = viewModel.uiState.value.history
        assertEquals(1, backupManager.restoreCalls)
        assertEquals(listOf(168L), failedHistory.items.map { it.id })
        assertFalse(failedHistory.loading)
        assertFalse(failedHistory.loadingMore)
        assertTrue(failedHistory.loadFailed)
        assertNull(failedHistory.failedCursor)

        repository.loadHomeSnapshotError = null
        repository.historyPages[null] = HistoryPage(
            items = listOf(sampleHistoryExpense(169L, 4_000L)),
            nextCursor = null,
            hasMore = false
        )
        viewModel.openHistory()
        viewModel.retryHistoryLoad()
        advanceUntilIdle()

        assertEquals(2, repository.loadHistoryPageCalls)
        assertEquals(listOf(169L), viewModel.uiState.value.history.items.map { it.id })
        assertFalse(viewModel.uiState.value.history.loadFailed)
    }

    @Test
    fun notRestoredRefreshesMutationThatWasAdmittedBeforeRestore() = runTest {
        val mutationGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 82L)
            addExpenseGates += mutationGate
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = null
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()

        viewModel.submitNewExpense(NewExpenseInput(100L, "FOOD", "午餐", ""))
        runCurrent()
        assertEquals(1, repository.addExpenseCalls)
        repository.homeSnapshot = sampleHomeSnapshot(expenseId = 83L)

        viewModel.confirmRestore()
        mutationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(83L), viewModel.uiState.value.expenses.map { it.id })
        assertEquals(
            viewModel.coordinatorForTest().currentEpoch,
            viewModel.uiState.value.displayedDataEpoch
        )
        assertEquals(2, repository.loadHomeSnapshotCalls)
        assertFalse(viewModel.uiState.value.pending.restoring)
    }

    @Test
    fun restoreCancellationClearsRestoringAndPrivatePayloadWithoutAnEvent() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 84L)
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreError = CancellationException("restore cancelled")
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.first()
        }

        viewModel.confirmRestore()
        advanceUntilIdle()
        viewModel.confirmRestore()
        advanceUntilIdle()

        assertFalse(viewModel.coordinatorForTest().isRestoring)
        assertFalse(viewModel.uiState.value.pending.restoring)
        assertNull(viewModel.uiState.value.overlay)
        assertEquals(1, backupManager.restoreCalls)
        assertFalse(event.isCompleted)
        event.cancel()
    }

    @Test
    fun cancellationBeforeQueuedRestoreStartsCannotLeaveCoordinatorRestoring() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 88L)
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = ExpenseBackupSettings(
                dailyBudgetCents = 700L,
                autoBookkeepingEnabled = true,
                weChatEnabled = false,
                alipayEnabled = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()

        viewModel.confirmRestore()
        viewModel.viewModelScope.cancel()
        runCurrent()

        assertFalse(viewModel.coordinatorForTest().isRestoring)
        assertFalse(viewModel.uiState.value.pending.restoring)
        assertEquals(1, backupManager.restoreCalls)
    }

    @Test
    fun restoreFailureClearsRestoringAndPrivatePayload() = runTest {
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 85L)
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreError = IOException("restore failed")
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.take(1).toList(events)
        }

        viewModel.confirmRestore()
        advanceUntilIdle()
        viewModel.confirmRestore()
        advanceUntilIdle()

        assertFalse(viewModel.coordinatorForTest().isRestoring)
        assertFalse(viewModel.uiState.value.pending.restoring)
        assertNull(viewModel.uiState.value.overlay)
        assertEquals(1, backupManager.restoreCalls)
        assertEquals(
            listOf(UiEvent.ShowMessage(UiMessage("恢复未完成，请检查当前账本后重试。"))),
            events
        )
    }

    @Test
    fun entityIntentIsAllowedOnlyAfterFreshPostRestoreSnapshot() = runTest {
        val restoreRefreshGate = CompletableDeferred<Unit>()
        val repository = FakeExpenseRepository().apply {
            homeSnapshot = sampleHomeSnapshot(expenseId = 86L)
        }
        val backupManager = FakeExpenseBackupManager().apply {
            validateJsonResult = true
            restoreResult = ExpenseBackupSettings(
                dailyBudgetCents = 700L,
                autoBookkeepingEnabled = true,
                weChatEnabled = false,
                alipayEnabled = true
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            backupManager = backupManager,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        viewModel.refresh(NotificationListenerConnectionState.CONNECTED)
        advanceUntilIdle()
        viewModel.acceptImportJson("valid-backup")
        advanceUntilIdle()
        repository.loadHomeSnapshotGates += restoreRefreshGate

        viewModel.confirmRestore()
        runCurrent()
        assertTrue(viewModel.uiState.value.pending.restoring)

        val input = NewExpenseInput(100L, "FOOD", "午餐", "")
        viewModel.submitNewExpense(input)
        assertEquals(0, repository.addExpenseCalls)

        repository.homeSnapshot = sampleHomeSnapshot(expenseId = 87L)
        restoreRefreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            viewModel.coordinatorForTest().currentEpoch,
            viewModel.uiState.value.displayedDataEpoch
        )
        viewModel.submitNewExpense(input)
        advanceUntilIdle()

        assertEquals(1, repository.addExpenseCalls)
    }

    @Test
    fun overlaysAreTransientAndDoNotWriteSavedState() = runTest {
        val handle = SavedStateHandle()
        val viewModel = createViewModel(
            repository = FakeExpenseRepository(),
            savedStateHandle = handle,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        viewModel.showOverlay(ExpenseOverlay.History)

        assertEquals(ExpenseOverlay.History, viewModel.uiState.value.overlay)
        assertTrue(handle.keys().isEmpty())

        viewModel.dismissOverlay()

        assertNull(viewModel.uiState.value.overlay)
        assertTrue(handle.keys().isEmpty())
    }

    @Test
    fun snapshotCommitAndDisplayedEpochAreAtomicAgainstRestoreAdmission() {
        val coordinator = ForegroundPersistenceCoordinator(
            ForegroundSettingsBaseline(
                autoBookkeepingEnabled = false,
                weChatEnabled = true,
                alipayEnabled = true
            )
        )
        val ticket = requireNotNull(coordinator.mutationTicket())
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val restoreAttempted = CountDownLatch(1)
        val restoreFinished = CountDownLatch(1)
        var appliedEpoch = -1L
        var restoreTicket: ForegroundRestoreTicket? = null

        val commitThread = thread {
            coordinator.commitIfCurrent(ticket) { epoch ->
                commitEntered.countDown()
                releaseCommit.await()
                appliedEpoch = epoch
            }
        }
        assertTrue(commitEntered.await(1, TimeUnit.SECONDS))

        val restoreThread = thread {
            restoreAttempted.countDown()
            restoreTicket = coordinator.beginRestore()
            restoreFinished.countDown()
        }
        assertTrue(restoreAttempted.await(1, TimeUnit.SECONDS))
        assertFalse(restoreFinished.await(100, TimeUnit.MILLISECONDS))

        releaseCommit.countDown()
        commitThread.join()
        restoreThread.join()

        assertEquals(0L, appliedEpoch)
        assertNotNull(restoreTicket)
        assertEquals(1L, coordinator.currentEpoch)
    }

    private fun createViewModel(
        repository: ExpenseRepositoryContract,
        backupManager: FakeExpenseBackupManager = FakeExpenseBackupManager(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        ioDispatcher: CoroutineDispatcher,
        today: () -> LocalDate = LocalDate::now
    ): ExpenseViewModel = ExpenseViewModel(
        repository = repository,
        backupManager = backupManager,
        savedStateHandle = savedStateHandle,
        ioDispatcher = ioDispatcher,
        today = today
    )

    private fun ExpenseViewModel.coordinatorForTest(): ForegroundPersistenceCoordinator {
        val field = ExpenseViewModel::class.java.getDeclaredField("coordinator")
        field.isAccessible = true
        return field.get(this) as ForegroundPersistenceCoordinator
    }

    private fun sampleHomeSnapshot(
        expenseId: Long,
        autoBookkeepingEnabled: Boolean = true,
        weChatEnabled: Boolean = false,
        alipayEnabled: Boolean = true
    ): ExpenseHomeSnapshot {
        val category = ExpenseCategory(
            key = "FOOD",
            name = "餐饮",
            colorArgb = 0xFFFF0000,
            builtIn = true,
            sortOrder = 0
        )
        return ExpenseHomeSnapshot(
            expenses = listOf(
                ExpenseRecord(
                    id = expenseId,
                    amountCents = 340L,
                    category = category,
                    name = "早餐",
                    note = "",
                    spentAt = 0L,
                    source = ExpenseSource.MANUAL,
                    merchant = null
                )
            ),
            categories = listOf(category),
            monthlyBudgetCents = 4_200L,
            dailyBudgetCents = 700L,
            todayTotalCents = 340L,
            autoBookkeepingEnabled = autoBookkeepingEnabled,
            weChatEnabled = weChatEnabled,
            alipayEnabled = alipayEnabled
        )
    }

    private fun sampleHistoryExpense(id: Long, spentAt: Long): ExpenseRecord = ExpenseRecord(
        id = id,
        amountCents = id,
        category = sampleHomeSnapshot(expenseId = id).categories.single(),
        name = "history-$id",
        note = "",
        spentAt = spentAt,
        source = ExpenseSource.MANUAL,
        merchant = null
    )
}
