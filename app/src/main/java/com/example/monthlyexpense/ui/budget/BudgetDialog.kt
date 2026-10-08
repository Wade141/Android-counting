package com.example.monthlyexpense.ui.budget

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.example.monthlyexpense.BudgetKind
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.ui.forms.parseMoney
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun BudgetDialog(
    kind: BudgetKind,
    initialCents: Long,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    var value by rememberSaveable(kind, initialCents, key = "budget-$kind-$initialCents") {
        mutableStateOf(moneyInput(initialCents))
    }
    val parsedValue = parseMoney(value)
    val validValue = parsedValue?.takeIf { it <= MoneyLimits.MAX_CENTS }
    val title = if (kind == BudgetKind.MONTHLY) "设置本月预算" else "设置今日预算"

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                label = { Text("金额（元，输入 0 表示未设置）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = value.isNotBlank() && validValue == null,
                supportingText = {
                    if (value.isNotBlank() && validValue == null) Text("请输入 0 至 99,999,999.99")
                },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { validValue?.let(onSave) }, enabled = !saving && validValue != null) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        }
    )
}

private fun moneyInput(cents: Long): String = BigDecimal.valueOf(cents, 2)
    .setScale(2, RoundingMode.UNNECESSARY).toPlainString()
