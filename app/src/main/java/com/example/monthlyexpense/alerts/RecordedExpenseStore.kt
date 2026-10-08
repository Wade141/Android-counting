package com.example.monthlyexpense.alerts

import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.notification.repository.LedgerNotificationProtocol
import kotlinx.coroutines.*

data class RecordedExpensePage(val expense: ExpenseRecord, val categories: List<ExpenseCategory>)

/** Reads by primary key, including archived records; old-book notifications never address a new ledger. */
class RecordedExpenseStore(private val database: ExpenseDatabaseHelper,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val onChanged: (Set<DataDomain>) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val initialize: suspend () -> Unit = {}) {
    suspend fun load(target: RecordedExpenseTarget): RecordedExpensePage? = withContext(io) {
        initialize()
        val ticket=coordinator.mutationTicket() ?: return@withContext null
        val result=coordinator.runRead(ticket) {
            if (database.backupDao.notificationProtocol.bookGeneration()!=target.bookGeneration) null
            else database.expenseDao.findById(target.expenseId)?.let { RecordedExpensePage(it,database.categoryDao.categories()) }
        }
        if (coordinator.isCurrent(ticket)) (result as? CoordinatedMutation.Executed)?.value else null
    }

    suspend fun save(target: RecordedExpenseTarget, input: ExpenseEditInput): Boolean = withContext(io) {
        initialize()
        val ticket=coordinator.mutationTicket() ?: return@withContext false
        val result=coordinator.runMutation(ticket) {
            val db=database.writableDatabase
            db.beginTransaction()
            val saved=try {
                val valid=LedgerNotificationProtocol.currentGeneration(db)==target.bookGeneration &&
                    database.expenseDao.findById(target.expenseId)!=null
                (valid && database.expenseDao.updateExpense(target.expenseId,input.name,input.note,input.categoryKey,
                    input.date,input.time,input.categoryExplicit,input.isSpecial)).also { db.setTransactionSuccessful() }
            } finally { db.endTransaction() }
            if(saved) onChanged(setOf(DataDomain.LEDGER))
            saved
        }
        (result as? CoordinatedMutation.Executed)?.value == true
    }
}
