package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.ui.formatMoney
import com.example.monthlyexpense.ui.settings.AppearanceCard
import com.example.monthlyexpense.ui.settings.secondaryTextColor

@Composable
internal fun SpecialBudgetCard(
    totalCents: Long,
    onOpenDetails: () -> Unit
) {
    AppearanceCard(cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("special-budget"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("专项预算", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(formatMoney(totalCents), fontSize = 26.sp, fontWeight = FontWeight.Black)
            TextButton(onClick = onOpenDetails) { Text("专项预算详情") }
        }
    }
}

@Composable
internal fun MonthlyTotalCard(dailyCents: Long, specialCents: Long) {
    AppearanceCard(cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("monthly-total"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本月消费总额", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(formatMoney(dailyCents + specialCents), fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("本月消费 ${formatMoney(dailyCents)}", color = secondaryTextColor())
            Text("专项支出 ${formatMoney(specialCents)}", color = secondaryTextColor())
        }
    }
}
