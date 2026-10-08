package com.example.monthlyexpense.screenshot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ui.formatMoney
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenshotEntryScreen(
    state: ScreenshotEntryState,
    onEdit: (ScreenshotField, String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onChooseImage: () -> Unit,
    onSave: () -> Unit,
    onReturn: () -> Unit,
    onDismissDuplicate: () -> Unit,
    onSaveDuplicate: () -> Unit,
    onReplace: (Boolean) -> Unit
) {
    val busy = state.saving || state.recognizing
    val saved = state.savedId != null
    BackHandler(enabled = state.saving) { /* Do not leave during the commit. */ }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onReturn, enabled = !state.saving) { Text("返回") }
            Text("截图记账", style = MaterialTheme.typography.headlineMedium)
            if (saved) {
                Text("保存成功", style = MaterialTheme.typography.titleLarge)
                Text("账目已保存到账本。")
                state.error?.let { Text(it) }
            } else {
                OutlinedButton(onClick = onChooseImage, enabled = !busy) { Text("从相册选择截图") }
                Text("请核对截图中的金额与交易状态。日期默认导入当天，时分秒采用确认保存时的系统时间；补记历史账单请修改日期。")
                if (state.recognizing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在自动识别截图…")
                    Text("可以先修正字段，识别完成后再确认保存。")
                }
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.evidence.take(8).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ScreenshotFieldInput("金额（元）", state.amount, !state.saving,
                    KeyboardType.Decimal) { onEdit(ScreenshotField.AMOUNT, it) }
                ScreenshotFieldInput("名称", state.name, !state.saving) { onEdit(ScreenshotField.NAME, it) }
                ScreenshotFieldInput("备注", state.note, !state.saving) { onEdit(ScreenshotField.NOTE, it) }
                Text("分类", style = MaterialTheme.typography.titleMedium)
                state.classification?.let { com.example.monthlyexpense.ui.ClassificationReason(it.origin, it.hits) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.categories.forEach { category ->
                        FilterChip(
                            selected = state.categoryKey == category.key,
                            onClick = { onEdit(ScreenshotField.CATEGORY, category.key) },
                            enabled = !state.saving,
                            label = { Text(category.name) }
                        )
                    }
                    if (state.categories.none { it.key == BuiltInCategoryKeys.OTHER }) {
                        FilterChip(selected = state.categoryKey == BuiltInCategoryKeys.OTHER,
                            onClick = { onEdit(ScreenshotField.CATEGORY, BuiltInCategoryKeys.OTHER) },
                            enabled = !state.saving, label = { Text("其他") })
                    }
                }
                ScreenshotFieldInput("日期（YYYY-MM-DD）", state.date, !state.saving,
                    KeyboardType.Ascii) { onEdit(ScreenshotField.DATE, it) }
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = state.confirmed, enabled = !busy, role = Role.Checkbox,
                        onValueChange = onConfirm
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = state.confirmed, onCheckedChange = null, enabled = !busy)
                    Text("我已核对：这是人民币成功支出", Modifier.weight(1f))
                }
                Button(
                    onClick = onSave,
                    enabled = !busy && state.confirmed,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.saving) "保存中…" else "确认保存") }
            }
        }
    }
    if (!saved && state.replacePending) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) onReplace(false) },
            title = { Text("替换当前截图？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("替换会放弃当前未保存的草稿和已修改的内容，并识别新截图。")
                }
            },
            confirmButton = {
                TextButton(onClick = { onReplace(true) }, enabled = !state.saving) { Text("放弃草稿并替换") }
            },
            dismissButton = {
                TextButton(onClick = { onReplace(false) }, enabled = !state.saving) { Text("保留当前草稿") }
            }
        )
    } else if (!saved && state.duplicates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismissDuplicate() },
            title = { Text("发现可能重复的账目") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请先核对以下账目，只有选择“仍然保存”才会继续保存。")
                    state.duplicates.take(5).forEach { duplicate ->
                        val date = Instant.ofEpochMilli(duplicate.spentAt)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("${duplicate.reason}\n${duplicate.name} · ${formatMoney(duplicate.amountCents)}\n$date")
                    }
                    if (state.duplicates.size > 5) Text("另有 ${state.duplicates.size - 5} 笔可能重复的账目。")
                }
            },
            confirmButton = {
                TextButton(onClick = onSaveDuplicate, enabled = !busy && state.confirmed) { Text("仍然保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDuplicate, enabled = !busy) { Text("返回核对") }
            }
        )
    }
}

@Composable
private fun ScreenshotFieldInput(
    label: String,
    value: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}
