package com.example.monthlyexpense.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseBackupManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper
    private lateinit var budgetSettings: BudgetSettings
    private lateinit var autoSettings: AutoBookkeepingSettings
    private lateinit var manager: ExpenseBackupManager

    @Before
    fun setUp() {
        context.deleteDatabase("expenses.db")
        context.getSharedPreferences(BudgetSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(AutoBookkeepingSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = ExpenseDatabaseHelper(context)
        budgetSettings = BudgetSettings(context)
        autoSettings = AutoBookkeepingSettings(context)
        manager = ExpenseBackupManager(database.backupDao, budgetSettings, autoSettings)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("expenses.db")
    }

    @Test
    fun jsonBackupRestoresDatabaseAndPreferences() {
        database.expenseDao.addExpense(1_880, BuiltInCategoryKeys.FOOD, "午饭", "")
        budgetSettings.dailyBudgetCents = 8_000
        autoSettings.isEnabled = true
        autoSettings.isWeChatEnabled = false
        autoSettings.isAlipayEnabled = true
        val json = manager.exportJson(exportedAt = 1_777_777_777_000L)

        database.expenseDao.addExpense(9_999, BuiltInCategoryKeys.OTHER, "应删除", "")
        budgetSettings.dailyBudgetCents = 1
        autoSettings.isEnabled = false
        autoSettings.isWeChatEnabled = true
        autoSettings.isAlipayEnabled = false

        assertEquals(
            ExpenseBackupSettings(
                dailyBudgetCents = 8_000,
                autoBookkeepingEnabled = true,
                weChatEnabled = false,
                alipayEnabled = true
            ),
            manager.restoreJsonWithSettings(json)
        )
        assertEquals(1, database.backupDao.exportBackupDatabase().expenses.size)
        assertEquals("午饭", database.backupDao.exportBackupDatabase().expenses.single().name)
        assertEquals(8_000, budgetSettings.dailyBudgetCents)
        assertTrue(autoSettings.isEnabled)
        assertFalse(autoSettings.isWeChatEnabled)
        assertTrue(autoSettings.isAlipayEnabled)
    }

    @Test
    fun invalidJsonChangesNeitherDatabaseNorPreferences() {
        database.expenseDao.addExpense(1_880, BuiltInCategoryKeys.FOOD, "保留", "")
        budgetSettings.dailyBudgetCents = 8_000
        autoSettings.isEnabled = true
        val before = database.backupDao.exportBackupDatabase()

        assertNull(manager.restoreJsonWithSettings("{\"format\":\"not-expense-report\"}"))
        assertEquals(before, database.backupDao.exportBackupDatabase())
        assertEquals(8_000, budgetSettings.dailyBudgetCents)
        assertTrue(autoSettings.isEnabled)
    }

    @Test
    fun failureAfterOpeningRestoreGateReleasesItWhenLedgerDidNotCommit() {
        val json = manager.exportJson()
        var gateOpen = false
        val guarded = ExpenseBackupManager(database.backupDao, budgetSettings, autoSettings,
            lifecycle = object : BackupRestoreLifecycle {
                override fun begin(restoreId: String) { gateOpen = true; error("precommit failure") }
                override fun applySources(settings: ExpenseBackupSettings) = Unit
                override fun finish(restoreId: String, committed: Boolean) {
                    assertFalse(committed)
                    gateOpen = false
                }
            })
        val book = database.backupDao.notificationProtocol.bookGeneration()
        assertEquals(BackupRestoreResult.NotCommitted, guarded.restoreDetailed(json))
        assertFalse(gateOpen)
        assertEquals(book, database.backupDao.notificationProtocol.bookGeneration())
        assertNull(database.backupDao.notificationProtocol.pendingRestore())
    }

    @Test
    fun committedLedgerSurvivesSettingsFailureAndRetryDoesNotRestoreTwice() {
        database.expenseDao.addExpense(1_880, BuiltInCategoryKeys.FOOD, "备份账目", "")
        val json = manager.exportJson()
        database.expenseDao.addExpense(999, BuiltInCategoryKeys.OTHER, "应替换", "")
        var permitSettings = false
        val interrupted = ExpenseBackupManager(database.backupDao, budgetSettings, autoSettings,
            settingsWriter = { permitSettings })
        assertEquals(BackupRestoreResult.SettingsPending, interrupted.restoreDetailed(json))
        assertEquals(1, database.backupDao.exportBackupDatabase().expenses.size)
        val generation = database.backupDao.notificationProtocol.bookGeneration()
        permitSettings = true
        assertTrue(interrupted.continuePendingRestore() is BackupRestoreResult.Completed)
        assertEquals(generation, database.backupDao.notificationProtocol.bookGeneration())
        assertNull(database.backupDao.notificationProtocol.pendingRestore())
    }
}
