package com.example.monthlyexpense.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.example.monthlyexpense.BackupExportFormat
import com.example.monthlyexpense.BudgetKind
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.ExpensePendingState
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.ExpenseUiState
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.HistoryCursor
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseAppScreenTest {
    @get:Rule(order = 0)
    val dispatcherRule: org.junit.rules.TestRule = ComposeDispatcherIsolationRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun dismissedMessageIsNotReplayedByRecompositionOrHostRecreation() {
        val events = Channel<UiEvent>(Channel.BUFFERED)
        val recomposition = mutableIntStateOf(0)
        val hostGeneration = mutableIntStateOf(0)

        composeRule.setContent {
            recomposition.intValue
            key(hostGeneration.intValue) {
                ExpenseUiEventHost(
                    events = events.receiveAsFlow(),
                    documentEffect = { writeId ->
                        DocumentEffect(
                            BackupExportFormat.JSON,
                            generation = 1,
                            writeId = writeId
                        )
                    },
                    documentWriter = { _, _ -> true },
                    onDocumentWriteCompleted = { _, _ -> }
                )
            }
        }

        events.trySend(UiEvent.ShowMessage(UiMessage("仅显示一次")))

        composeRule.onNodeWithText("仅显示一次").assertIsDisplayed()
        composeRule.runOnIdle { recomposition.intValue++ }
        composeRule.onNodeWithText("仅显示一次").assertIsDisplayed()
        composeRule.onNodeWithText("知道了").performClick()
        composeRule.onAllNodesWithText("仅显示一次").assertCountEquals(0)

        composeRule.runOnIdle { hostGeneration.intValue++ }
        composeRule.onAllNodesWithText("仅显示一次").assertCountEquals(0)
    }

    @Test
    fun writeDocumentRunsWriterAndCompletionExactlyOnceAcrossRecompositionAndRecreation() {
        val events = Channel<UiEvent>(Channel.BUFFERED)
        val recomposition = mutableIntStateOf(0)
        val hostGeneration = mutableIntStateOf(0)
        val writes = mutableListOf<Pair<String, String>>()
        val completions = mutableListOf<Pair<BackupExportFormat, Boolean>>()
        val writerStarted = CompletableDeferred<Unit>()
        val allowWriteToFinish = CompletableDeferred<Unit>()

        composeRule.setContent {
            recomposition.intValue
            key(hostGeneration.intValue) {
                ExpenseUiEventHost(
                    events = events.receiveAsFlow(),
                    documentEffect = { writeId ->
                        DocumentEffect(
                            BackupExportFormat.CSV,
                            generation = 1,
                            writeId = writeId
                        )
                    },
                    documentWriter = { targetUri, content ->
                        writerStarted.complete(Unit)
                        allowWriteToFinish.await()
                        writes += targetUri to content
                        true
                    },
                    onDocumentWriteCompleted = { effect, success ->
                        completions += effect.format to success
                    }
                )
            }
        }

        events.trySend(
            UiEvent.WriteDocument(
                writeId = 1,
                targetUri = "content://backup/export",
                content = "a,b\n1,2"
            )
        )

        composeRule.waitUntil { writerStarted.isCompleted }
        composeRule.runOnIdle { recomposition.intValue++ }
        composeRule.runOnIdle { hostGeneration.intValue++ }
        allowWriteToFinish.complete(Unit)
        composeRule.waitUntil { writes.size == 1 && completions.size == 1 }
        assertEquals(listOf("content://backup/export" to "a,b\n1,2"), writes)
        assertEquals(listOf(BackupExportFormat.CSV to true), completions)
        composeRule.runOnIdle {
            assertEquals(1, writes.size)
            assertEquals(1, completions.size)
        }
    }

    @Test
    fun staleDocumentCompletionDoesNotClearNewGenerationOrBreakNextEvent() {
        val events = Channel<UiEvent>(Channel.BUFFERED)
        val hostGeneration = mutableIntStateOf(0)
        val oldWriteStarted = CompletableDeferred<Unit>()
        val allowOldWriteToFinish = CompletableDeferred<Unit>()
        val oldEffect = DocumentEffect(BackupExportFormat.JSON, generation = 1, writeId = 1)
        val newEffect = DocumentEffect(BackupExportFormat.CSV, generation = 2, writeId = 2)
        var currentEffect: DocumentEffect? = oldEffect
        val writes = mutableListOf<String>()
        val completions = mutableListOf<Pair<DocumentEffect, Boolean>>()

        composeRule.setContent {
            key(hostGeneration.intValue) {
                ExpenseUiEventHost(
                    events = events.receiveAsFlow(),
                    documentEffect = { checkNotNull(currentEffect) },
                    documentWriter = { _, content ->
                        if (content == "old-json") {
                            oldWriteStarted.complete(Unit)
                            allowOldWriteToFinish.await()
                        }
                        writes += content
                        true
                    },
                    onDocumentWriteCompleted = { effect, success ->
                        currentEffect.dispatchCompletionIfCurrent(
                            completedEffect = effect,
                            success = success,
                            onCurrentCompletion = { _, _ -> currentEffect = null }
                        )
                        completions += effect to success
                    }
                )
            }
        }

        events.trySend(
            UiEvent.WriteDocument(
                writeId = oldEffect.writeId,
                targetUri = "content://backup/old",
                content = "old-json"
            )
        )
        composeRule.waitUntil { oldWriteStarted.isCompleted }

        composeRule.runOnIdle { hostGeneration.intValue++ }
        composeRule.runOnIdle { currentEffect = newEffect }
        allowOldWriteToFinish.complete(Unit)

        composeRule.waitUntil { completions.size == 1 }
        composeRule.runOnIdle { assertEquals(newEffect, currentEffect) }

        events.trySend(
            UiEvent.WriteDocument(
                writeId = newEffect.writeId,
                targetUri = "content://backup/new",
                content = "new-csv"
            )
        )
        composeRule.waitUntil { completions.size == 2 }

        assertEquals(listOf("old-json", "new-csv"), writes)
        assertEquals(listOf(oldEffect to true, newEffect to true), completions)
        composeRule.runOnIdle { assertEquals(null, currentEffect) }
    }

    @Test
    fun bufferedStaleSameFormatDocumentEventIsDroppedWithoutWriteOrCompletion() {
        val events = Channel<UiEvent>(Channel.BUFFERED)
        val oldEffect = DocumentEffect(BackupExportFormat.JSON, generation = 1, writeId = 1)
        val newEffect = DocumentEffect(BackupExportFormat.JSON, generation = 2, writeId = 2)
        var currentEffect: DocumentEffect? = newEffect
        var staleEffectLookups = 0
        val writes = mutableListOf<String>()
        val completions = mutableListOf<Pair<DocumentEffect, Boolean>>()

        events.trySend(
            UiEvent.WriteDocument(
                writeId = oldEffect.writeId,
                targetUri = "content://backup/old",
                content = "old-json"
            )
        )

        composeRule.setContent {
            ExpenseUiEventHost(
                events = events.receiveAsFlow(),
                documentEffect = { writeId ->
                    if (writeId == oldEffect.writeId) staleEffectLookups++
                    currentEffect?.takeIf { it.writeId == writeId }
                },
                documentWriter = { _, content ->
                    writes += content
                    true
                },
                onDocumentWriteCompleted = { effect, success ->
                    currentEffect.dispatchCompletionIfCurrent(
                        completedEffect = effect,
                        success = success,
                        onCurrentCompletion = { _, _ -> currentEffect = null }
                    )
                    completions += effect to success
                }
            )
        }

        composeRule.waitUntil { staleEffectLookups == 1 }
        assertEquals(emptyList<String>(), writes)
        assertEquals(emptyList<Pair<DocumentEffect, Boolean>>(), completions)
        composeRule.runOnIdle { assertEquals(newEffect, currentEffect) }

        events.trySend(
            UiEvent.WriteDocument(
                writeId = newEffect.writeId,
                targetUri = "content://backup/new",
                content = "new-json"
            )
        )
        composeRule.waitUntil { completions.size == 1 }

        assertEquals(listOf("new-json"), writes)
        assertEquals(listOf(newEffect to true), completions)
        composeRule.runOnIdle { assertEquals(null, currentEffect) }
    }

    @Test
    fun pendingExportDisablesSecondCsvDocumentRequest() {
        var csvRequests = 0
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    pending = ExpensePendingState(exportPreparing = true),
                    overlay = ExpenseOverlay.Menu
                ),
                callbacks = noOpCallbacks(onExportCsv = { csvRequests++ })
            )
        }

        composeRule.onNodeWithText("导出表格（CSV）").assertIsNotEnabled()
        composeRule.onNodeWithText("导出表格（CSV）").performClick()
        composeRule.runOnIdle { assertEquals(0, csvRequests) }
    }

    @Test
    fun homeShowsComputedTotalsRecordsAndRoutesRecordEdits() {
        val lunch = screenExpense(id = 7L, name = "午餐", amountCents = 1_234L)
        val coffee = screenExpense(id = 8L, name = "咖啡", amountCents = 566L)
        val shownOverlays = mutableListOf<ExpenseOverlay>()

        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    expenses = listOf(lunch, coffee),
                    categories = listOf(screenCategory),
                    monthlyBudgetCents = 2_000L,
                    dailyBudgetCents = 1_000L,
                    todayTotalCents = 566L
                ),
                callbacks = noOpCallbacks(onShowOverlay = { shownOverlays += it })
            )
        }

        scrollHomeTo(2)
        composeRule.onNodeWithText("¥18.00 / ¥20.00").performScrollTo().assertIsDisplayed()
        scrollHomeTo(8)
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
        composeRule.onAllNodesWithText("编辑记录")[0].performClick()
        scrollHomeTo(9)
        composeRule.onNodeWithText("咖啡").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(listOf(ExpenseOverlay.EditExpense(7L)), shownOverlays)
        }
    }

    @Test
    fun sidebarShowsSettingsAndMovesBackgroundOutOfSidebar() {
        var opens = 0
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(overlay = ExpenseOverlay.Menu),
                callbacks = noOpCallbacks().copy(onOpenSettings = { opens++ })
            )
        }
        composeRule.onNodeWithText("个性化背景").assertDoesNotExist()
        composeRule.onNodeWithText("设置").performClick()
        assertEquals(1, opens)
    }

    @Test
    fun historicalRecordCanOpenDateEditor() {
        val record = screenExpense(901L, "历史消费", 500L)
        val state = mutableStateOf(ExpenseUiState(
            history = HistoryUiState(items = listOf(record),
                selectedMonth = java.time.YearMonth.of(2026, 8),
                initialLoadComplete = true, hasMore = false),
            categories = listOf(record.category),
            overlay = ExpenseOverlay.History
        ))
        composeRule.setContent {
            ExpenseAppScreen(state = state.value, callbacks = noOpCallbacks(
                onShowOverlay = { state.value = state.value.copy(overlay = it) }
            ))
        }
        composeRule.onNodeWithText("历史消费").performClick()
        composeRule.onNodeWithText("编辑记录").assertIsDisplayed()
        composeRule.onNodeWithText("修改入账时间").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存更改").assertIsEnabled()
    }

    @Test
    fun selectedHistoryMonthShowsArchivedRecordsAndRoutesClose() {
        var dismisses = 0

        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    history = HistoryUiState(
                        items = listOf(
                            screenExpense(id = 9L, name = "上月地铁", amountCents = 400L)
                        ),
                        selectedMonth = java.time.YearMonth.of(2026, 8),
                        initialLoadComplete = true,
                        hasMore = false
                    ),
                    overlay = ExpenseOverlay.History
                ),
                callbacks = noOpCallbacks(onDismissOverlay = { dismisses++ })
            )
        }

        composeRule.onNodeWithText("历史账单").assertIsDisplayed()
        composeRule.onNodeWithText("上月地铁").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performClick()

        composeRule.runOnIdle { assertEquals(1, dismisses) }
    }

    @Test
    fun historyLoadingShowsProgress() {
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    history = HistoryUiState(loading = true),
                    overlay = ExpenseOverlay.History
                ),
                callbacks = noOpCallbacks()
            )
        }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("还没有已归档的账单").assertCountEquals(0)
    }

    @Test
    fun historyErrorShowsExactRetryCopyAndRoutesRetryOnce() {
        var retryCalls = 0
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    history = HistoryUiState(
                        initialLoadComplete = true,
                        loadFailed = true
                    ),
                    overlay = ExpenseOverlay.History
                ),
                callbacks = noOpCallbacks(onRetryHistory = { retryCalls++ })
            )
        }

        composeRule.onNodeWithText("历史账单加载失败，请重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCalls) }
    }

    @Test
    fun completedEmptyHistoryShowsExistingEmptyCopy() {
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    history = HistoryUiState(
                        initialLoadComplete = true,
                        hasMore = false
                    ),
                    overlay = ExpenseOverlay.History
                ),
                callbacks = noOpCallbacks()
            )
        }

        composeRule.onNodeWithText("还没有已归档的账单").assertIsDisplayed()
        composeRule.onAllNodesWithText("加载更多").assertCountEquals(0)
    }

    @Test
    fun historyShowsMonthsBeforeDetailsAndCanReturnToMonths() {
        val september = java.time.YearMonth.of(2026, 9)
        val august = java.time.YearMonth.of(2026, 8)
        val history = mutableStateOf(HistoryUiState(
            months = listOf(
                com.example.monthlyexpense.data.HistoryMonth(september, 12345L, 51),
                com.example.monthlyexpense.data.HistoryMonth(august, 100L, 1)
            ),
            initialLoadComplete = true,
            hasMore = false
        ))
        composeRule.setContent {
            com.example.monthlyexpense.ui.history.HistoryPanel(
                state = history.value,
                onClose = {}, onLoadMore = {}, onRetry = {},
                onSelectMonth = { month ->
                    history.value = history.value.copy(selectedMonth = month,
                        items = listOf(screenExpense(201L, "九月账单", 300L)))
                },
                onBackToMonths = { history.value = history.value.copy(selectedMonth = null) }
            )
        }
        composeRule.onNodeWithText("九月账单").assertDoesNotExist()
        composeRule.onNodeWithText("2026年 9月").performClick()
        composeRule.onNodeWithText("九月账单").assertIsDisplayed()
        composeRule.onNodeWithText("本月总支出").assertIsDisplayed()
        composeRule.onNodeWithText(formatMoney(12345L)).assertIsDisplayed()
        composeRule.onNodeWithText("2026年 8月").assertDoesNotExist()
        composeRule.onNodeWithText("返回月份列表").performClick()
        composeRule.onNodeWithText("2026年 8月").assertIsDisplayed()
        composeRule.onNodeWithText("九月账单").assertDoesNotExist()
    }

    @Test
    fun historyHasMoreInvokesLoadMoreExactlyOnceWhileEnabled() {
        var loadMoreCalls = 0
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    history = HistoryUiState(
                        selectedMonth = java.time.YearMonth.of(2026, 8),
                        items = listOf(screenExpense(204L, "可继续加载", 100L)),
                        nextCursor = HistoryCursor(spentAt = 1_000L, id = 204L),
                        initialLoadComplete = true,
                        hasMore = true
                    ),
                    overlay = ExpenseOverlay.History
                ),
                callbacks = noOpCallbacks(onLoadMoreHistory = { loadMoreCalls++ })
            )
        }

        composeRule.onNodeWithText("加载更多").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, loadMoreCalls) }
    }

    @Test
    fun historyMenuRoutesToOpenHistoryInsteadOfGenericOverlayCallback() {
        var openHistoryCalls = 0
        var genericHistoryCalls = 0
        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(overlay = ExpenseOverlay.Menu),
                callbacks = noOpCallbacks(
                    onOpenHistory = { openHistoryCalls++ },
                    onShowOverlay = { overlay ->
                        if (overlay == ExpenseOverlay.History) genericHistoryCalls++
                    }
                )
            )
        }

        composeRule.onNodeWithText("历史账单").performClick()
        composeRule.runOnIdle {
            assertEquals(1, openHistoryCalls)
            assertEquals(0, genericHistoryCalls)
        }
    }

    @Test
    fun restoringAndPendingStateBlockRelevantControlsAndCallbacks() {
        var entryRequests = 0

        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(
                    categories = listOf(screenCategory),
                    pending = ExpensePendingState(restoring = true),
                    overlay = ExpenseOverlay.BackupOptions
                ),
                callbacks = noOpCallbacks(
                    onShowOverlay = { overlay ->
                        if (overlay == ExpenseOverlay.Entry) entryRequests++
                    }
                )
            )
        }

        composeRule.onNodeWithText("导出 JSON").assertIsNotEnabled()
        composeRule.onNodeWithText("导入 JSON").assertIsNotEnabled()
        composeRule.onNodeWithText("记一笔", useUnmergedTree = true).performClick()

        composeRule.runOnIdle {
            assertEquals(0, entryRequests)
        }
    }

    @Test
    fun pendingCategoryAndBudgetWritesDisableTheirDialogControls() {
        val state = mutableStateOf(
            ExpenseUiState(
                categories = listOf(screenCategory),
                pending = ExpensePendingState(categorySaving = true),
                overlay = ExpenseOverlay.Categories
            )
        )

        composeRule.setContent {
            ExpenseAppScreen(
                state = state.value,
                callbacks = noOpCallbacks()
            )
        }

        composeRule.onNodeWithText("添加分类").assertIsNotEnabled()
        composeRule.onNodeWithText("完成").assertIsNotEnabled()

        composeRule.runOnIdle {
            state.value = ExpenseUiState(
                pending = ExpensePendingState(budgetSaving = true),
                monthlyBudgetCents = 10_000L,
                overlay = ExpenseOverlay.EditBudget(BudgetKind.MONTHLY)
            )
        }

        composeRule.onNodeWithText("保存").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
    }

    @Test
    fun categoryAndBudgetActionsRouteToTheirExpectedOverlays() {
        val shownOverlays = mutableListOf<ExpenseOverlay>()

        composeRule.setContent {
            ExpenseAppScreen(
                state = ExpenseUiState(categories = listOf(screenCategory)),
                callbacks = noOpCallbacks(onShowOverlay = { shownOverlays += it })
            )
        }

        scrollHomeTo(3)
        composeRule.onNodeWithText("管理分类").performScrollTo().performClick()
        scrollHomeTo(3)
        composeRule.onNodeWithText("点击设置").performClick()
        scrollHomeTo(6)
        composeRule.onNodeWithText("修改今日预算").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    ExpenseOverlay.Categories,
                    ExpenseOverlay.EditBudget(BudgetKind.MONTHLY),
                    ExpenseOverlay.EditBudget(BudgetKind.DAILY)
                ),
                shownOverlays
            )
        }
    }

    private fun scrollHomeTo(index: Int) {
        composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollToIndex) {
            it(index)
        }
    }

    @Test fun searchEntryIsBesideRecordsAndCategoryControlIsInOverview() {
        var opened = false
        composeRule.setContent {
            ExpenseAppScreen(ExpenseUiState(categories = listOf(screenCategory)),
                noOpCallbacks().copy(onOpenSearch = { opened = true }))
        }
        scrollHomeTo(3)
        composeRule.onNodeWithText("管理分类").performScrollTo().assertIsDisplayed()
        scrollHomeTo(7)
        composeRule.onNodeWithText("消费记录").assertIsDisplayed()
        composeRule.onNodeWithText("搜索").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test fun specialExpenseHasSeparateSectionAndDoesNotDistortDailyOverview() {
        val daily = screenExpense(1, "生活费", 132_377)
        val special = screenExpense(2, "学费", 6_021_680).copy(isSpecial = true, note = "本学年")
        val state = mutableStateOf(ExpenseUiState(expenses = listOf(special, daily), categories = listOf(screenCategory),
            monthlyBudgetCents = 480_000))
        composeRule.setContent {
            ExpenseAppScreen(
                state.value,
                noOpCallbacks(onShowOverlay = { state.value = state.value.copy(overlay = it) },
                    onDismissOverlay = { state.value = state.value.copy(overlay = null) }))
        }
        scrollHomeTo(3)
        composeRule.onNodeWithText("¥1323.77 / ¥4800.00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("本月预算剩余 ¥3476.23").performScrollTo().assertIsDisplayed()
        scrollHomeTo(4)
        composeRule.onNodeWithText("专项预算").assertIsDisplayed()
        composeRule.onNodeWithText("学费").assertDoesNotExist()
        composeRule.onNodeWithText("添加或管理分类").assertDoesNotExist()
        composeRule.onNodeWithText("分类与日常消费共用").assertDoesNotExist()
        composeRule.onNodeWithText("专项预算详情").performClick()
        composeRule.onNodeWithText("学费").assertIsDisplayed()
        composeRule.onNodeWithText("本学年").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("生活费").assertDoesNotExist()
        composeRule.onNodeWithText("返回").performClick()
        scrollHomeTo(5)
        composeRule.onNodeWithText("本月消费总额").assertIsDisplayed()
        composeRule.onNodeWithText("¥61540.57").assertIsDisplayed()
        scrollHomeTo(8)
        composeRule.onNodeWithText("生活费").assertIsDisplayed()
    }

    @Test fun confirmedMovePassesMembershipToUpdateCallback() {
        val expense = screenExpense(1, "学费", 6_021_680)
        val saved = mutableListOf<com.example.monthlyexpense.ExpenseEditInput>()
        composeRule.setContent {
            ExpenseAppScreen(
                ExpenseUiState(expenses = listOf(expense), categories = listOf(screenCategory),
                    overlay = ExpenseOverlay.EditExpense(1)),
                noOpCallbacks().copy(onUpdateExpense = { _, input -> saved += input }))
        }
        composeRule.onNodeWithText("移至专项预算").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(saved.isEmpty()) }
        composeRule.onNodeWithText("确认移入").performClick()
        composeRule.runOnIdle { assertEquals(true, saved.single().isSpecial) }
    }

    @Test fun specialRecordCanBeDeletedDirectlyAndTotalsRefresh() {
        val state = mutableStateOf(ExpenseUiState(
            expenses = listOf(screenExpense(1, "学费", 6_021_680).copy(isSpecial = true)),
            categories = listOf(screenCategory)))
        composeRule.setContent {
            ExpenseAppScreen(state.value, noOpCallbacks(
                onShowOverlay = { state.value = state.value.copy(overlay = it) },
                onDismissOverlay = { state.value = state.value.copy(overlay = null) }
            ).copy(onDeleteExpense = { id ->
                state.value = state.value.copy(expenses = state.value.expenses.filterNot { it.id == id })
            }))
        }
        scrollHomeTo(4)
        composeRule.onNodeWithText("专项预算详情").performClick()
        composeRule.onNodeWithText("删除").performScrollTo().performClick()
        composeRule.onNodeWithText("本月暂无专项账单").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        scrollHomeTo(5)
        composeRule.onNodeWithText("专项支出 ¥0.00").assertIsDisplayed()
    }

    @Test fun sharedCategoryManagementIncludesSpecialSpendingInMonthlyCategoryTotal() {
        composeRule.setContent {
            ExpenseAppScreen(
                ExpenseUiState(expenses = listOf(
                    screenExpense(1, "日常", 132_377),
                    screenExpense(2, "学费", 6_021_680).copy(isSpecial = true)),
                    categories = listOf(screenCategory), overlay = ExpenseOverlay.Categories),
                noOpCallbacks())
        }
        composeRule.onNodeWithText("日常与专项共用分类，金额包含专项支出").assertIsDisplayed()
        composeRule.onNodeWithText("本月总额 ¥61540.57", substring = true).assertIsDisplayed()
    }

    @Test fun specialDetailsEditRoutesBackToDetailsAndBlocksActionsWhileSaving() {
        val state = mutableStateOf(ExpenseUiState(
            expenses = listOf(screenExpense(1, "学费", 6_021_680).copy(isSpecial = true)),
            categories = listOf(screenCategory), overlay = ExpenseOverlay.SpecialBudgetDetails))
        val overlays = mutableListOf<ExpenseOverlay>()
        composeRule.setContent {
            ExpenseAppScreen(state.value, noOpCallbacks(onShowOverlay = { overlays += it }))
        }
        composeRule.onNodeWithText("编辑记录").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(ExpenseOverlay.EditExpense(1, returnToSpecialBudget = true)), overlays)
            state.value = state.value.copy(pending = ExpensePendingState(expenseEditSaving = true))
        }
        composeRule.onNodeWithText("编辑记录").assertIsNotEnabled()
        composeRule.onNodeWithText("删除").assertIsNotEnabled()
        composeRule.onNodeWithText("返回").assertIsNotEnabled()
    }
}

