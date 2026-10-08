package com.example.monthlyexpense.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.ui.ExpenseAppTheme
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordedExpenseEditorTest {
    @get:Rule val compose=createComposeRule()
    private val context: Context=ApplicationProvider.getApplicationContext()
    private lateinit var db: ExpenseDatabaseHelper
    private lateinit var store: RecordedExpenseStore
    @Before fun setup() {
        context.deleteDatabase("expenses.db");db=ExpenseDatabaseHelper(context)
        store=RecordedExpenseStore(db,ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false,true,true)),{},Dispatchers.Unconfined)
    }
    @After fun close() {db.close();context.deleteDatabase("expenses.db")}

    @Test fun notificationDestinationOpensEditorImmediatelyAndSavesOriginalRow() {
        db.expenseDao.addExpense(1234,BuiltInCategoryKeys.OTHER,"通知原记录","")
        val id=db.readableDatabase.rawQuery("SELECT id FROM expenses",null).use {it.moveToFirst();it.getLong(0)}
        val target=RecordedExpenseTarget(db.backupDao.notificationProtocol.bookGeneration(),id)
        var saved=false
        compose.setContent { ExpenseAppTheme {RecordedExpenseEditor(target,store,{}, {saved=true})} }
        compose.onNodeWithText("编辑记录").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("通知直接改好")
        compose.onNodeWithText("保存更改").performClick()
        compose.waitForIdle()
        assertTrue(saved)
        assertEquals("通知直接改好",db.expenseDao.findById(id)!!.name)
        assertEquals(1234L,db.expenseDao.findById(id)!!.amountCents)
    }

    @Test fun missingRecordShowsExplanationInsteadOfEmptyHomeOrNewExpense() {
        var closed=false
        compose.setContent { ExpenseAppTheme {RecordedExpenseEditor(RecordedExpenseTarget("old-book",99),store,{closed=true},{})} }
        compose.onNodeWithText("这笔账单已删除、所属账本已变化，或正在恢复备份。").assertIsDisplayed()
        compose.onNodeWithText("编辑记录").assertDoesNotExist()
        compose.onNodeWithText("返回账本").performClick()
        assertTrue(closed)
    }
}
