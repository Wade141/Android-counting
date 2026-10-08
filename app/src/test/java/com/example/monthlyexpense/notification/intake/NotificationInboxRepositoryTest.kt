package com.example.monthlyexpense.notification.intake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.PaymentSource
import javax.crypto.KeyGenerator
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationInboxRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NotificationInboxDatabase
    private lateinit var repo: NotificationInboxRepository
    private lateinit var policies: NotificationSourceGeneration
    private lateinit var cipher: PayloadCipher
    private val clock = IntakeClock(System.currentTimeMillis(), 1_000, 1)
    @Before fun setup() {
        context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete()
        db = NotificationInboxDatabase(context)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cipher = NotificationPayloadCipher { key }
        repo = NotificationInboxRepository(db, cipher)
        policies = NotificationSourceGeneration(db, cipher)
        policies.initialize(true, true, true, clock)
        policies.completeBoundary(PaymentSource.WECHAT, 1, emptyList(), clock)
    }
    @After fun close() { db.close(); context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete() }
    private fun raw(text: String = "payment") = RawNotification(PaymentPackages.WECHAT, "title", text, "", emptyList(), clock.wallTime + 1, "same-key", null)
    private fun save(text: String = "payment", sequence: Long = 1) = repo.persist(raw(text), "book", 1, sequence, clock = clock)
    private fun id(result: IntakePersistResult) = (result as IntakePersistResult.Saved).intakeId

    @Test fun sameKeyUpdatesRemainSeparateAndHandoffErasesPayload() {
        val first = id(save()); val second = id(save("updated", 2))
        assertNotEquals(first, second)
        assertEquals("updated", repo.readPayload(second, clock.wallTime)?.text)
        repo.acknowledgeHandoff(first, "event")
        assertNull(repo.readPayload(first, clock.wallTime))
        assertEquals("event", repo.find(first)?.eventId)
    }
    @Test fun capacityDoesNotEvictUnprocessedInputs() {
        repeat(500) { assertTrue(save(sequence = it.toLong()) is IntakePersistResult.Saved) }
        assertEquals(IntakeFailure.CAPACITY, (save() as IntakePersistResult.Rejected).reason)
        assertEquals(500, repo.pendingIds().size)
    }
    @Test fun oversizedInputIsRejectedWithoutTruncation() {
        assertEquals(IntakeFailure.TOO_LARGE, (save("a".repeat(65_536)) as IntakePersistResult.Rejected).reason)
        assertTrue(repo.pendingIds().isEmpty())
    }
    @Test fun transactionalFailureDoesNotReportSaved() {
        db.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON notification_inbox BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertEquals(IntakeFailure.STORAGE, (save() as IntakePersistResult.Rejected).reason)
        assertTrue(repo.pendingIds().isEmpty())
    }
    @Test fun retryBudgetAndBackoffSurviveRepositoryRecreation() {
        val intakeId = id(save())
        assertNotNull(repo.claim(intakeId, clock.wallTime))
        repo.retry(intakeId, clock.wallTime)
        db.close()
        db = NotificationInboxDatabase(context)
        repo = NotificationInboxRepository(db,cipher)
        assertNull(repo.claim(intakeId, clock.wallTime + 9_999))
        assertNotNull(repo.claim(intakeId, clock.wallTime + 10_000))
        repo.retry(intakeId, clock.wallTime + 10_000)
        assertNull(repo.claim(intakeId, clock.wallTime + 69_999))
        assertNotNull(repo.claim(intakeId, clock.wallTime + 70_000))
        repo.retry(intakeId, clock.wallTime + 70_000)
        assertEquals(IntakeState.FAILED, repo.find(intakeId)?.state)
        assertNull(repo.claim(intakeId, clock.wallTime + 999_999))
    }
    @Test fun expiryRejectsPayloadEvenBeforeMaintenance() {
        val intakeId = id(save())
        assertNull(repo.readPayload(intakeId, clock.wallTime + NotificationInboxRepository.RETENTION_MS))
        assertEquals(IntakeState.EXPIRED, repo.find(intakeId)?.state)
    }
    @Test fun persistedInputStillCanBeHandedOffAfterSystemDiscoveryWindowExpires() {
        val intakeId = id(save())
        val later = clock.wallTime + 31 * 60_000L
        assertNotNull(repo.readPayload(intakeId, later))
        assertNotNull(repo.claim(intakeId, later))
        repo.acknowledgeHandoff(intakeId, "saved-event")
        assertEquals(IntakeState.HANDED_OFF, repo.find(intakeId)?.state)
        assertEquals("saved-event", repo.find(intakeId)?.eventId)
    }
    @Test fun fixedBatchCannotAbsorbNotificationArrivingAfterFinalRead() {
        val first = id(save())
        val batch = repo.createBatch(clock.wallTime)!!
        assertEquals(listOf(first), repo.batchMembers(batch.batchId).map { it.intakeId })
        val late = id(save("later", 2))
        repo.finishBatch(batch.batchId)
        val next = repo.createBatch(clock.wallTime)!!
        assertNotEquals(batch.batchId, next.batchId)
        assertTrue(repo.batchMembers(next.batchId).any { it.intakeId == late })
        assertEquals(listOf(first), repo.batchMembers(batch.batchId).map { it.intakeId })
    }
    @Test fun closingAndReopeningNeverRevivesOldGeneration() {
        val first = id(save())
        policies.setSourceEnabled(PaymentSource.WECHAT, false, clock)
        policies.setSourceEnabled(PaymentSource.WECHAT, true, clock)
        assertEquals(IntakeState.DISCARDED, repo.find(first)?.state)
        assertNull(repo.readPayload(first))
        assertEquals(IntakeFailure.SOURCE_CHANGED, (save() as IntakePersistResult.Rejected).reason)
        assertFalse(policies.allows(PaymentSource.WECHAT, 1))
    }
    @Test fun callbackCapturedBeforeInitializationIsIsolatedAfterImportWinsRace() {
        val saved = repo.persist(raw(), "book", 0, 1, clock=clock) as IntakePersistResult.Saved
        assertEquals(QuarantineReason.INITIALIZING,repo.find(saved.intakeId)?.reason)
        assertEquals(IntakeState.QUARANTINED,repo.find(saved.intakeId)?.state)
        assertNull(repo.claim(saved.intakeId,clock.wallTime))
    }
    @Test fun encryptedPayloadBudgetAndNoBackupLocationAreEnforced() {
        assertEquals(context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).canonicalPath,
            java.io.File(db.readableDatabase.path).canonicalPath)
        var saved = 0
        var result: IntakePersistResult
        do {
            result = save("x".repeat(60_000),saved.toLong())
            if(result is IntakePersistResult.Saved) saved++
        } while(result is IntakePersistResult.Saved)
        assertTrue(saved in 1..499)
        assertEquals(IntakeFailure.CAPACITY,(result as IntakePersistResult.Rejected).reason)
        assertTrue(NotificationInboxRepository.payloadUsage(db.readableDatabase).second <= NotificationInboxRepository.MAX_TOTAL_BYTES)
    }
    @Test fun cipherFailureCannotSavePlaintext() {
        val unavailable = NotificationInboxRepository(db,object : PayloadCipher {
            override fun encrypt(plaintext: ByteArray,identity: String): EncryptedPayload = error("unavailable")
            override fun decrypt(payload: EncryptedPayload,identity: String): ByteArray = error("unavailable")
        })
        assertEquals(IntakePersistResult.Rejected(IntakeFailure.ENCRYPTION),unavailable.persist(raw(),"book",1,1,clock=clock))
        assertTrue(repo.pendingIds().isEmpty())
    }
    @Test fun queuedPreRestoreInputIsQuarantinedEvenAfterPoliciesAndBookHaveAdvanced() {
        repo.setRestoreBarrier("restore")
        policies.applyRestoredSettings("restore",true,true,true,clock)
        repo.setBookGeneration("new-book")
        repo.setRestoreBarrier(null)
        val saved = save() as IntakePersistResult.Saved
        assertEquals(QuarantineReason.BOOK_CHANGED,repo.find(saved.intakeId)?.reason)
        assertNull(repo.claim(saved.intakeId,clock.wallTime))
    }
}