private fun noOpCallbacks(
    onExportCsv: () -> Unit = {},
    onShowOverlay: (ExpenseOverlay) -> Unit = {},
    onDismissOverlay: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onLoadMoreHistory: () -> Unit = {},
    onRetryHistory: () -> Unit = {}
) = ExpenseAppCallbacks(
    onShowOverlay = onShowOverlay,
    onOpenHistory = onOpenHistory,
    onLoadMoreHistory = onLoadMoreHistory,
    onRetryHistory = onRetryHistory,
    onDismissOverlay = onDismissOverlay,
    onSubmitNewExpense = {},
    onUpdateExpense = { _, _ -> },
    onDeleteExpense = {},
    onShowCategoryEditor = {},
    onDismissCategoryEditor = {},
    onRequestCategoryDelete = {},
    onDismissCategoryDelete = {},
    onAddCategory = {},
    onUpdateCategory = { _, _ -> },
    onDeleteCategory = {},
    onSetBudget = { _, _ -> },
    onSetAutoBookkeepingEnabled = {},
    onSetWeChatEnabled = {},
    onSetAlipayEnabled = {},
    onRequestNotificationAccess = {},
    onReplayRecent = {},
    onExportJson = {},
    onExportCsv = onExportCsv,
    onImportJson = {},
    onConfirmRestore = {}
)

private val screenCategory = ExpenseCategory(
    key = "food",
    name = "餐饮",
    colorArgb = 0xFFEF6C45L,
    builtIn = true,
    sortOrder = 0
)

private fun screenExpense(
    id: Long,
    name: String,
    amountCents: Long,
    spentAt: Long = 0L
) = ExpenseRecord(
    id = id,
    amountCents = amountCents,
    category = screenCategory,
    name = name,
    note = "",
    spentAt = spentAt,
    source = ExpenseSource.MANUAL,
    merchant = null
)
