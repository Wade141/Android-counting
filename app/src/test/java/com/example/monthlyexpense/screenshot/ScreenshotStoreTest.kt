package com.example.monthlyexpense.screenshot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.*
import com.example.monthlyexpense.backup.*
import com.example.monthlyexpense.categories.CategoryMutationResult
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.ui.sourceSuffix
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ScreenshotStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper
    private lateinit var coordinator: ForegroundPersistenceCoordinator
    private lateinit var store: ScreenshotExpenseStore
    private val day = LocalDate.of(2025, 8, 12).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun input() = ScreenshotExpenseInput(
        "12345678-1234-1234-1234-123456789abc", "a".repeat(64), 1200,
        "Coffee Shop", " note ", BuiltInCategoryKeys.FOOD, day
    )
    private fun rows() = database.backupDao.exportBackupDatabase().expenses

    @Before fun setUp() {
        context.deleteDatabase("expenses.db")
        database = ExpenseDatabaseHelper(context)
        coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        store = ScreenshotExpenseStore(database, coordinator)
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase("expenses.db")
    }

    @Test fun readsWithoutExplicitSaveNeverInsert() = runBlocking {
        assertTrue(store.loadCategories().any { it.key == BuiltInCategoryKeys.FOOD })
        assertNull(store.suggestCategory("Coffee Shop"))
        assertTrue(rows().isEmpty())
    }

    @Test fun onlyCommittedNewScreenshotInvalidatesLedger() = runBlocking {
        var changes = 0
        val observed = ScreenshotExpenseStore(database, coordinator, onSaved = {
            assertEquals(1, rows().size)
            changes++
        })
        assertTrue(observed.save(input(), false) is ScreenshotSaveResult.Saved)
        assertTrue(observed.save(input(), false) is ScreenshotSaveResult.AlreadySaved)
        assertEquals(1, changes)
    }

    @Test fun repeatedTaskIsIdempotentEvenWithDuplicatePermissionAndEditedHash() = runBlocking {
        val saved = store.save(input(), false) as ScreenshotSaveResult.Saved
        assertEquals(ScreenshotSaveResult.AlreadySaved(saved.id), store.save(input(), false))
        assertEquals(ScreenshotSaveResult.AlreadySaved(saved.id), store.save(input().copy(imageHash = null), true))
        val row = rows().single()
        assertEquals("ocr:${"a".repeat(64)}:12345678-1234-1234-1234-123456789abc", row.sourceKey)
        assertEquals("SCREENSHOT_OCR", row.source)
        assertEquals("note", row.note)
        assertEquals("2025-08", row.monthKey)
        assertTrue(row.archived)
    }

    @Test fun sameImageWarnsAcrossDatesAndPermissionOnlyBypassesWarning() = runBlocking {
        val first = store.save(input(), false) as ScreenshotSaveResult.Saved
        val next = input().copy(taskId = UUID.randomUUID().toString(), spentAt = day + 172800000, name = "Other", amountCents = 200)
        val warning = store.save(next, false) as ScreenshotSaveResult.Duplicate
        assertEquals(first.id, warning.matches.single().id)
        assertTrue(warning.matches.single().reason.isNotBlank())
        assertEquals(1, rows().size)
        val saved = store.save(next, true) as ScreenshotSaveResult.Saved
        assertEquals(ScreenshotSaveResult.AlreadySaved(saved.id), store.save(next, true))
        assertEquals(2, rows().size)
    }

    @Test fun sameDayAmountAndNormalizedNameWarnForEveryExistingSource() = runBlocking {
        for (source in ExpenseSource.entries) {
            database.writableDatabase.delete("expenses", null, null)
            assertTrue(database.expenseDao.insertExpenseRecord(database.writableDatabase, 1200,
                BuiltInCategoryKeys.FOOD, "  COFFEE\t Shop  ", "", day + 3600000, source, null, null))
            assertTrue(store.save(input(), false) is ScreenshotSaveResult.Duplicate)
            assertEquals(1, rows().size)
            assertTrue(store.save(input().copy(spentAt = day + 86400000), false) is ScreenshotSaveResult.Saved)
        }
    }

    @Test fun absentHashesAreNotExactImageDuplicates() = runBlocking {
        assertTrue(store.save(input().copy(imageHash = null), false) is ScreenshotSaveResult.Saved)
        assertTrue(store.save(input().copy(taskId = UUID.randomUUID().toString(), imageHash = null, amountCents = 2400), false) is ScreenshotSaveResult.Saved)
        assertEquals("ocr:none:12345678-1234-1234-1234-123456789abc", rows().first().sourceKey)
    }

    @Test fun differentNameOrAmountDoesNotWarnAndUppercaseIdentityIsCanonical() = runBlocking {
        database.expenseDao.addExpense(1200, BuiltInCategoryKeys.FOOD, "Other", "", day)
        database.expenseDao.addExpense(2400, BuiltInCategoryKeys.FOOD, "Coffee Shop", "", day)
        val saved = store.save(input().copy(taskId = input().taskId.uppercase(), imageHash = "A".repeat(64)), false)
            as ScreenshotSaveResult.Saved
        assertEquals(ScreenshotSaveResult.AlreadySaved(saved.id), store.save(input(), false))
        assertEquals(3, rows().size)
    }

    @Test fun currentDateUsesCurrentMonthAndIsNotArchived() = runBlocking {
        val today = LocalDate.now()
        val spentAt = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(store.save(input().copy(spentAt = spentAt), false) is ScreenshotSaveResult.Saved)
        assertFalse(rows().single().archived)
        assertEquals(java.time.YearMonth.from(today).toString(), rows().single().monthKey)
        assertEquals(ExpenseSource.SCREENSHOT_OCR, database.expenseDao.currentMonthRecords(today).single().source)
    }

    @Test fun rejectsInvalidFieldsAndDeletedCategoryEvenWithPermission() = runBlocking {
        val category = database.categoryDao.addCustomCategory("custom", 0) as CategoryMutationResult.Success
        database.categoryDao.deleteCustomCategory(category.category.key)
        val invalid = listOf(input().copy(amountCents = 0), input().copy(amountCents = MoneyLimits.MAX_CENTS + 1),
            input().copy(name = " "), input().copy(name = "x".repeat(41)), input().copy(note = "x".repeat(101)),
            input().copy(taskId = "1-1-1-1-1"), input().copy(imageHash = "%".repeat(64)),
            input().copy(imageHash = "a".repeat(63)), input().copy(categoryKey = category.category.key),
            input().copy(spentAt = Long.MAX_VALUE))
        for (value in invalid) assertTrue(value.toString(), store.save(value, true) is ScreenshotSaveResult.Invalid)
        assertTrue(rows().isEmpty())
        assertTrue(store.save(input().copy(amountCents = MoneyLimits.MAX_CENTS, name = "x".repeat(40), note = "x".repeat(100)), true) is ScreenshotSaveResult.Saved)
    }

    @Test fun categorySuggestionUsesLatestHistoricalNameOrMerchant() = runBlocking {
        database.expenseDao.addExpense(100, BuiltInCategoryKeys.FOOD, "Coffee Shop", "", day)
        database.expenseDao.addAutomaticExpense(200, BuiltInCategoryKeys.TRAVEL, "Payment", day + 1000,
            ExpenseSource.WECHAT_AUTO, "COFFEE   SHOP", "notification:1")
        // Automatic entries now resolve rules; this historical fixture represents a user correction.
        val corrected = rows().last()
        database.expenseDao.updateExpense(corrected.id, corrected.name, corrected.note, BuiltInCategoryKeys.TRAVEL)
        assertEquals(BuiltInCategoryKeys.TRAVEL, store.suggestCategory(" coffee\tshop "))
        database.expenseDao.addExpense(300, BuiltInCategoryKeys.SHOPPING, "coffee shop", "", day + 1000)
        assertEquals(BuiltInCategoryKeys.SHOPPING, store.suggestCategory("Coffee Shop"))
        assertNull(store.suggestCategory("unknown"))
        assertNull(store.suggestCategory(" "))
    }

    @Test fun restoreRejectsOldStoreAndStoreCreatedDuringRestore() = runBlocking {
        val ticket = coordinator.beginRestore()!!
        val duringRestore = ScreenshotExpenseStore(database, coordinator)
        assertEquals(ScreenshotSaveResult.Stale, store.save(input(), true))
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, {})
        assertEquals(ScreenshotSaveResult.Stale, store.save(input(), true))
        assertEquals(ScreenshotSaveResult.Stale, duringRestore.save(input(), true))
        assertTrue(rows().isEmpty())
        assertTrue(ScreenshotExpenseStore(database, coordinator).save(input(), false) is ScreenshotSaveResult.Saved)
    }

    @Test fun queuedSaveRechecksTicketAfterDispatcherSwitch() = runBlocking {
        val queued = kotlinx.coroutines.test.StandardTestDispatcher()
        val delayedStore = ScreenshotExpenseStore(database, coordinator, queued)
        val saving = async(start = CoroutineStart.UNDISPATCHED) { delayedStore.save(input(), true) }
        val ticket = coordinator.beginRestore()!!
        queued.scheduler.runCurrent()
        assertEquals(ScreenshotSaveResult.Stale, saving.await())
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, {})
        assertTrue(rows().isEmpty())
    }

    @Test fun concurrentTasksRecheckDuplicatesBeforeInsert() = runBlocking {
        val results = (1..8).map {
            async(Dispatchers.Default) { store.save(input().copy(taskId = UUID.randomUUID().toString()), false) }
        }.awaitAll()
        assertEquals(1, results.count { it is ScreenshotSaveResult.Saved })
        assertEquals(7, results.count { it is ScreenshotSaveResult.Duplicate })
        assertEquals(1, rows().size)
    }

    @Test fun concurrentSameTaskWithPermissionStillInsertsOnce() = runBlocking {
        val results = (1..8).map {
            async(Dispatchers.Default) { store.save(input(), true) }
        }.awaitAll()
        assertEquals(1, results.count { it is ScreenshotSaveResult.Saved })
        assertEquals(7, results.count { it is ScreenshotSaveResult.AlreadySaved })
        assertEquals(1, rows().size)
    }

    @Test fun screenshotSourceAndKeySurviveJsonDatabaseRoundTripAndCsv() = runBlocking {
        store.save(input(), false)
        val document = ExpenseBackupDocument(1000, database.backupDao.exportBackupDatabase(),
            ExpenseBackupSettings(100, false, true, true))
        val decoded = ExpenseBackupJson.decode(ExpenseBackupJson.encode(document))
        assertEquals(document, decoded)
        database.writableDatabase.delete("expenses", null, null)
        assertTrue(database.backupDao.restoreBackupDatabase(decoded.database))
        assertEquals(document.database, database.backupDao.exportBackupDatabase())
        assertTrue(store.save(input(), true) is ScreenshotSaveResult.AlreadySaved)
        assertTrue(ExpenseCsvExporter.encode(rows(), emptyMap()).contains("SCREENSHOT_OCR"))
        assertTrue(sourceSuffix(ExpenseSource.SCREENSHOT_OCR).contains("截图"))
    }
}
