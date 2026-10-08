package com.example.monthlyexpense.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ExpenseCsvExporterTest {
    @Test
    fun exportsUtf8BomHeaderAndRealCsvEscaping() {
        val expense = BackupExpense(
            id = 1,
            amountCents = 1_234,
            categoryKey = "FOOD",
            name = "早餐,咖啡",
            note = "他说\"很好\"\n下次再来",
            spentAt = 0L,
            monthKey = "1970-01",
            archived = true,
            source = "MANUAL",
            merchant = null,
            sourceKey = null
        )

        val csv = ExpenseCsvExporter.encode(
            expenses = listOf(expense),
            categoryNames = mapOf("FOOD" to "饮食"),
            zoneId = ZoneOffset.UTC
        )

        assertEquals(
            "\uFEFF日期时间,金额（元）,分类,名称,备注,来源,商户,预算归属\r\n" +
                "1970-01-01 00:00:00,12.34,饮食,\"早餐,咖啡\"," +
                "\"他说\"\"很好\"\"\n下次再来\",手动,,日常预算\r\n",
            csv
        )
    }

    @Test
    fun emptyExportStillContainsHeader() {
        assertEquals(
            "\uFEFF日期时间,金额（元）,分类,名称,备注,来源,商户,预算归属\r\n",
            ExpenseCsvExporter.encode(emptyList(), emptyMap(), ZoneOffset.UTC)
        )
    }

    @Test
    fun neutralizesSpreadsheetFormulasInUserText() {
        val expense = BackupExpense(
            id = 1,
            amountCents = 100,
            categoryKey = "OTHER",
            name = "=HYPERLINK(\"https://example.test\")",
            note = "+cmd",
            spentAt = 0,
            monthKey = "1970-01",
            archived = true,
            source = "MANUAL",
            merchant = "@merchant",
            sourceKey = null
        )

        val csv = ExpenseCsvExporter.encode(
            listOf(expense),
            mapOf("OTHER" to "-分类"),
            ZoneOffset.UTC
        )

        assertTrue(csv.contains("'-分类,\"'=HYPERLINK(\"\"https://example.test\"\")\",'+cmd,手动,'@merchant"))
    }
}
