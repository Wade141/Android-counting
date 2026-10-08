package com.example.monthlyexpense

import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.data.HistoryCursor

data class ExpenseUiState(
    val expenses: List<ExpenseRecord> = emptyList(),
    val history: HistoryUiState = HistoryUiState(),
    val search: com.example.monthlyexpense.search.ExpenseSearchUiState = com.example.monthlyexpense.search.ExpenseSearchUiState(),
    val categories: List<ExpenseCategory> = emptyList(),
    val monthlyBudgetCents: Long = 0L,
    val dailyBudgetCents: Long = 0L,
    val todayTotalCents: Long = 0L,
    val notificationState: NotificationListenerConnectionState =
        NotificationListenerConnectionState.NOT_AUTHORIZED,
    val autoBookkeeping: AutoBookkeepingUiState = AutoBookkeepingUiState(),
    val pending: ExpensePendingState = ExpensePendingState(),
    val overlay: ExpenseOverlay? = null,
    val categoryManagement: CategoryManagementUiState = CategoryManagementUiState(),
    val categoryCorrection: CategoryCorrection? = null,
    val displayedDataEpoch: Long = 0L,
    val initialLoadComplete: Boolean = false
)

data class CategoryCorrection(val categoryKey: String, val merchant: String?, val name: String, val epoch: Long)

data class HistoryUiState(
    val months: List<com.example.monthlyexpense.data.HistoryMonth> = emptyList(),
    val selectedMonth: java.time.YearMonth? = null,
    val items: List<ExpenseRecord> = emptyList(),
    val nextCursor: HistoryCursor? = null,
    val initialLoadComplete: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val loadFailed: Boolean = false,
    val failedCursor: HistoryCursor? = null
)

data class AutoBookkeepingUiState(
    val enabled: Boolean = false,
    val weChatEnabled: Boolean = true,
    val alipayEnabled: Boolean = true,
    val enabledWritePending: Boolean = false,
    val weChatWritePending: Boolean = false,
    val alipayWritePending: Boolean = false
)

data class ExpensePendingState(
    val entrySaving: Boolean = false,
    val expenseEditSaving: Boolean = false,
    val budgetSaving: Boolean = false,
    val categorySaving: Boolean = false,
    val categoryDeleting: Boolean = false,
    val exportPreparing: Boolean = false,
    val restoring: Boolean = false
) {
    val anyWrite get() = entrySaving || expenseEditSaving || budgetSaving ||
        categorySaving || categoryDeleting || restoring
}

enum class BudgetKind { MONTHLY, DAILY }

enum class BackupExportFormat { JSON, CSV }

sealed interface CategoryEditorTarget {
    data object New : CategoryEditorTarget

    data class Existing(val categoryKey: String) : CategoryEditorTarget
}

data class CategoryManagementUiState(
    val editor: CategoryEditorTarget? = null,
    val deleteTargetKey: String? = null
)

sealed interface ExpenseOverlay {
    data object Menu : ExpenseOverlay

    data object History : ExpenseOverlay

    data object Search : ExpenseOverlay

    data object SpecialBudgetDetails : ExpenseOverlay

    data object BackupOptions : ExpenseOverlay

    data object Categories : ExpenseOverlay

    data object Entry : ExpenseOverlay

    data class EditExpense(val expenseId: Long, val returnToSpecialBudget: Boolean = false) : ExpenseOverlay

    data class EditBudget(val kind: BudgetKind) : ExpenseOverlay

    data object ConfirmRestore : ExpenseOverlay
}

data class NewExpenseInput(
    val amountCents: Long,
    val categoryKey: String,
    val name: String,
    val note: String,
    val categoryExplicit: Boolean = true
)

data class ExpenseEditInput(
    val name: String,
    val note: String,
    val categoryKey: String,
    val date: java.time.LocalDate? = null,
    val time: java.time.LocalTime? = null,
    val categoryExplicit: Boolean = false,
    val isSpecial: Boolean? = null
)

data class CategoryInput(val name: String?, val colorArgb: Long)

@JvmInline
value class UiMessage(val text: String)

sealed interface UiEvent {
    data class ShowMessage(val message: UiMessage) : UiEvent

    data class WriteDocument(
        val writeId: Long,
        val targetUri: String,
        val content: String
    ) : UiEvent
}
