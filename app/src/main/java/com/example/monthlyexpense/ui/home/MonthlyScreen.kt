package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.budget.BudgetSummary
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.ui.budget.DailyBudgetCard
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun MonthlyScreen(
    expenses: List<ExpenseRecord>,
    categories: List<ExpenseCategory>,
    summary: BudgetSummary,
    notificationState: NotificationListenerConnectionState,
    autoEnabled: Boolean,
    weChatEnabled: Boolean,
    alipayEnabled: Boolean,
    onAutoChanged: (Boolean) -> Unit,
    onWeChatChanged: (Boolean) -> Unit,
    onAlipayChanged: (Boolean) -> Unit,
    onRequestAccess: () -> Unit,
    onReplayRecent: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenCategories: () -> Unit,
    onEditMonthlyBudget: () -> Unit,
    onEditDailyBudget: () -> Unit,
    onEditExpense: (ExpenseRecord) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSearch: () -> Unit = {},
    onOpenSpecialBudgetDetails: () -> Unit = {},
    onRepairNotifications: () -> Unit = {},
    notificationOperationBusy: Boolean = false,
    notificationOperationProgress: String? = null,
    categoryTotals: Map<String, Long>
) {
    val (specialExpenses, dailyExpenses) = androidx.compose.runtime.remember(expenses) {
        expenses.partition { it.isSpecial }
    }
    val specialTotal = androidx.compose.runtime.remember(specialExpenses) { specialExpenses.sumOf { it.amountCents } }
    val totals = androidx.compose.runtime.remember(categories, categoryTotals) {
        categories.associateWith { category -> categoryTotals[category.key] ?: 0L }
    }
    val title = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy年 M月"))
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("本月消费概览", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                }
                IconButton(onClick = onOpenMenu) {
                    Text("☰", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            AutoBookkeepingCard(
                notificationState,
                autoEnabled,
                weChatEnabled,
                alipayEnabled,
                onAutoChanged,
                onWeChatChanged,
                onAlipayChanged,
                onRequestAccess,
                onReplayRecent,
                onRepairNotifications,
                notificationOperationBusy,
                notificationOperationProgress
            )
        }
        item { NotificationManagementPanel() }
        item { MonthlyOverviewCard(totals, summary, onEditMonthlyBudget, onOpenCategories) }
        item { SpecialBudgetCard(specialTotal, onOpenSpecialBudgetDetails) }
        item { MonthlyTotalCard(summary.monthlySpentCents, specialTotal) }
        item { DailyBudgetCard(summary, onEditDailyBudget) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("消费记录", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenSearch) { Text("搜索") }
            }
        }
        if (dailyExpenses.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                    Text("本月还没有日常消费记录", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                }
            }
        } else {
            items(dailyExpenses, key = { it.id }) { expense ->
                ExpenseRow(expense, { onEditExpense(expense) }, { onDelete(expense.id) })
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}
