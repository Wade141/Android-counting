package com.example.monthlyexpense.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.classification.*

@Composable
internal fun ExpenseEntryDialog(
    categories: List<ExpenseCategory>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Long, String, String, String, Boolean) -> Unit,
    rules: List<CategoryRuleSet> = emptyList()
) {
    var amount by rememberSaveable(key = "new-expense-amount") { mutableStateOf("") }
    var name by rememberSaveable(key = "new-expense-name") { mutableStateOf("") }
    var note by rememberSaveable(key = "new-expense-note") { mutableStateOf("") }
    val selectedKey = rememberSaveable(key = "new-expense-category") {
        mutableStateOf(BuiltInCategoryKeys.OTHER)
    }

    var categoryExplicit by rememberSaveable { mutableStateOf(false) }
    val suggestion = ExpenseCategoryResolver.resolve(ClassificationInput(null, name), rules)
    val effectiveKey = if (categoryExplicit) selectedKey.value else suggestion.categoryKey

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("记录消费") },
        text = {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.72f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    amount,
                    { amount = it },
                    label = { Text("金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(name, { name = it }, label = { Text("消费名称") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") })
                Text("消费类型", fontWeight = FontWeight.Bold)
                CategoryChoices(categories, effectiveKey) { selectedKey.value = it; categoryExplicit = true }
                if (!categoryExplicit) com.example.monthlyexpense.ui.ClassificationReason(suggestion.origin, suggestion.hits)
                else Text("已手动选择分类")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    parseMoney(amount)?.takeIf { it > 0 }
                        ?.let { onSave(it, effectiveKey, name, note, categoryExplicit) }
                },
                enabled = !saving
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        }
    )
}

internal fun parseMoney(value: String): Long? = try {
    val decimal = value.trim().toBigDecimal().setScale(2, java.math.RoundingMode.UNNECESSARY)
    if (decimal.signum() < 0) null else decimal.movePointRight(2).longValueExact()
} catch (_: Exception) {
    null
}
