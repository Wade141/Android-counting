package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ui.expenseDate
import com.example.monthlyexpense.ui.formatMoney
import com.example.monthlyexpense.ui.sourceSuffix

@Composable
internal fun ExpenseRow(expense: ExpenseRecord, onEdit: () -> Unit, onDelete: () -> Unit, enabled: Boolean = true) {
    com.example.monthlyexpense.ui.settings.AppearanceCard(cornerRadius = 16.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(11.dp).background(
                        Color(expense.category.colorArgb.toInt()),
                        RoundedCornerShape(6.dp)
                    )
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(expense.name.ifBlank { expense.category.name }, fontWeight = FontWeight.Bold)
                    if (expense.isSpecial) Text("专项预算", fontSize = 12.sp,
                        color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                    Text(
                        "${expense.category.name} · ${expenseDate(expense.spentAt)}${sourceSuffix(expense.source)}",
                        color = com.example.monthlyexpense.ui.settings.secondaryTextColor(),
                        fontSize = 12.sp
                    )
                    if (!expense.isSpecial) com.example.monthlyexpense.ui.ClassificationReason(expense.classificationOrigin, expense.classificationHits)
                    if (expense.note.isNotBlank()) {
                        Text(expense.note, color = com.example.monthlyexpense.ui.settings.LocalCustomFontColor.current ?: Color.DarkGray, fontSize = 13.sp)
                    }
                }
                Text(formatMoney(expense.amountCents), fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑记录") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除", color = Color(0xFFB91C1C)) }
            }
        }
    }
}
