package com.example.monthlyexpense.backup

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ExpenseCsvExporter {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun encode(
        expenses: List<BackupExpense>,
        categoryNames: Map<String, String>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = buildString {
        append('\uFEFF')
        append("日期时间,金额（元）,分类,名称,备注,来源,商户,预算归属\r\n")
        expenses.forEach { expense ->
            val fields = listOf(
                Instant.ofEpochMilli(expense.spentAt).atZone(zoneId).format(dateFormatter),
                BigDecimal.valueOf(expense.amountCents, 2).toPlainString(),
                categoryNames[expense.categoryKey] ?: expense.categoryKey,
                expense.name,
                expense.note,
                sourceName(expense.source),
                expense.merchant.orEmpty(),
                if (expense.isSpecial) "专项预算" else "日常预算"
            )
            append(fields.joinToString(",") { it.neutralizeFormula().csvField() })
            append("\r\n")
        }
    }

    private fun sourceName(source: String): String = when (source) {
        "MANUAL" -> "手动"
        "WECHAT_AUTO" -> "微信自动记账"
        "ALIPAY_AUTO" -> "支付宝自动记账"
        else -> source
    }

    private fun String.csvField(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }

    private fun String.neutralizeFormula(): String {
        val firstVisible = trimStart().firstOrNull()
        return if (firstVisible in setOf('=', '+', '-', '@')) "'$this" else this
    }
}
