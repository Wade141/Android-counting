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
class NotificationSourceGenerationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NotificationInboxDatabase
    private lateinit var cipher: PayloadCipher
    private lateinit var policies: NotificationSourceGeneration
    private lateinit var repo: NotificationInboxRepository
    private val source = PaymentSource.WECHAT
    private val clock = IntakeClock(System.currentTimeMillis(), 1_000, 1)
    @Before fun setup() {
        context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete()
        db = NotificationInboxDatabase(context)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cipher = NotificationPayloadCipher { key }
        policies = NotificationSourceGeneration(db,cipher)
        repo = NotificationInboxRepository(db,cipher)
        policies.initialize(true,true,true,clock)
    }
    @After fun close() { db.close(); context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete() }
    private fun raw(time: Long = clock.wallTime+1,key: String = "key") = RawNotification(PaymentPackages.WECHAT,"title","pay","",emptyList(),time,key,null)
    @Test fun failedPolicyCommitNeverPublishesOptimisticState() {
        policies.completeBoundary(source,1,emptyList(),clock)
        db.writableDatabase.execSQL("CREATE TRIGGER fail_policy BEFORE INSERT ON source_policies BEGIN SELECT RAISE(ABORT,'test'); END")
        assertTrue(runCatching { policies.setSourceEnabled(source,false,clock) }.isFailure)
        assertTrue(policies.allows(source,1))
        assertEquals(SourcePolicyState.ON, policies.reload().sources[source]?.state)
    }
    @Test fun failedPreferenceMirrorDoesNotUndoCommittedControlPolicy() {
        policies = NotificationSourceGeneration(db,cipher) { error("mirror unavailable") }
        policies.setSourceEnabled(source,false,clock)
        assertFalse(policies.snapshot().sources.getValue(source).selected)
        assertEquals(2L,policies.snapshot().sources.getValue(source).generation)
        assertEquals(SourcePolicyState.OFF,policies.reload().sources[source]?.state)
    }
    @Test fun failedBoundaryLeavesArmingAndExplicitFailure() {
        policies.boundaryFailed(source,1,"READ_FAILED")
        assertEquals(SourcePolicyState.ARMING,policies.snapshot().sources[source]?.state)
        assertEquals("READ_FAILED",policies.snapshot().sources[source]?.failure)
        assertFalse(policies.allows(source,1))
    }
    @Test fun boundaryReleasesOnlyDefinitelyNewCallbacksAndKeepsOldKeyUpdates() {
        val exact = repo.persist(raw(clock.wallTime),"book",1,1,clock=clock) as IntakePersistResult.Saved
        val fresh = repo.persist(raw(key="fresh"),"book",1,2,clock=clock) as IntakePersistResult.Saved
        val update = repo.persist(raw(key="old"),"book",1,3,clock=clock) as IntakePersistResult.Saved
        assertTrue(policies.completeBoundary(source,1,listOf(raw(clock.wallTime-1,"old")),clock))
        assertEquals(IntakeState.QUARANTINED,repo.find(exact.intakeId)?.state)
        assertEquals(IntakeState.PENDING,repo.find(fresh.intakeId)?.state)
        assertEquals(IntakeState.QUARANTINED,repo.find(update.intakeId)?.state)
        assertEquals(QuarantineReason.ENABLE_BOUNDARY,policies.classify(raw(key="old"),clock))
    }
    @Test fun restartUnknownBootAndClockDriftFailClosed() {
        assertFalse(policies.completeBoundary(source,1,emptyList(),clock.copy(bootCount=2)))
        assertFalse(policies.completeBoundary(source,1,emptyList(),clock.copy(bootCount=null)))
        assertFalse(policies.completeBoundary(source,1,emptyList(),clock.copy(wallTime=clock.wallTime+2_001)))
        assertEquals(SourcePolicyState.ARMING,policies.snapshot().sources[source]?.state)
    }
    @Test fun restoreBarrierNeverReleasesBoundaryRowsIntoAutomaticProcessing() {
        repo.setRestoreBarrier("restore")
        val saved = repo.persist(raw(),"book",1,1,clock=clock) as IntakePersistResult.Saved
        assertTrue(policies.completeBoundary(source,1,emptyList(),clock))
        repo.setRestoreBarrier(null)
        assertEquals(QuarantineReason.BOOK_RESTORE,repo.find(saved.intakeId)?.reason)
        assertNull(repo.claim(saved.intakeId,clock.wallTime))
    }
    @Test fun restoredPoliciesChangeGenerationOnceAndPreserveOldCandidatesForConfirmation() {
        policies.completeBoundary(source,1,emptyList(),clock)
        val saved = repo.persist(raw(),"old-book",1,1,clock=clock) as IntakePersistResult.Saved
        policies.applyRestoredSettings("restore-id",true,true,true,clock)
        policies.applyRestoredSettings("restore-id",true,true,true,clock)
        assertEquals(2L,policies.snapshot().sources.getValue(source).generation)
        assertEquals(SourcePolicyState.ARMING,policies.snapshot().sources.getValue(source).state)
        assertEquals(QuarantineReason.BOOK_CHANGED,repo.find(saved.intakeId)?.reason)
        assertNotNull(repo.readPayload(saved.intakeId,clock.wallTime))
    }
    @Test fun rebootReestablishesBoundaryWithoutInvalidatingPreviouslyLegalInput() {
        policies.completeBoundary(source,1,listOf(raw(clock.wallTime-1,"disabled-key")),clock)
        val saved = repo.persist(raw(key="legal"),"book",1,1,clock=clock) as IntakePersistResult.Saved
        val reboot = IntakeClock(clock.wallTime+30_000,100,2)
        policies.rearmUncertainBoundaries(reboot)
        assertEquals(SourcePolicyState.ARMING,policies.snapshot().sources.getValue(source).state)
        assertNull(repo.createBatch(reboot.wallTime))
        assertTrue(policies.completeBoundary(source,1,emptyList(),reboot))
        assertNotNull(repo.claim(saved.intakeId,reboot.wallTime))
        assertEquals(QuarantineReason.ENABLE_BOUNDARY,policies.classify(raw(reboot.wallTime+1,"disabled-key"),reboot))
    }
}
