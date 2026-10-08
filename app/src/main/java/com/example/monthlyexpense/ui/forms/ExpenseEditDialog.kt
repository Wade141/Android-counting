package com.example.monthlyexpense.ui.forms

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ui.formatMoney

@Composable
internal fun ExpenseEditDialog(
    expense: ExpenseRecord,
    categories: List<ExpenseCategory>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, LocalDate, LocalTime, Boolean, Boolean?) -> Unit
) {
    var showBudgetConfirmation by rememberSaveable(expense.id) { mutableStateOf(false) }
    var showDateTimePicker by rememberSaveable(expense.id) { mutableStateOf(false) }
    var timeText by rememberSaveable(expense.id, key = "expense-edit-${expense.id}-time") {
        mutableStateOf(Instant.ofEpochMilli(expense.spentAt).atZone(ZoneId.systemDefault()).toLocalTime().toString())
    }
    var dateText by rememberSaveable(expense.id, key = "expense-edit-${expense.id}-date") {
        mutableStateOf(Instant.ofEpochMilli(expense.spentAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
    }
    var name by rememberSaveable(expense.id, key = "expense-edit-${expense.id}-name") {
        mutableStateOf(expense.name)
    }
    var note by rememberSaveable(expense.id, key = "expense-edit-${expense.id}-note") {
        mutableStateOf(expense.note)
    }
    val selectedKey = rememberSaveable(expense.id, key = "expense-edit-${expense.id}-category") {
        mutableStateOf(expense.category.key)
    }

    var categoryExplicit by rememberSaveable(expense.id) { mutableStateOf(false) }
    val submit: (Boolean?) -> Unit = { isSpecial ->
        onSave(name, note, selectedKey.value, LocalDate.parse(dateText), LocalTime.parse(timeText), categoryExplicit, isSpecial)
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑记录") },
        text = {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.72f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("金额 ${formatMoney(expense.amountCents)}", fontWeight = FontWeight.Bold)
                Text(if (expense.isSpecial) "预算归属：专项预算" else "预算归属：日常预算")
                TextButton(enabled = !saving, onClick = { showBudgetConfirmation = true }) {
                    Text(if (expense.isSpecial) "移回日常预算" else "移至专项预算")
                }
                Text("消费日期：$dateText")
                Text("入账时间：${LocalTime.parse(timeText).format(DateTimeFormatter.ofPattern("HH:mm"))}")
                TextButton(enabled = !saving, onClick = {
                    showDateTimePicker = true
                }) { Text("修改入账时间") }
                OutlinedTextField(name, { name = it }, enabled = !saving, label = { Text("消费名称") }, singleLine = true)
                OutlinedTextField(note, { note = it }, enabled = !saving, label = { Text("消费记录备注") })
                if (!expense.isSpecial) com.example.monthlyexpense.ui.ClassificationReason(expense.classificationOrigin, expense.classificationHits)
                Text("消费类型", fontWeight = FontWeight.Bold)
                CategoryChoices(categories, selectedKey.value) { selectedKey.value = it; categoryExplicit = true }
            }
        },
        confirmButton = {
            Button(onClick = { submit(null) }, enabled = !saving) {
                Text("保存更改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        }
    )
    if (showBudgetConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!saving) showBudgetConfirmation = false },
            title = { Text(if (expense.isSpecial) "确认移回日常预算？" else "确认移至专项预算？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$name · ${formatMoney(expense.amountCents)}")
                    Text(if (expense.isSpecial)
                        "移回后，该笔支出将重新计入所属日期的日常消费和预算统计，消费总额保持不变。"
                    else
                        "移入后，该笔支出不再计入所属日期的日常消费和预算统计，仍计入消费总额。")
                    Text("确认后将同时保存当前名称、备注、分类和入账时间。")
                }
            },
            confirmButton = {
                Button(enabled = !saving, onClick = {
                    showBudgetConfirmation = false
                    submit(!expense.isSpecial)
                }) { Text(if (expense.isSpecial) "确认移回" else "确认移入") }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showBudgetConfirmation = false }) { Text("暂不移动") }
            }
        )
    }
    if (showDateTimePicker) {
        EntryDateTimeDialog(
            initialDate = LocalDate.parse(dateText),
            initialTime = LocalTime.parse(timeText),
            onDismiss = { showDateTimePicker = false },
            onConfirm = { date, time ->
                dateText = date.toString()
                timeText = time.toString()
                showDateTimePicker = false
            }
        )
    }

}
