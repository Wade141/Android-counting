package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.notification.decision.DecisionReasons
import com.example.monthlyexpense.notification.parser.PaymentAmountParser
import com.example.monthlyexpense.notification.repository.PendingNotification
import com.example.monthlyexpense.ui.expenseDate
import com.example.monthlyexpense.ui.formatMoney
import java.math.BigDecimal

@Composable
internal fun NotificationReviewDialog(item: PendingNotification, busy: Boolean, onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit, onIgnore: () -> Unit, onLink: () -> Unit) {
    var amount by rememberSaveable(item.eventId) { mutableStateOf(item.amountCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var name by rememberSaveable(item.eventId) { mutableStateOf(item.merchant ?: if (item.source == "WECHAT") "微信付款" else "支付宝付款") }
    val cents = PaymentAmountParser.toCents(amount)?.takeIf { it in 1..MoneyLimits.MAX_CENTS }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("核对付款") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${if (item.source == "WECHAT") "微信" else "支付宝"} · ${expenseDate(item.observedAt)}")
            Text(item.reasons.joinToString("；", transform = DecisionReasons::describe))
            Text("未确认的记录不会计入消费。")
            if (item.candidates.isNotEmpty()) Text("通知中的金额：${item.candidates.joinToString("、", transform = ::formatMoney)}")
            OutlinedTextField(amount, { amount = it }, label = { Text("实际支出金额") },
                modifier = Modifier.testTag("review-amount"), singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount.isNotEmpty() && cents == null)
            OutlinedTextField(name, { name = it }, label = { Text("名称") },
                modifier = Modifier.testTag("review-name"), singleLine = true, enabled = !busy, isError = name.trim().length !in 1..40)
            TextButton(onClick = onLink, enabled = !busy) { Text("这笔已经记过，关联已有账单") }
        }
    }, confirmButton = {
        TextButton(onClick = { cents?.let { onConfirm(it, name.trim()) } }, enabled = !busy && cents != null && name.trim().length in 1..40) { Text("确认计入支出") }
    }, dismissButton = {
        Row { TextButton(onClick = onIgnore, enabled = !busy) { Text("忽略") }; TextButton(onClick = onDismiss, enabled = !busy) { Text("稍后处理") } }
    })
}
