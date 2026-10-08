package com.example.monthlyexpense.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.BudgetKind
import com.example.monthlyexpense.CategoryEditorTarget
import com.example.monthlyexpense.CategoryInput
import com.example.monthlyexpense.ExpenseEditInput
import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.ExpenseUiState
import com.example.monthlyexpense.NewExpenseInput
import com.example.monthlyexpense.budget.BudgetSummary
import com.example.monthlyexpense.ui.budget.BudgetDialog
import com.example.monthlyexpense.ui.categories.CategoryManagementDialog
import com.example.monthlyexpense.ui.forms.ExpenseEditDialog
import com.example.monthlyexpense.ui.forms.ExpenseEntryDialog
import com.example.monthlyexpense.ui.history.HistoryPanel
import com.example.monthlyexpense.ui.home.MonthlyScreen
import com.example.monthlyexpense.ui.menu.FeatureMenuItem
import com.example.monthlyexpense.ui.menu.FeatureMenuPanel
import com.example.monthlyexpense.ocrtest.debugOcrMenuItem
import com.example.monthlyexpense.screenshot.screenshotMenuItem

private val PageColor = Color(0xFFFFF9F2)
private val AccentColor = Color(0xFFDE6845)

internal data class ExpenseAppCallbacks(
    val onShowOverlay: (ExpenseOverlay) -> Unit,
    val onOpenHistory: () -> Unit,
    val onLoadMoreHistory: () -> Unit,
    val onRetryHistory: () -> Unit,
    val onDismissOverlay: () -> Unit,
    val onSubmitNewExpense: (NewExpenseInput) -> Unit,
    val onUpdateExpense: (Long, ExpenseEditInput) -> Unit,
    val onDeleteExpense: (Long) -> Unit,
    val onShowCategoryEditor: (CategoryEditorTarget) -> Unit,
    val onDismissCategoryEditor: () -> Unit,
    val onRequestCategoryDelete: (String) -> Unit,
    val onDismissCategoryDelete: () -> Unit,
    val onAddCategory: (CategoryInput) -> Unit,
    val onUpdateCategory: (String, CategoryInput) -> Unit,
    val onDeleteCategory: (String) -> Unit,
    val onSetBudget: (BudgetKind, Long) -> Unit,
    val onSetAutoBookkeepingEnabled: (Boolean) -> Unit,
    val onSetWeChatEnabled: (Boolean) -> Unit,
    val onSetAlipayEnabled: (Boolean) -> Unit,
    val onRequestNotificationAccess: () -> Unit,
    val onReplayRecent: () -> Unit,
    val onExportJson: () -> Unit,
    val onExportCsv: () -> Unit,
    val onImportJson: () -> Unit,
    val onConfirmRestore: () -> Unit,
    val onSelectHistoryMonth: (java.time.YearMonth?) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenCategoryKeywords: (String, String?, Long?) -> Unit = { _, _, _ -> },
    val onDismissCorrection: () -> Unit = {},
    val onRepairNotifications: () -> Unit = {},
    val onOpenSearch: () -> Unit = {},
    val search: com.example.monthlyexpense.ui.search.ExpenseSearchCallbacks = com.example.monthlyexpense.ui.search.ExpenseSearchCallbacks()
)

@Composable
internal fun ExpenseAppTheme(fontColor: Long? = null, cards: com.example.monthlyexpense.ui.settings.CardAppearance = com.example.monthlyexpense.ui.settings.CardAppearance(), content: @Composable () -> Unit) {
    val customColor = fontColor?.let { Color(it.toInt()) }
    val colors = lightColorScheme(
        primary = AccentColor,
        background = PageColor,
        surface = Color.White,
        onBackground = customColor ?: Color(0xFF292521)
    )
    MaterialTheme(colorScheme = if (customColor == null) colors else colors.copy(
        onSurface = customColor, onSurfaceVariant = customColor, onBackground = customColor
    )) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.example.monthlyexpense.ui.settings.LocalCustomFontColor provides customColor,
            com.example.monthlyexpense.ui.settings.LocalCardAppearance provides cards,
            androidx.compose.material3.LocalContentColor provides (customColor ?: colors.onBackground)
        ) { content() }
    }
}

