package com.example.monthlyexpense.ui

import com.example.monthlyexpense.ExpenseSource
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun sourceSuffix(source: ExpenseSource): String = when (source) {
    ExpenseSource.MANUAL -> ""
    ExpenseSource.WECHAT_AUTO -> " · 微信自动记账"
    ExpenseSource.ALIPAY_AUTO -> " · 支付宝自动记账"
    ExpenseSource.SCREENSHOT_OCR -> " · 截图记账"
}

internal fun expenseDate(spentAt: Long): String = Instant.ofEpochMilli(spentAt)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

internal fun formatMoney(cents: Long): String = "¥" + BigDecimal.valueOf(cents, 2)
    .setScale(2, RoundingMode.UNNECESSARY).toPlainString()
