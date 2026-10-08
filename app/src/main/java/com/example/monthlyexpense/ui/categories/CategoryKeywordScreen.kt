package com.example.monthlyexpense.ui.categories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.classification.*

internal data class CategoryKeywordCallbacks(
    val cancel: () -> Unit = {}, val save: () -> Unit = {}, val input: (String) -> Unit = {},
    val add: () -> Unit = {}, val mode: (KeywordMatchMode) -> Unit = {},
    val enabled: (Boolean) -> Unit = {}, val field: (CategoryMatchField) -> Unit = {},
    val edit: (String) -> Unit = {}, val remove: (String) -> Unit = {},
    val merchant: (String) -> Unit = {}, val name: (String) -> Unit = {},
    val preview: () -> Unit = {}, val openConflict: (String) -> Unit = {}
)

@Composable
internal fun CategoryKeywordScreen(state: CategoryKeywordUiState, categories: List<ExpenseCategory>, callbacks: CategoryKeywordCallbacks) {
    Dialog(onDismissRequest = { if (!state.saving) callbacks.cancel() },
        // Compose 1.7.6's false path remeasures against screenHeightDp instead of the
        // available window. Retain native measurement and request full width on the window.
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = true)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            // This window uses native system fitting; retain resize behavior on older Android too.
            @Suppress("DEPRECATION")
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        CategoryKeywordContent(state, categories, callbacks)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryKeywordContent(state: CategoryKeywordUiState, categories: List<ExpenseCategory>, callbacks: CategoryKeywordCallbacks,
    modifier: Modifier = Modifier, safeInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime)) {
    val categoryName = categories.find { it.key == state.draft.categoryKey }?.name ?: "分类"
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(safeInsets).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // One scroll viewport includes the actions, so even a small window or large
            // keyboard can never leave Save/Cancel stranded outside the scrollable content.
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$categoryName · 自动归类", style = MaterialTheme.typography.headlineSmall)
                    Text("仅对保存后的新消费生效；手动选择分类始终优先。")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("启用自动归类", Modifier.weight(1f))
                        Switch(state.draft.enabled, callbacks.enabled, enabled = !state.saving)
                    }
                    Text("匹配范围", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryMatchField.entries.forEach { field -> FilterChip(state.draft.matchField == field,
                            { callbacks.field(field) }, enabled = !state.saving, label = { Text(field.label()) }) }
                    }
                    Text("关键词（任意一个命中即可）", style = MaterialTheme.typography.titleMedium)
                    if (state.draft.keywords.isEmpty()) Text("尚未添加关键词")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.draft.keywords.forEach { word ->
                            Surface(shape = MaterialTheme.shapes.small,
                                color = if (state.editingId == word.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ callbacks.edit(word.id) }, enabled = !state.saving, modifier = Modifier.weight(1f, fill = false)) {
                                        Text(word.keyword + if (word.mode == KeywordMatchMode.EXACT) " · 精确" else "")
                                    }
                                    IconButton({ callbacks.remove(word.id) }, enabled = !state.saving,
                                        modifier = Modifier.testTag("remove-${word.id}")) { Text("×") }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(state.input, callbacks.input, Modifier.weight(1f), enabled = !state.saving,
                            label = { Text(if (state.editingId == null) "新关键词" else "编辑关键词") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { callbacks.add() }))
                        TextButton(callbacks.add, enabled = !state.saving, modifier = Modifier.testTag("add-keyword")) { Text(if (state.editingId == null) "＋" else "更新") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeywordMatchMode.entries.forEach { mode -> FilterChip(state.inputMode == mode, { callbacks.mode(mode) }, enabled = !state.saving,
                            label = { Text(if (mode == KeywordMatchMode.EXACT) "完全一致" else "包含") }) }
                    }
                    val normalized = normalizeKeyword(state.input)
                    if (normalized.isNotEmpty() && (normalized.codePointCount(0, normalized.length) == 1 || normalized in setOf("支付", "商店")))
                        Text("这个关键词较宽泛，容易误分类，建议先测试匹配。", color = MaterialTheme.colorScheme.error)
                    Text("每类最多 50 个；点击标签可编辑。空格、逗号不会拆成多个关键词。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("测试匹配", style = MaterialTheme.typography.titleMedium)
                    Text("使用当前草稿和其他分类已保存的规则，不会修改账单。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(state.testMerchant, callbacks.merchant, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("测试商户") })
                    OutlinedTextField(state.testName, callbacks.name, Modifier.fillMaxWidth(), enabled = !state.saving, label = { Text("测试消费名称") })
                    OutlinedButton(callbacks.preview, enabled = !state.saving) { Text("测试匹配") }
                    state.preview?.let { result ->
                        when (result) {
                            CategoryMatch.NoMatch -> Text("未命中关键词；未启用的分类不参与匹配。")
                            is CategoryMatch.Matched -> Text("归入：${categories.find { it.key == result.categoryKey }?.name ?: result.categoryKey}")
                            is CategoryMatch.Conflict -> Text("分类待确认：多个分类同时命中", color = MaterialTheme.colorScheme.error)
                        }
                        val hits = when (result) { is CategoryMatch.Matched -> result.hits; is CategoryMatch.Conflict -> result.hits; else -> emptyList() }
                        hits.forEach { Text("${categories.find { c -> c.key == it.categoryKey }?.name ?: it.categoryKey}：${it.field.label()}${if (it.mode == KeywordMatchMode.EXACT) "完全一致" else "包含"}“${it.keyword}”") }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.conflictingCategoryKey?.let { key -> TextButton({ callbacks.openConflict(key) }, enabled = !state.saving) {
                        Text("放弃当前草稿，编辑${categories.find { it.key == key }?.name ?: "冲突分类"}")
                    } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(callbacks.cancel, Modifier.weight(1f).heightIn(min = 48.dp).testTag("keyword-cancel"), enabled = !state.saving) { Text("取消") }
                    Button(callbacks.save, Modifier.weight(1f).heightIn(min = 48.dp).testTag("keyword-save"), enabled = !state.saving) { Text(if (state.saving) "保存中…" else "保存") }
                }
            }
        }
    }
}

internal fun CategoryMatchField.label(): String = when (this) {
    CategoryMatchField.MERCHANT_OR_NAME -> "商户或名称"
    CategoryMatchField.MERCHANT -> "商户"
    CategoryMatchField.NAME -> "消费名称"
}
