package com.example.monthlyexpense

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.search.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ExpenseSearchDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ExpenseDatabaseHelper
    private val date = LocalDate.of(2026,9,30)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = date.atStartOfDay(zone).toInstant().toEpochMilli()
    @Before fun setup() { context.deleteDatabase("expenses.db"); db = ExpenseDatabaseHelper(context) }
    @After fun cleanup() { db.close(); context.deleteDatabase("expenses.db") }
    private fun query(word: String = "", filters: ExpenseSearchFilters = ExpenseSearchFilters()) =
        (validateSearch(word, filters, date, zone) as SearchValidationResult.Valid).query
    private fun insert(name: String = "茶", note: String = "", merchant: String? = null,
        cents: Long = 100, at: Long = time, archived: Int = 0, category: String = "FOOD",
        source: ExpenseSource = ExpenseSource.MANUAL): Long = db.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
        put("name",name); put("note",note); put("merchant",merchant); put("amount_cents",cents)
        put("spent_at",at); put("month_key","2026-09"); put("archived",archived)
        put("category",category); put("source",source.name)
    })
    @Test fun literalSearchCoversNameNoteMerchantWithoutSqlOrWildcardExpansion() {
        val a=insert(name="100%折扣"); val b=insert(name="普通",note="100%备注"); val c=insert(name="普通",merchant="100%商户")
        insert(name="100元折扣")
        assertEquals(setOf(a,b,c), db.expenseDao.searchPage(query("100%"),null).items.map { it.id }.toSet())
        val underscore=insert(name="a_b"); insert(name="acb")
        assertEquals(listOf(underscore),db.expenseDao.searchPage(query("a_b"),null).items.map { it.id })
        val slash=insert(name="a\\b"); assertEquals(listOf(slash),db.expenseDao.searchPage(query("a\\b"),null).items.map { it.id })
        val quote=insert(name="' OR 1=1 --")
        assertEquals(listOf(quote),db.expenseDao.searchPage(query("' OR 1=1 --"),null).items.map { it.id })
    }
    @Test fun filtersCombineWithAndAndChoicesWithinGroupUseOr() {
        val a=insert(cents=2000,source=ExpenseSource.WECHAT_AUTO)
        val b=insert(cents=10000,category="SHOPPING",source=ExpenseSource.ALIPAY_AUTO)
        insert(cents=1999,source=ExpenseSource.WECHAT_AUTO); insert(cents=2000)
        insert(cents=2000,category="OTHER",source=ExpenseSource.WECHAT_AUTO)
        insert(cents=2000,at=time-1,source=ExpenseSource.WECHAT_AUTO)
        val filters=ExpenseSearchFilters(SearchDatePreset.TODAY,categoryKeys=setOf("FOOD","SHOPPING"),minAmountText="20",maxAmountText="100",
            sources=setOf(ExpenseSource.WECHAT_AUTO,ExpenseSource.ALIPAY_AUTO))
        val page=db.expenseDao.searchPage(query("茶",filters),null)
        assertEquals(setOf(a,b),page.items.map { it.id }.toSet())
        assertEquals(ExpenseSearchSummary(2,12000),page.summary)
        assertTrue(db.expenseDao.searchPage(query(filters=filters.copy(categoryKeys=setOf("DELETED"))),null).items.isEmpty())
    }
    @Test fun globalSearchIncludesArchivedAndUnarchivedPastWithoutMutatingLedger() {
        insert(); insert(at=time-40*86400000L,archived=1); insert(at=time-40*86400000L)
        db.writableDatabase.execSQL("CREATE TRIGGER no_writes BEFORE UPDATE ON expenses BEGIN SELECT RAISE(ABORT,'read only'); END")
        assertEquals(3L,db.expenseDao.searchPage(query(),null).summary!!.count)
        val empty=db.expenseDao.searchPage(query("不存在"),null)
        assertEquals(ExpenseSearchSummary(0,0),empty.summary); assertFalse(empty.hasMore)
    }
    @Test fun identicalTimestampsPageWithoutDuplicatesAndSummaryCoversAllPages() {
        repeat(101) { insert() }
        val first=db.expenseDao.searchPage(query(),null)
        val second=db.expenseDao.searchPage(query(),first.nextCursor)
        val third=db.expenseDao.searchPage(query(),second.nextCursor)
        assertEquals(listOf(50,50,1),listOf(first,second,third).map { it.items.size })
        assertEquals(ExpenseSearchSummary(101,10100),first.summary)
        assertNull(second.summary); assertNull(third.summary); assertFalse(third.hasMore)
        val ids=(first.items+second.items+third.items).map { it.id }
        assertEquals(101,ids.toSet().size); assertEquals(ids.sortedDescending(),ids)
    }
    @Test fun datesIncludeLastMillisecondButNotNextMidnightAndUnknownCategoryStillCounts() {
        insert(at=time-1); val a=insert(at=time); val b=insert(at=time+86400000L-1,category="MISSING"); insert(at=time+86400000L)
        val page=db.expenseDao.searchPage(query(filters=ExpenseSearchFilters(SearchDatePreset.TODAY)),null)
        assertEquals(setOf(a,b),page.items.map { it.id }.toSet())
        assertEquals(ExpenseSearchSummary(2,200),page.summary)
        assertEquals("OTHER",page.items.first { it.id==b }.category.key)
        assertTrue(runCatching { db.expenseDao.searchPage(query(),null,0) }.isFailure)
    }

    @Test fun concurrentWritesCannotSplitFirstPageAndSummarySnapshots() {
        repeat(20) { insert() }
        val sql = db.writableDatabase
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val writer = executor.submit {
            start.await()
            repeat(60) { i ->
                sql.beginTransaction()
                try {
                    sql.execSQL("UPDATE expenses SET amount_cents = ?", arrayOf(if (i % 2 == 0) 200 else 100))
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
            }
        }
        try {
            start.countDown()
            repeat(60) {
                val page = db.expenseDao.searchPage(query(), null)
                assertEquals(page.items.sumOf { it.amountCents }, page.summary!!.totalCents)
                assertEquals(page.items.size.toLong(), page.summary!!.count)
            }
            writer.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
}
