package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ui.formatMoney

@Composable
internal fun SpecialBudgetDetailsDialog(
    expenses: List<ExpenseRecord>,
    onDismiss: () -> Unit,
    onEdit: (ExpenseRecord) -> Unit,
    onDelete: (Long) -> Unit,
    busy: Boolean = false
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("专项预算详情") },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().fillMaxHeight(.75f).testTag("special-budget-details"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("本月专项支出 ${formatMoney(expenses.sumOf { it.amountCents })} · ${expenses.size} 笔")
                }
                if (expenses.isEmpty()) {
                    item { Text("本月暂无专项账单") }
                }
                items(expenses, key = { it.id }) { expense ->
                    ExpenseRow(expense, onEdit = { onEdit(expense) }, onDelete = { onDelete(expense.id) }, enabled = !busy)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("返回") }
        }
    )
}
