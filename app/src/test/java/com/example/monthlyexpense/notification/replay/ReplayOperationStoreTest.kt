package com.example.monthlyexpense.notification.replay

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReplayOperationStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var wall = 10_000L
    private var elapsed = 5_000L
    private var boot: Int? = 7
    private var session = "process-a"
    private lateinit var store: ReplayOperationStore

    @Before fun open() {
        File(context.noBackupFilesDir, "notification-replay.db").delete()
        store = newStore()
    }
    @After fun close() { store.close() }
    private fun newStore() = ReplayOperationStore(context, now = { wall }, elapsed = { elapsed }, bootCount = { boot }, processSessionId = session)
    private fun create(id: String = "manual", manual: Boolean = true) = store.create(id, manual, "book-a")
    private fun collect(id: String = "manual") {
        store.setFence(id, "session", 3)
        ReplayCollectionSource.entries.forEach { store.markCollected(id, it) }
        assertTrue(store.sealCollection(id))
    }

    @Test fun frozenRangeDeduplicatesEventsAndRejectsLaterExpansion() {
        create()
        store.registerItems("manual", listOf(ReplayOperationItem("i1", "event"), ReplayOperationItem("i2", "event")))
        collect()
        assertEquals(1, store.get("manual")!!.items.size)
        assertThrows(IllegalStateException::class.java) {
            store.registerItems("manual", listOf(ReplayOperationItem("late", "late-event")))
        }
    }

    @Test fun incompleteCollectionNeverReportsCompleteEvenWithAllReceipts() {
        create()
        store.registerItems("manual", listOf(ReplayOperationItem("i1", "e1")))
        store.applyReceipts("manual", listOf(ReplayOperationReceipt("i1", "e1", "book-a", ReplayItemResult.BOOKED)))
        val result = store.finish("manual")
        assertEquals(ReplayPhase.PARTIAL, result.phase)
        assertEquals(1, result.summary.booked)
    }

    @Test fun restartKeepsAttemptBudgetAndOriginalDeadline() {
        create()
        val attempt = store.beginAttempt("manual")!!
        val budget = store.budget("manual", attempt.attemptId)!!
        repeat(3) { assertTrue(budget.reserveRead()) }
        wall += 80_000; elapsed += 80_000
        store.close(); store = newStore()
        assertEquals(attempt.attemptId, store.beginAttempt("manual")!!.attemptId)
        assertEquals(70_000L, store.remainingMillis("manual", attempt.attemptId))
        assertFalse(store.budget("manual", attempt.attemptId)!!.reserveRead())
        boot = boot!! + 1
        assertEquals(0L, store.remainingMillis("manual", attempt.attemptId))
    }

    @Test fun retryPreservesLogicalIdReceiptsAndSealedMembers() {
        create()
        val attempt = store.beginAttempt("manual")!!
        store.registerItems("manual", listOf(ReplayOperationItem("i1", "e1"), ReplayOperationItem("i2", "e2")))
        collect()
        store.applyReceipts("manual", listOf(ReplayOperationReceipt("i1", "e1", "book-a", ReplayItemResult.BOOKED)))
        assertEquals(ReplayPhase.PARTIAL, store.finish("manual").phase)
        val retry = store.beginAttempt("manual", userRetry = true)!!
        assertNotEquals(attempt.attemptId, retry.attemptId)
        assertTrue(store.get("manual")!!.collectionSealed)
        assertEquals(1, store.get("manual")!!.summary.booked)
        store.applyReceipts("manual", listOf(ReplayOperationReceipt("i2", "e2", "book-a", ReplayItemResult.REVIEW)))
        assertEquals(ReplayPhase.COMPLETED, store.finish("manual").phase)
    }

    @Test fun dismissSurvivesRestartAndAutomaticResultDoesNotReplaceManual() = runBlocking {
        create(); collect(); store.finish("manual"); store.dismiss("manual")
        create("auto", manual = false)
        store.close(); store = newStore()
        assertTrue(store.latestManual()!!.dismissed)
        assertEquals("manual", store.observeLatestManual().first()!!.operationId)
        assertNull(ReplayResultPresentation.from(store.latestManual()!!, ConnectionStatus.UNKNOWN))
    }

    @Test fun newManualSupersedesOldResultWithoutAutomaticInterference() {
        create(); collect(); store.finish("manual")
        create("next")
        assertTrue(store.get("manual")!!.superseded)
        assertEquals("next", store.latestManual()!!.operationId)
    }

    @Test fun changedBookInterruptsOperationAndRejectsOldReceipts() {
        create(); collect()
        store.recoverInterrupted("book-b")
        assertEquals(ReplayPhase.INTERRUPTED, store.get("manual")!!.phase)
        assertNull(store.beginAttempt("manual", userRetry = true))
        assertThrows(IllegalStateException::class.java) {
            store.applyReceipts("manual", listOf(ReplayOperationReceipt("x", null, "book-b", ReplayItemResult.BOOKED)))
        }
    }

    @Test fun restartMarksUnsealedInProgressRangeInterrupted() {
        create(); store.beginAttempt("manual"); store.updatePhase("manual", ReplayPhase.COLLECTING)
        store.recoverInterrupted("book-a")
        assertEquals(ReplayPhase.INTERRUPTED, store.get("manual")!!.phase)
        assertNotNull(store.beginAttempt("manual", userRetry = true))
    }

    @Test fun receiptValidationRollsBackWholeSummaryUpdate() {
        create()
        store.registerItems("manual", listOf(ReplayOperationItem("i1", "e1")))
        assertThrows(IllegalArgumentException::class.java) {
            store.applyReceipts("manual", listOf(
                ReplayOperationReceipt("i1", "e1", "book-a", ReplayItemResult.BOOKED),
                ReplayOperationReceipt("unknown", "e2", "book-a", ReplayItemResult.BOOKED)
            ))
        }
        assertEquals(0, store.get("manual")!!.summary.booked)
        assertEquals(1, store.get("manual")!!.summary.unfinished)
    }

    @Test fun connectionConfirmationAloneDoesNotProduceSuccessPopup() {
        create(); store.confirmConnection("manual", 12)
        assertNull(ReplayResultPresentation.from(store.get("manual")!!, ConnectionStatus.CONNECTED))
        collect(); store.finish("manual")
        val result = ReplayResultPresentation.from(store.get("manual")!!, ConnectionStatus.DISCONNECTED)!!
        assertEquals("通知接收当前已断开。", result.connectionMessage)
        assertEquals("本次检查完成，没有新增账目。", result.resultMessage)
    }

    @Test fun staleBudgetInstanceCannotRefundAnotherExecutorsReservation() {
        create()
        val attempt = store.beginAttempt("manual")!!
        val first = store.budget("manual", attempt.attemptId)!!
        val stale = store.budget("manual", attempt.attemptId)!!
        assertTrue(first.reserveRead())
        assertFalse(stale.reserveRead())
        assertEquals(1, store.get("manual")!!.attempt!!.budget.reads)
    }

    @Test fun snapshotAndIntakeAliasesCountSingleCommittedEvent() {
        create()
        store.registerItems("manual", listOf(ReplayOperationItem("intake"), ReplayOperationItem("snapshot", "event")))
        collect()
        store.resolveItemEvent("manual", "intake", "event")
        store.applyReceipts("manual", listOf(ReplayOperationReceipt("intake", "event", "book-a", ReplayItemResult.BOOKED)))
        assertEquals(1, store.finish("manual").summary.booked)
        assertEquals(1, store.get("manual")!!.items.size)
    }

    @Test fun collectionWithoutMemoryFenceCannotBeSealed() {
        create()
        ReplayCollectionSource.entries.forEach { store.markCollected("manual", it) }
        assertFalse(store.sealCollection("manual"))
        assertEquals(ReplayPhase.FAILED, store.finish("manual").phase)
    }

    @Test fun resultWriteFailureCannotPublishCompletedState() {
        create(); collect()
        store.updatePhase("manual", ReplayPhase.PROCESSING)
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "notification-replay.db").absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER reject_result BEFORE UPDATE ON replay_operations BEGIN SELECT RAISE(ABORT, 'full'); END")
        }
        assertThrows(RuntimeException::class.java) { store.finish("manual") }
        assertEquals(ReplayPhase.PROCESSING, store.get("manual")!!.phase)
        assertNull(ReplayResultPresentation.from(store.get("manual")!!, ConnectionStatus.CONNECTED))
    }

    @Test fun latestUndismissedManualResultIsRetainedBeyondThirtyDays() {
        create(); collect(); store.finish("manual")
        wall += 31L * 24 * 60 * 60 * 1000
        assertTrue(store.prune().isEmpty())
        store.dismiss("manual")
        assertEquals(listOf("manual"), store.prune())
        assertNull(store.latestManual())
    }

    @Test fun budgetRejectsElapsedExpiryEvenWhenWallClockDidNotAdvance() {
        create()
        val attempt = store.beginAttempt("manual")!!
        val budget = store.budget("manual", attempt.attemptId)!!
        elapsed += 150_001L
        assertFalse(budget.reserveRecovery())
        assertEquals(0L, store.remainingMillis("manual", attempt.attemptId))
        assertFalse(store.get("manual")!!.attempt!!.budget.recoveryUsed)
    }

    @Test fun automaticResultNeverProducesPopup() {
        create("auto", manual = false); collect("auto"); store.finish("auto")
        assertNull(ReplayResultPresentation.from(store.get("auto")!!, ConnectionStatus.CONNECTED))
    }

    @Test fun aliasReceiptsKeepOriginalBookedCreditWhenDuplicateWasAlreadyProcessed() {
        create()
        store.registerItems("manual", listOf(ReplayOperationItem("first", "event"), ReplayOperationItem("alias", "event")))
        collect()
        store.applyReceipts("manual", listOf(
            ReplayOperationReceipt("first", "event", "book-a", ReplayItemResult.BOOKED),
            ReplayOperationReceipt("alias", "event", "book-a", ReplayItemResult.ALREADY_PROCESSED)
        ))
        assertEquals(1, store.finish("manual").summary.booked)
        assertEquals(0, store.get("manual")!!.summary.alreadyProcessed)
    }

    @Test fun newManualOperationTerminatesSupersededQueuedOperationForBoundedCleanup() {
        create(); create("next")
        assertEquals(ReplayPhase.INTERRUPTED, store.get("manual")!!.phase)
        wall += 31L * 24 * 60 * 60 * 1000
        assertEquals(listOf("manual"), store.prune())
        assertEquals("next", store.latestManual()!!.operationId)
    }

    @Test fun cleanupReceiptIdsRemainRecoverableIfLedgerCleanupIsInterrupted() {
        create(); collect(); store.finish("manual"); store.dismiss("manual")
        wall += 31L * 24 * 60 * 60 * 1000
        assertEquals(listOf("manual"), store.prune())
        store.close(); store = newStore()
        assertEquals(listOf("manual"), store.prune())
    }

    @Test fun missingBootCountAllowsSameProcessOnlyWithoutResettingBudget() {
        boot = null
        create()
        val attempt = store.beginAttempt("manual")!!
        assertTrue(store.budget("manual", attempt.attemptId)!!.reserveRead())
        store.close(); store = newStore()
        assertEquals(1, store.budget("manual", attempt.attemptId)!!.state.reads)
        store.close(); session = "process-b"; store = newStore()
        assertNull(store.budget("manual", attempt.attemptId))
    }

    @Test fun liveBookInvalidationDoesNotInterruptOtherSameBookOperations() {
        create()
        store.beginAttempt("manual")
        store.invalidateBookGeneration("book-a")
        assertEquals(ReplayPhase.QUEUED, store.get("manual")!!.phase)
        store.invalidateBookGeneration("book-b")
        assertEquals(ReplayTerminationReason.BOOK_CHANGED, store.get("manual")!!.reason)
    }

    @Test fun referencesIncludeCanonicalIdsAndAliasesWhileRetryRemainsPossible() {
        create()
        store.registerItems("manual", listOf(ReplayOperationItem("one", "event"), ReplayOperationItem("alias", "event")))
        assertEquals(setOf("one", "alias"), store.referencedIntakeIds())
        store.finish("manual", ReplayTerminationReason.READ)
        assertEquals(setOf("one", "alias"), store.referencedIntakeIds())
        store.invalidateBookGeneration("book-b")
        assertTrue(store.referencedIntakeIds().isEmpty())
    }
}
