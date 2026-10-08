package com.example.monthlyexpense.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.example.monthlyexpense.budget.BudgetSummary
import com.example.monthlyexpense.ui.formatMoney

@Composable
internal fun DailyBudgetCard(summary: BudgetSummary, onEditDailyBudget: () -> Unit) {
    com.example.monthlyexpense.ui.settings.AppearanceCard(cornerRadius = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("今日预算", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                    Text(
                        if (summary.isDailyBudgetSet) formatMoney(summary.dailyBudgetCents) else "未设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("今日消费（不含专项）", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
                    Text(formatMoney(summary.todaySpentCents), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
            TextButton(onClick = onEditDailyBudget) { Text("修改今日预算") }
            Text("本月预算剩余", color = com.example.monthlyexpense.ui.settings.secondaryTextColor(), fontSize = 13.sp)
            BudgetProgress(summary)
            Spacer(Modifier.height(10.dp))
            Text("今日消费 / 今日预算：${formatMoney(summary.todaySpentCents)} / ${formatMoney(summary.dailyBudgetCents)}")
            if (summary.isDailyOverspent) {
                Text(
                    "今天已超支 ${formatMoney(summary.dailyOverspentCents)}，请理性消费",
                    color = Color(0xFFC2410C),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BudgetProgress(summary: BudgetSummary) {
    Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(Color(0xFFE5E1DC), RoundedCornerShape(7.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(summary.remainingRatio).fillMaxHeight()
                    .background(Color(0xFFF59E0B), RoundedCornerShape(7.dp))
            )
        }
        if (summary.isMonthlyOverspent) {
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier.width(14.dp).fillMaxHeight()
                    .background(Color(0xFFDC2626), RoundedCornerShape(7.dp))
            )
        }
    }
    if (summary.isMonthlyOverspent) {
        Text(
            "已超支 ${formatMoney(summary.monthlyOverspentCents)}",
            color = Color(0xFFDC2626),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
