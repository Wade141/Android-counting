package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.budget.BudgetSummary
import com.example.monthlyexpense.ui.formatMoney

private val WarningColor = Color(0xFFC2410C)

@Composable
internal fun MonthlyOverviewCard(
    totals: Map<ExpenseCategory, Long>,
    summary: BudgetSummary,
    onEditBudget: () -> Unit,
    onOpenCategories: () -> Unit = {}
) {
    com.example.monthlyexpense.ui.settings.AppearanceCard(cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本月预算", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
            TextButton(onClick = onEditBudget) {
                Text(
                    if (summary.isMonthlyBudgetSet) formatMoney(summary.monthlyBudgetCents) else "点击设置",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            DonutChart(totals, Modifier.size(220.dp))
            Spacer(Modifier.height(12.dp))
            totals.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { LegendItem(it.key, it.value, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onOpenCategories) { Text("管理分类") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFEEEAE5))
            Text("本月消费 / 本月预算", color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
            Text("不含专项支出", fontSize = 12.sp, color = com.example.monthlyexpense.ui.settings.secondaryTextColor())
            Text(
                "${formatMoney(summary.monthlySpentCents)} / ${formatMoney(summary.monthlyBudgetCents)}",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                when {
                    !summary.isMonthlyBudgetSet -> "尚未设置本月预算"
                    summary.isMonthlyOverspent -> "本月预算已超支 ${formatMoney(summary.monthlyOverspentCents)}"
                    else -> "本月预算剩余 ${formatMoney(summary.monthlyRemainingCents)}"
                },
                color = if (summary.isMonthlyOverspent) WarningColor else com.example.monthlyexpense.ui.settings.secondaryTextColor()
            )
        }
    }
}

@Composable
private fun DonutChart(totals: Map<ExpenseCategory, Long>, modifier: Modifier) {
    val total = totals.values.sum()
    Canvas(modifier) {
        val stroke = size.minDimension * .16f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        if (total == 0L) {
            drawArc(
                Color(0xFFEAE5DF),
                -90f,
                360f,
                false,
                topLeft,
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        } else {
            var start = -90f
            totals.forEach { (category, cents) ->
                val sweep = cents.toFloat() / total * 360f
                if (sweep > 0f) {
                    drawArc(
                        Color(category.colorArgb.toInt()),
                        start,
                        sweep,
                        false,
                        topLeft,
                        arcSize,
                        style = Stroke(stroke)
                    )
                    start += sweep
                }
            }
        }
    }
}

@Composable
private fun LegendItem(category: ExpenseCategory, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(
                    Color(category.colorArgb.toInt()),
                    RoundedCornerShape(5.dp)
                )
            )
            Spacer(Modifier.width(5.dp))
            Text(category.name, fontSize = 13.sp)
        }
        Text(formatMoney(cents), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