@Composable
internal fun ExpenseAppScreen(
    state: ExpenseUiState,
    callbacks: ExpenseAppCallbacks,
    background: com.example.monthlyexpense.ui.background.BackgroundImage? = null,
    backgroundOverlay: @Composable () -> Unit = {},
    fontColor: Long? = null,
    cardAppearance: com.example.monthlyexpense.ui.settings.CardAppearance = com.example.monthlyexpense.ui.settings.CardAppearance(),
    notificationOperationBusy: Boolean = false,
    notificationOperationProgress: String? = null,
    categoryRules: List<com.example.monthlyexpense.classification.CategoryRuleSet> = emptyList()
) {
    val restoring = state.pending.restoring
    val overlay = state.overlay
    val totals = remember(state.expenses) { com.example.monthlyexpense.ExpenseTotals.from(state.expenses) }
    val summary = BudgetSummary.from(
        monthlySpentCents = totals.monthlyCents,
        monthlyBudgetCents = state.monthlyBudgetCents,
        todaySpentCents = state.todayTotalCents,
        dailyBudgetCents = state.dailyBudgetCents
    )

    ExpenseAppTheme(fontColor, cardAppearance) {
        Box(Modifier.fillMaxSize()) {
            background?.let { com.example.monthlyexpense.ui.background.BackgroundLayer(it) }
            Scaffold(
                containerColor = if (background == null) PageColor else Color.Transparent,
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (!restoring) callbacks.onShowOverlay(ExpenseOverlay.Entry)
                        },
                        text = { Text("记一笔", fontWeight = FontWeight.Bold) },
                        icon = { Text("＋", fontSize = 22.sp) }
                    )
                }
            ) { padding ->
                MonthlyScreen(
                    categoryTotals = totals.byCategory,
                    expenses = state.expenses,
                    categories = state.categories,
                    summary = summary,
                    notificationState = state.notificationState,
                    autoEnabled = state.autoBookkeeping.enabled,
                    weChatEnabled = state.autoBookkeeping.weChatEnabled,
                    alipayEnabled = state.autoBookkeeping.alipayEnabled,
                    onAutoChanged = callbacks.onSetAutoBookkeepingEnabled,
                    onWeChatChanged = callbacks.onSetWeChatEnabled,
                    onAlipayChanged = callbacks.onSetAlipayEnabled,
                    onRequestAccess = callbacks.onRequestNotificationAccess,
                    onReplayRecent = callbacks.onReplayRecent,
                    onRepairNotifications = callbacks.onRepairNotifications,
                    notificationOperationBusy = notificationOperationBusy || restoring,
                    notificationOperationProgress = notificationOperationProgress,
                    onOpenMenu = { callbacks.onShowOverlay(ExpenseOverlay.Menu) },
                    onOpenCategories = {
                        if (!restoring) callbacks.onShowOverlay(ExpenseOverlay.Categories)
                    },
                    onOpenSearch = { if (!restoring) callbacks.onOpenSearch() },
                    onOpenSpecialBudgetDetails = {
                        if (!restoring) callbacks.onShowOverlay(ExpenseOverlay.SpecialBudgetDetails)
                    },
                    onEditMonthlyBudget = {
                        if (!restoring) {
                            callbacks.onShowOverlay(ExpenseOverlay.EditBudget(BudgetKind.MONTHLY))
                        }
                    },
                    onEditDailyBudget = {
                        if (!restoring) {
                            callbacks.onShowOverlay(ExpenseOverlay.EditBudget(BudgetKind.DAILY))
                        }
                    },
                    onEditExpense = {
                        if (!restoring) callbacks.onShowOverlay(ExpenseOverlay.EditExpense(it.id))
                    },
                    onDelete = {
                        if (!restoring) callbacks.onDeleteExpense(it)
                    },
                    modifier = Modifier.padding(padding)
                )
            }

            AnimatedVisibility(
                visible = overlay == ExpenseOverlay.Menu,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = .35f))
                        .clickable(onClick = callbacks.onDismissOverlay)
                )
            }
            AnimatedVisibility(
                visible = overlay == ExpenseOverlay.Menu,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it }
            ) {
                FeatureMenuPanel(
                    items = listOf(
                        FeatureMenuItem("设置", enabled = !restoring) { callbacks.onOpenSettings() },
                        FeatureMenuItem("历史账单") {
                            callbacks.onOpenHistory()
                        },
                        FeatureMenuItem("导出/导入") {
                            callbacks.onShowOverlay(ExpenseOverlay.BackupOptions)
                        },
                        FeatureMenuItem(
                            title = "导出表格（CSV）",
                            enabled = !state.pending.exportPreparing && !restoring
                        ) {
                            callbacks.onDismissOverlay()
                            callbacks.onExportCsv()
                        }
                    ) + screenshotMenuItem(!restoring) + listOfNotNull(debugOcrMenuItem()),
                    onClose = callbacks.onDismissOverlay
                )
            }

            AnimatedVisibility(
                visible = overlay == ExpenseOverlay.History,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = .35f))
                        .clickable(onClick = callbacks.onDismissOverlay)
                )
            }
            AnimatedVisibility(
                visible = overlay == ExpenseOverlay.History,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it }
            ) {
                HistoryPanel(
                    state = state.history,
                    onClose = callbacks.onDismissOverlay,
                    onLoadMore = callbacks.onLoadMoreHistory,
                    onRetry = callbacks.onRetryHistory,
                    onSelectMonth = callbacks.onSelectHistoryMonth,
                    onBackToMonths = { callbacks.onSelectHistoryMonth(null) },
                    onEditExpense = { if (!restoring) callbacks.onShowOverlay(ExpenseOverlay.EditExpense(it)) }
                )
            }
            if (overlay == ExpenseOverlay.Search || state.search.editingExpense != null) {
                com.example.monthlyexpense.ui.search.ExpenseSearchPanel(state.search, state.categories, callbacks.search)
            }
            backgroundOverlay()
        }

        state.categoryCorrection?.takeIf { it.epoch == state.displayedDataEpoch && !restoring }?.let { correction ->
            val category = state.categories.find { it.key == correction.categoryKey }
            var useName by remember(correction) { mutableStateOf(false) }
            AlertDialog(onDismissRequest = callbacks.onDismissCorrection,
                title = { Text(if (useName) "使用消费名称？" else "已改为${category?.name ?: "所选分类"}") },
                text = { Text(if (useName) "没有商户信息。是否用消费名称“${correction.name}”作为关键词？你可以在下一页修改。"
                    else "可以主动添加关键词，让以后相同商户的消费自动归类；本次修改不会自动创建规则。") },
                confirmButton = { TextButton({
                    if (correction.merchant.isNullOrBlank() && !useName) useName = true
                    else { callbacks.onOpenCategoryKeywords(correction.categoryKey, correction.merchant?.takeIf { it.isNotBlank() } ?: correction.name, correction.epoch); callbacks.onDismissCorrection() }
                }) { Text(if (useName) "使用名称" else "添加自动归类关键词") } },
                dismissButton = { TextButton(callbacks.onDismissCorrection) { Text("完成") } })
        }

        if (overlay == ExpenseOverlay.Entry && state.categories.isNotEmpty()) {
            ExpenseEntryDialog(
                categories = state.categories,
                saving = state.pending.entrySaving,
                onDismiss = callbacks.onDismissOverlay,
                rules = categoryRules,
                onSave = { amount, categoryKey, name, note, explicit ->
                    callbacks.onSubmitNewExpense(NewExpenseInput(amount, categoryKey, name, note, explicit))
                }
            )
        }

        if (overlay == ExpenseOverlay.SpecialBudgetDetails) {
            val specialExpenses = remember(state.expenses) { state.expenses.filter { it.isSpecial } }
            com.example.monthlyexpense.ui.home.SpecialBudgetDetailsDialog(
                expenses = specialExpenses,
                onDismiss = callbacks.onDismissOverlay,
                onEdit = { callbacks.onShowOverlay(ExpenseOverlay.EditExpense(it.id, returnToSpecialBudget = true)) },
                onDelete = callbacks.onDeleteExpense,
                busy = state.pending.anyWrite
            )
        }

        (overlay as? ExpenseOverlay.EditExpense)?.let { edit ->
            (state.search.editingExpense?.expense?.takeIf { it.id == edit.expenseId }
                ?: (state.expenses + state.history.items).firstOrNull { it.id == edit.expenseId })?.let { expense ->
                ExpenseEditDialog(
                    expense = expense,
                    categories = state.categories,
                    saving = state.pending.expenseEditSaving,
                    onDismiss = callbacks.onDismissOverlay,
                    onSave = { name, note, categoryKey, date, time, explicit, isSpecial ->
                        callbacks.onUpdateExpense(expense.id, ExpenseEditInput(name, note, categoryKey, date, time, explicit, isSpecial))
                    }
                )
            }
        }

        if (overlay == ExpenseOverlay.Categories) {
            val allCategoryTotals = remember(state.expenses) {
                state.expenses.groupBy { it.category.key }.mapValues { (_, records) -> records.sumOf { it.amountCents } }
            }
            CategoryManagementDialog(
                categories = state.categories,
                categoryTotals = allCategoryTotals,
                rules = categoryRules,
                onKeywords = { callbacks.onOpenCategoryKeywords(it, null, null) },
                management = state.categoryManagement,
                pending = state.pending,
                onDismiss = {
                    callbacks.onDismissCategoryEditor()
                    callbacks.onDismissCategoryDelete()
                    callbacks.onDismissOverlay()
                },
                onShowEditor = callbacks.onShowCategoryEditor,
                onDismissEditor = callbacks.onDismissCategoryEditor,
                onRequestDelete = callbacks.onRequestCategoryDelete,
                onDismissDelete = callbacks.onDismissCategoryDelete,
                onAdd = { name, color -> callbacks.onAddCategory(CategoryInput(name, color)) },
                onUpdate = { category, name, color ->
                    callbacks.onUpdateCategory(
                        category.key,
                        CategoryInput(if (category.builtIn) null else name, color)
                    )
                },
                onDelete = { callbacks.onDeleteCategory(it.key) }
            )
        }

        (overlay as? ExpenseOverlay.EditBudget)?.let { edit ->
            val initialCents = when (edit.kind) {
                BudgetKind.MONTHLY -> state.monthlyBudgetCents
                BudgetKind.DAILY -> state.dailyBudgetCents
            }
            BudgetDialog(
                kind = edit.kind,
                initialCents = initialCents,
                saving = state.pending.budgetSaving,
                onDismiss = callbacks.onDismissOverlay,
                onSave = { callbacks.onSetBudget(edit.kind, it) }
            )
        }

        if (overlay == ExpenseOverlay.BackupOptions) {
            AlertDialog(
                onDismissRequest = callbacks.onDismissOverlay,
                title = { Text("导出/导入") },
                text = { Text("JSON 备份包含全部账单、分类、预算和自动记账设置。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            callbacks.onDismissOverlay()
                            callbacks.onExportJson()
                        },
                        enabled = !state.pending.exportPreparing && !restoring
                    ) { Text("导出 JSON") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = callbacks.onDismissOverlay) { Text("取消") }
                        TextButton(
                            onClick = {
                                callbacks.onDismissOverlay()
                                callbacks.onImportJson()
                            },
                            enabled = !restoring
                        ) { Text("导入 JSON") }
                    }
                }
            )
        }

        if (overlay == ExpenseOverlay.ConfirmRestore) {
            AlertDialog(
                onDismissRequest = callbacks.onDismissOverlay,
                title = { Text("完全恢复备份？") },
                text = {
                    Text("当前全部账单、分类和预算将被备份内容替换。此操作无法撤销。")
                },
                confirmButton = {
                    Button(
                        onClick = callbacks.onConfirmRestore,
                        enabled = !restoring
                    ) { Text("确认恢复") }
                },
                dismissButton = {
                    TextButton(
                        onClick = callbacks.onDismissOverlay,
                        enabled = !restoring
                    ) { Text("取消") }
                }
            )
        }
    }
}
