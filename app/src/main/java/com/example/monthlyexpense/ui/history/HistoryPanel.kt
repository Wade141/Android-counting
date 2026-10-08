package com.example.monthlyexpense.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.key
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.ui.expenseDate
import com.example.monthlyexpense.ui.formatMoney
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val PageColor = Color(0xFFFFF9F2)

@Composable
internal fun HistoryPanel(
    state: HistoryUiState,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSelectMonth: (YearMonth) -> Unit,
    onBackToMonths: () -> Unit,
    onEditExpense: (Long) -> Unit = {}
) {
    val selectedMonth = state.selectedMonth
    val summary = state.months.firstOrNull { it.month == selectedMonth }
    BackHandler { if (selectedMonth != null) onBackToMonths() else onClose() }
    val formatter = DateTimeFormatter.ofPattern("yyyy年 M月")
    Surface(Modifier.fillMaxHeight().fillMaxWidth(.86f), color = PageColor, shadowElevation = 18.dp) {
        key(selectedMonth) {
            LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("历史账单", Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Black)
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
                if (selectedMonth != null) {
                    item { TextButton(onClick = onBackToMonths) { Text("返回月份列表") } }
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(selectedMonth.format(formatter), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("本月总支出", color = com.example.monthlyexpense.ui.settings.secondaryTextColor(), modifier = Modifier.padding(top = 8.dp))
                            Text(formatMoney(summary?.totalCents ?: 0L), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("共 ${summary?.count ?: 0} 笔", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                        }
                    }
                }
                if (state.loading || state.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                if (state.loadFailed) {
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("历史账单加载失败，请重试", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                }
                if (state.initialLoadComplete && (if (selectedMonth == null) state.months.isEmpty() else state.items.isEmpty()) && !state.loadFailed) {
                    item { Text(if (selectedMonth == null) "还没有已归档的账单" else "该月份暂无账单", color = com.example.monthlyexpense.ui.settings.secondaryTextColor()) }
                }
                if (selectedMonth == null) {
                    items(state.months, key = { "month-" + it.month }) { month ->
                        Surface(
                            onClick = { onSelectMonth(month.month) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(month.month.format(formatter), fontWeight = FontWeight.Bold)
                                    Text("共 ${month.count} 笔", color = com.example.monthlyexpense.ui.settings.secondaryTextColor(), fontSize = 12.sp)
                                }
                                Text(formatMoney(month.totalCents), fontWeight = FontWeight.Bold)
                                Text(" ›", color = com.example.monthlyexpense.ui.settings.secondaryTextColor(), fontSize = 24.sp)
                            }
                        }
                    }
                } else {
                    items(state.items, key = { "history-" + it.id }) { expense ->
                        Row(
                            Modifier.fillMaxWidth().clickable(onClickLabel = "编辑记录") { onEditExpense(expense.id) }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(9.dp).background(
                                    Color(expense.category.colorArgb.toInt()),
                                    RoundedCornerShape(5.dp)
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(expense.name.ifBlank { expense.category.name })
                                if (!expense.isSpecial) com.example.monthlyexpense.ui.ClassificationReason(expense.classificationOrigin, expense.classificationHits)
                                Text(expenseDate(expense.spentAt), color = com.example.monthlyexpense.ui.settings.secondaryTextColor(), fontSize = 12.sp)
                            }
                            Text(formatMoney(expense.amountCents), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (
                    state.initialLoadComplete &&
                    selectedMonth != null &&
                    state.hasMore &&
                    !state.loading &&
                    !state.loadingMore &&
                    !state.loadFailed
                ) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}
