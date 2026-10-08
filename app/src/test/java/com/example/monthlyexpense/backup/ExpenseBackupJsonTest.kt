package com.example.monthlyexpense.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseBackupJsonTest {
    @Test
    fun roundTripPreservesEveryBackupField() {
        val document = sampleDocument()

        val decoded = ExpenseBackupJson.decode(ExpenseBackupJson.encode(document))

        assertEquals(document, decoded)
    }

    @Test
    fun rejectsJsonFromAnotherFormat() {
        val json = JSONObject(ExpenseBackupJson.encode(sampleDocument()))
            .put("format", "other").toString()

        assertThrows(InvalidExpenseBackupException::class.java) {
            ExpenseBackupJson.decode(json)
        }
    }

    @Test
    fun rejectsUnknownBackupVersion() {
        val json = JSONObject(ExpenseBackupJson.encode(sampleDocument()))
            .put("version", 99).toString()

        assertThrows(InvalidExpenseBackupException::class.java) {
            ExpenseBackupJson.decode(json)
        }
    }

    @Test
    fun rejectsExpenseReferencingMissingCategory() {
        val root = JSONObject(ExpenseBackupJson.encode(sampleDocument()))
        root.getJSONObject("database").getJSONArray("expenses")
            .getJSONObject(0).put("categoryKey", "missing")
        val json = root.toString()

        assertThrows(InvalidExpenseBackupException::class.java) {
            ExpenseBackupJson.decode(json)
        }
    }

    @Test
    fun rejectsDuplicateJsonKeys() {
        val json = ExpenseBackupJson.encode(sampleDocument())
            .replaceFirst("{", "{\"format\":\"expense_report\",")

        assertThrows(InvalidExpenseBackupException::class.java) {
            ExpenseBackupJson.decode(json)
        }
    }

    @Test
    fun rejectsTrailingContentAfterRootObject() {
        val json = ExpenseBackupJson.encode(sampleDocument()) + " trailing"

        assertThrows(InvalidExpenseBackupException::class.java) {
            ExpenseBackupJson.decode(json)
        }
    }

    private fun sampleDocument() = ExpenseBackupDocument(
        exportedAt = 1_777_777_777_000L,
        database = ExpenseBackupDatabase(
            categories = listOf(
                BackupCategory("FOOD", "饮食", 0xFFFF8A65L, true, 0),
                BackupCategory("ENTERTAINMENT", "娱乐", 0xFF7E8CE0L, true, 1),
                BackupCategory("TRAVEL", "出行", 0xFF42A5F5L, true, 2),
                BackupCategory("SHOPPING", "购物", 0xFFAB47BCL, true, 3),
                BackupCategory("BILLS", "缴费", 0xFFFFB74DL, true, 4),
                BackupCategory("OTHER", "其他", 0xFF58BFA6L, true, 5)
            ),
            expenses = listOf(
                BackupExpense(
                    id = 7,
                    amountCents = 5_310,
                    categoryKey = "FOOD",
                    name = "早餐",
                    note = "豆浆,油条",
                    spentAt = 1_777_000_000_000L,
                    monthKey = "2026-04",
                    archived = true,
                    source = "WECHAT_AUTO",
                    merchant = "早餐店",
                    sourceKey = "wechat:key"
                )
            ),
            monthlyBudgets = listOf(BackupMonthlyBudget("2026-04", 300_000))
        ),
        settings = ExpenseBackupSettings(
            dailyBudgetCents = 10_000,
            autoBookkeepingEnabled = true,
            weChatEnabled = true,
            alipayEnabled = false
        )
    )
}
