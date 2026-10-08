package com.example.monthlyexpense.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.CategoryEditorTarget
import com.example.monthlyexpense.CategoryManagementUiState
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpensePendingState
import com.example.monthlyexpense.ui.formatMoney

@Composable
internal fun CategoryManagementDialog(
    categories: List<ExpenseCategory>,
    categoryTotals: Map<String, Long>,
    management: CategoryManagementUiState,
    pending: ExpensePendingState,
    onDismiss: () -> Unit,
    onShowEditor: (CategoryEditorTarget) -> Unit,
    onDismissEditor: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onAdd: (String, Long) -> Unit,
    onUpdate: (ExpenseCategory, String?, Long) -> Unit,
    onDelete: (ExpenseCategory) -> Unit,
    rules: List<com.example.monthlyexpense.classification.CategoryRuleSet> = emptyList(),
    onKeywords: (String) -> Unit = {}
) {
    val saving = pending.categorySaving
    val deleting = pending.categoryDeleting
    AlertDialog(
        onDismissRequest = { if (!saving && !deleting) onDismiss() },
        title = { Text("分类管理") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.65f)) {
                item { Text("日常与专项共用分类，金额包含专项支出", fontSize = 12.sp) }
                items(categories, key = { it.key }) { category ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(18.dp).background(
                                Color(category.colorArgb.toInt()),
                                RoundedCornerShape(9.dp)
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.name, fontWeight = FontWeight.Bold)
                            val rule = rules.find { it.categoryKey == category.key }
                            Text(if (rule == null || rule.keywords.isEmpty()) "未配置关键词" else "自动归类${if (rule.enabled) "已开启" else "已停用"} · ${rule.keywords.size} 个关键词", fontSize = 12.sp)
                            TextButton({ onKeywords(category.key) }, enabled = !saving && !deleting) { Text("自动归类") }
                            Text(
                                (if (category.builtIn) "内置分类 · 可改颜色" else "自定义分类") +
                                    " · 本月总额 " + formatMoney(categoryTotals[category.key] ?: 0L),
                                color = com.example.monthlyexpense.ui.settings.secondaryTextColor(),
                                fontSize = 12.sp
                            )
                        }
                        TextButton(
                            onClick = { onShowEditor(CategoryEditorTarget.Existing(category.key)) },
                            enabled = !saving && !deleting
                        ) { Text("编辑") }
                        if (!category.builtIn) {
                            TextButton(
                                onClick = { onRequestDelete(category.key) },
                                enabled = !saving && !deleting
                            ) { Text("删除", color = Color(0xFFB91C1C)) }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFEEEAE5))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onShowEditor(CategoryEditorTarget.New) },
                enabled = !saving && !deleting
            ) { Text("添加分类") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving && !deleting) { Text("完成") }
        }
    )

    management.editor?.let { target ->
        val existing = when (target) {
            CategoryEditorTarget.New -> null
            is CategoryEditorTarget.Existing -> categories.firstOrNull { it.key == target.categoryKey }
        }
        if (target == CategoryEditorTarget.New || existing != null) {
            CategoryEditorDialog(
                existing = existing,
                onKeywords = { existing?.let { onKeywords(it.key) } },
                saving = saving,
                onDismiss = onDismissEditor,
                onSave = { name, color ->
                    if (existing == null) onAdd(name, color) else onUpdate(existing, name, color)
                }
            )
        }
    }

    management.deleteTargetKey?.let { key ->
        categories.firstOrNull { it.key == key }?.let { category ->
            AlertDialog(
                onDismissRequest = { if (!deleting) onDismissDelete() },
                title = { Text("删除分类") },
                text = {
                    Text(
                        "确定删除“${category.name}”吗？如果仍有消费记录使用它，系统会提示你先更改这些记录的分类。"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { onDelete(category) },
                        enabled = !deleting && !saving
                    ) { Text("确认删除") }
                },
                dismissButton = {
                    TextButton(onClick = onDismissDelete, enabled = !deleting) { Text("取消") }
                }
            )
        }
    }
}
