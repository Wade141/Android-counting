package com.example.monthlyexpense.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordedExpenseStoreTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private lateinit var db: ExpenseDatabaseHelper
    private lateinit var store: RecordedExpenseStore
    private val coordinator=ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false,true,true))
    @Before fun setup() {
        context.deleteDatabase("expenses.db");db=ExpenseDatabaseHelper(context)
        store=RecordedExpenseStore(db,coordinator,{},Dispatchers.Unconfined)
        db.expenseDao.addExpense(6500,BuiltInCategoryKeys.OTHER,"原始名称","原备注")
        db.writableDatabase.execSQL("UPDATE expenses SET archived=1, month_key='2020-01'")
    }
    @After fun close() { db.close();context.deleteDatabase("expenses.db") }
    private fun target()=RecordedExpenseTarget(db.backupDao.notificationProtocol.bookGeneration(),
        db.readableDatabase.rawQuery("SELECT id FROM expenses LIMIT 1",null).use {it.moveToFirst();it.getLong(0)})

    @Test fun opensArchivedExpenseDirectlyAndSavesSameRowWithoutChangingAmount()=runBlocking {
        val target=target();val page=store.load(target)!!
        assertEquals("原始名称",page.expense.name)
        assertTrue(store.save(target,ExpenseEditInput("改好名称","新备注",BuiltInCategoryKeys.FOOD,categoryExplicit=true)))
        val updated=store.load(target)!!.expense
        assertEquals(target.expenseId,updated.id)
        assertEquals(6500L,updated.amountCents)
        assertEquals(BuiltInCategoryKeys.FOOD,updated.category.key)
        assertEquals("新备注",updated.note)
    }

    @Test fun staleBookCannotOpenOrChangeReusedId()=runBlocking {
        val target=target()
        db.writableDatabase.execSQL("UPDATE ledger_metadata SET book_generation='new-book'")
        assertNull(store.load(target))
        assertFalse(store.save(target,ExpenseEditInput("错误修改","",BuiltInCategoryKeys.FOOD)))
        assertEquals("原始名称",db.expenseDao.findById(target.expenseId)!!.name)
    }

    @Test fun deletedRecordAndRestoreBarrierDoNotOpenOrSave()=runBlocking {
        val target=target();db.expenseDao.delete(target.expenseId)
        assertNull(store.load(target))
        assertFalse(store.save(target,ExpenseEditInput("不能复活","",BuiltInCategoryKeys.FOOD)))
        coordinator.setDurableRestorePending(true)
        assertNull(store.load(target))
    }

    @Test fun targetsRoundTripAndRejectMalformedLinks() {
        val target=RecordedExpenseTarget("book space",7)
        assertEquals(target,RecordedExpenseTarget.fromUri(target.toUri()))
        for (uri in listOf("monthlyexpense://recorded/book/0","monthlyexpense://recorded/book/not-id","https://recorded/book/7","monthlyexpense://recorded/book/7/extra")) {
            assertNull(RecordedExpenseTarget.fromUri(android.net.Uri.parse(uri)))
        }
    }
}
