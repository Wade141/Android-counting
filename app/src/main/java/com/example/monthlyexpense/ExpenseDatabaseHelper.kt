package com.example.monthlyexpense

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.monthlyexpense.data.ExpenseHomeDatabaseSnapshot
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import com.example.monthlyexpense.notification.NotificationMetrics
import com.example.monthlyexpense.notification.PipelineMetric

/**
 * Owns the database lifecycle and DAOs sharing this helper.
 * Cross-table home reads use one connection and transaction.
 */
class ExpenseDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DatabaseSchema.NAME, null, DatabaseSchema.VERSION) {
    val categoryDao = CategoryDao(this)
    val categoryRuleDao = com.example.monthlyexpense.classification.CategoryRuleDao(this)
    val expenseDao = ExpenseDao(this, categoryDao)
    val budgetDao = BudgetDao(this)
    val backupDao = BackupDao(this, categoryDao)

    override fun onCreate(db: SQLiteDatabase) = DatabaseSchema.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        DatabaseMigrations.upgrade(db, oldVersion)

    internal fun loadLedgerData(now: LocalDate, zoneId: ZoneId): Pair<List<ExpenseRecord>, Long> {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            readLedger(db, now, zoneId)
                .also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }

    internal fun loadSettingsData(now: LocalDate): Pair<List<ExpenseCategory>, Long> {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            readSettings(db, now)
                .also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }

    private fun readLedger(db: SQLiteDatabase, now: LocalDate, zoneId: ZoneId) =
        NotificationMetrics.measure(PipelineMetric.LEDGER_READ) {
            expenseDao.archivePastMonths(db, now)
            expenseDao.currentMonthRecords(db, now) to expenseDao.todayTotal(db, now, zoneId)
        }

    private fun readSettings(db: SQLiteDatabase, now: LocalDate) =
        NotificationMetrics.measure(PipelineMetric.SETTINGS_READ) {
            categoryDao.categories(db) to budgetDao.getOrCreateMonthlyBudget(db, YearMonth.from(now))
        }

    internal fun loadHomeData(now: LocalDate, zoneId: ZoneId): ExpenseHomeDatabaseSnapshot {
        val started = NotificationMetrics.begin(PipelineMetric.HOME_READ)
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val (expenses, todayTotal) = readLedger(db, now, zoneId)
            val (categories, monthlyBudget) = readSettings(db, now)
            val result = ExpenseHomeDatabaseSnapshot(
                expenses = expenses,
                categories = categories,
                monthlyBudgetCents = monthlyBudget,
                todayTotalCents = todayTotal
            )
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
            NotificationMetrics.end(PipelineMetric.HOME_READ, started)
        }
    }

    /** Restored ledgers can be viewed before settings completion without archiving or creating budget rows. */
    internal fun loadReadOnlyHomeData(now: LocalDate, zoneId: ZoneId): ExpenseHomeDatabaseSnapshot {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        return try {
            val budget = db.rawQuery(
                "SELECT amount_cents FROM monthly_budgets WHERE month_key <= ? ORDER BY month_key DESC LIMIT 1",
                arrayOf(YearMonth.from(now).toString())
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            ExpenseHomeDatabaseSnapshot(
                expenses = expenseDao.currentMonthRecords(db, now),
                categories = categoryDao.categories(db),
                monthlyBudgetCents = budget,
                todayTotalCents = expenseDao.todayTotal(db, now, zoneId)
            ).also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }
}
