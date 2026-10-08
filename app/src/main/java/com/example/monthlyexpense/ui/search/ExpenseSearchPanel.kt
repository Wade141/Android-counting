package com.example.monthlyexpense.ui.search

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.search.*
import com.example.monthlyexpense.ui.formatMoney
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ExpenseSearchCallbacks(
    val onClose: () -> Unit = {},
    val onKeyword: (String, Boolean) -> Unit = { _, _ -> },
    val onSubmit: () -> Unit = {},
    val onOpenFilters: () -> Unit = {},
    val onDraft: (ExpenseSearchFilters) -> Unit = {},
    val onCancelFilters: () -> Unit = {},
    val onResetDraft: () -> Unit = {},
    val onApplyFilters: () -> Unit = {},
    val onReplaceFilters: (ExpenseSearchFilters) -> Unit = {},
    val onClear: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onEdit: (Long) -> Unit = {}
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpenseSearchPanel(state: ExpenseSearchUiState, categories: List<ExpenseCategory>, callbacks: ExpenseSearchCallbacks) {
    BackHandler(onBack = callbacks.onClose)
    val keyboard = LocalSoftwareKeyboardController.current
    var textValue by remember { mutableStateOf(TextFieldValue(state.keyword)) }
    LaunchedEffect(Unit) {
        // A newly created text field has no IME composition, including after Activity recreation.
        if (state.isComposing) callbacks.onKeyword(state.keyword, false)
    }
    LaunchedEffect(state.keyword) {
        if (textValue.text != state.keyword) textValue = TextFieldValue(state.keyword)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onClose) { Text("返回") }
                Text("搜索账单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { keyboard?.hide(); callbacks.onOpenFilters() }) { Text("筛选") }
            }
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it; callbacks.onKeyword(it.text, it.composition != null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("名称、备注或商户") },
                trailingIcon = {
                    if (state.keyword.isNotEmpty()) TextButton(onClick = {
                        textValue = TextFieldValue(""); callbacks.onKeyword("", false); callbacks.onSubmit()
                    }) { Text("清空") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { callbacks.onSubmit(); keyboard?.hide() })
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    AppliedFilters(state.appliedFilters, categories, callbacks.onReplaceFilters)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索范围：全部账单", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (state.keyword.isNotBlank() || state.appliedFilters != ExpenseSearchFilters()) {
                            TextButton(onClick = callbacks.onClear) { Text("重置条件") }
                        }
                    }
                    if (state.filterDraft == null) state.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when {
                        state.loading || state.updating -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(if (state.updating) "  正在更新结果…" else "  正在搜索…")
                        }
                        !state.dirty -> state.summary?.let {
                            Text("共 ${it.count} 笔 · 合计 ${formatMoney(it.totalCents)}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (!state.dirty && !state.loading && state.summary?.count == 0L) item {
                    Text("没有找到匹配记录，试试其他关键词或减少筛选条件。", Modifier.padding(vertical = 24.dp))
                }
                items(state.items, key = { it.id }) { expense ->
                    Card(onClick = { keyboard?.hide(); callbacks.onEdit(expense.id) },
                        enabled = state.canEdit && !state.dirty,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(expense.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Text(formatMoney(expense.amountCents), fontWeight = FontWeight.Bold)
                            }
                            Text("${expense.category.name} · ${sourceLabel(expense.source)}", style = MaterialTheme.typography.bodySmall)
                            Text(Instant.ofEpochMilli(expense.spentAt).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")), style = MaterialTheme.typography.bodySmall)
                            expense.merchant?.takeIf { it.isNotBlank() && it != expense.name }?.let { Text("商户：$it") }
                            if (!expense.isSpecial) com.example.monthlyexpense.ui.ClassificationReason(expense.classificationOrigin, expense.classificationHits)
                            if (expense.note.isNotBlank()) Text(expense.note)
                        }
                    }
                }
                item {
                    when {
                        state.error != null -> Column {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = callbacks.onRetry) { Text("重试") }
                        }
                        state.loadingMore -> CircularProgressIndicator(Modifier.size(24.dp))
                        state.hasMore && !state.dirty -> TextButton(onClick = callbacks.onMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                        state.items.isNotEmpty() && !state.dirty -> Text("已显示全部结果", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    state.filterDraft?.let { draft -> FilterDialog(draft, state.validationError, categories, callbacks) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppliedFilters(filters: ExpenseSearchFilters, categories: List<ExpenseCategory>, replace: (ExpenseSearchFilters) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (filters.datePreset != SearchDatePreset.ALL) InputChip(selected = true,
            onClick = { replace(filters.copy(datePreset = SearchDatePreset.ALL, startDate = null, endDate = null)) },
            label = { Text((if (filters.datePreset == SearchDatePreset.CUSTOM) "${filters.startDate} 至 ${filters.endDate}" else filters.datePreset.label) + " ×") })
        filters.categoryKeys.sorted().forEach { key -> InputChip(selected = true,
            onClick = { replace(filters.copy(categoryKeys = filters.categoryKeys - key)) },
            label = { Text("${categories.firstOrNull { it.key == key }?.name ?: "已移除分类"} ×") }) }
        if (filters.minAmountText.isNotBlank() || filters.maxAmountText.isNotBlank()) InputChip(selected = true,
            onClick = { replace(filters.copy(minAmountText = "", maxAmountText = "")) },
            label = { Text("¥${filters.minAmountText.ifBlank { "0" }} 至 ${filters.maxAmountText.ifBlank { "不限" }} ×") })
        filters.sources.sortedBy { it.name }.forEach { source -> InputChip(selected = true,
            onClick = { replace(filters.copy(sources = filters.sources - source)) }, label = { Text("${sourceLabel(source)} ×") }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(draft: ExpenseSearchFilters, error: String?, categories: List<ExpenseCategory>, callbacks: ExpenseSearchCallbacks) {
    Dialog(onDismissRequest = callbacks.onCancelFilters, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        // This full-screen window delegates system bar / IME insets to Compose.
        decorFitsSystemWindows = false
    )) {
        ExpenseSearchFilterContent(draft, error, categories, callbacks)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpenseSearchFilterContent(
    draft: ExpenseSearchFilters,
    error: String?,
    categories: List<ExpenseCategory>,
    callbacks: ExpenseSearchCallbacks,
    modifier: Modifier = Modifier,
    safeInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime)
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(safeInsets).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选账单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = callbacks.onCancelFilters) { Text("取消") }
                }
                // Wrap short forms; when space is limited, only the fields scroll above the actions.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("日期", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchDatePreset.entries.forEach { preset -> FilterChip(selected = draft.datePreset == preset,
                            onClick = { callbacks.onDraft(draft.copy(datePreset = preset)) }, label = { Text(preset.label) }) }
                    }
                    if (draft.datePreset == SearchDatePreset.CUSTOM) {
                        DateButton("开始日期", draft.startDate) { callbacks.onDraft(draft.copy(startDate = it)) }
                        DateButton("结束日期", draft.endDate) { callbacks.onDraft(draft.copy(endDate = it)) }
                    }
                    Text("分类（可多选）", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.categoryKeys.isEmpty(), onClick = { callbacks.onDraft(draft.copy(categoryKeys = emptySet())) }, label = { Text("全部分类") })
                        categories.forEach { category -> FilterChip(selected = category.key in draft.categoryKeys,
                            onClick = { callbacks.onDraft(draft.copy(categoryKeys = draft.categoryKeys.toggle(category.key))) }, label = { Text(category.name) }) }
                    }
                    Text("金额范围", fontWeight = FontWeight.Bold)
                    OutlinedTextField(draft.minAmountText, { callbacks.onDraft(draft.copy(minAmountText = it)) },
                        label = { Text("最低金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(draft.maxAmountText, { callbacks.onDraft(draft.copy(maxAmountText = it)) },
                        label = { Text("最高金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text("记账来源", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.sources.isEmpty(), onClick = { callbacks.onDraft(draft.copy(sources = emptySet())) }, label = { Text("全部来源") })
                        ExpenseSource.entries.forEach { source -> FilterChip(selected = source in draft.sources,
                            onClick = { callbacks.onDraft(draft.copy(sources = draft.sources.toggle(source))) }, label = { Text(sourceLabel(source)) }) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = callbacks.onResetDraft, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重置") }
                    Button(onClick = callbacks.onApplyFilters, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("应用筛选") }
                }
            }
        }
    }
}

@Composable
private fun DateButton(label: String, value: LocalDate?, onDate: (LocalDate) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val initial = value ?: LocalDate.now()
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }) { Text("$label：${value ?: "请选择"}") }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
private fun sourceLabel(source: ExpenseSource): String = when (source) {
    ExpenseSource.MANUAL -> "手动记账"
    ExpenseSource.WECHAT_AUTO -> "微信自动记账"
    ExpenseSource.ALIPAY_AUTO -> "支付宝自动记账"
    ExpenseSource.SCREENSHOT_OCR -> "截图记账"
}
