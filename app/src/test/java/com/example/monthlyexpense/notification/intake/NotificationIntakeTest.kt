package com.example.monthlyexpense.notification.intake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.PaymentSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationIntakeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NotificationInboxDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: NotificationInboxRepository
    private lateinit var policies: NotificationSourceGeneration
    private lateinit var intake: NotificationIntake
    private val clock = IntakeClock(System.currentTimeMillis(),1_000,1)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Before fun setup() {
        context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete()
        db = NotificationInboxDatabase(context)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val delegate = NotificationPayloadCipher { key }
        val cipher = object : PayloadCipher {
            override fun encrypt(plaintext: ByteArray, identity: String): EncryptedPayload {
                if(identity.startsWith("intake:")) { entered.countDown(); check(release.await(15,TimeUnit.SECONDS)) }
                return delegate.encrypt(plaintext,identity)
            }
            override fun decrypt(payload: EncryptedPayload, identity: String) = delegate.decrypt(payload,identity)
        }
        repo = NotificationInboxRepository(db,cipher)
        policies = NotificationSourceGeneration(db,cipher)
        policies.initialize(true,true,true,clock)
        policies.completeBoundary(PaymentSource.WECHAT,1,emptyList(),clock)
        scope = CoroutineScope(SupervisorJob())
        intake = NotificationIntake(scope,repo,policies,{"book"},{},clock={clock})
    }
    @After fun close(): Unit = runBlocking { release.countDown(); scope.coroutineContext[Job]?.cancelAndJoin(); db.close(); context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete(); Unit }
    private fun raw() = RawNotification(PaymentPackages.WECHAT,"title","pay","",emptyList(),clock.wallTime+1,"key",null)
    @Test fun fenceWaitsForAcceptedMemoryInputAndServiceLifetimeIsIndependent() = runBlocking {
        assertTrue(intake.offer(raw()) is IntakeEnqueueResult.Accepted)
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val fence = intake.captureFence()
        assertFalse(fence.result.isCompleted)
        // Destroying a service-owned scope cannot cancel the application-owned writer.
        CoroutineScope(SupervisorJob()).cancel()
        release.countDown()
        val result = withTimeout(10_000) { intake.awaitFence(fence) }
        assertTrue(result.complete)
        assertEquals(1L,result.committedCount)
        assertEquals(1,repo.listUpTo(fence.processSessionId,fence.sequence).size)
    }
    @Test fun fullQueueIsExplicitAndFenceControlDoesNotNeedAQueueSlot() = runBlocking {
        intake.offer(raw()); assertTrue(entered.await(5,TimeUnit.SECONDS))
        repeat(128) { assertTrue(intake.offer(raw()) is IntakeEnqueueResult.Accepted) }
        assertEquals(IntakeEnqueueResult.Rejected(IntakeFailure.QUEUE_FULL),intake.offer(raw()))
        val fence = intake.captureFence()
        assertEquals(129L,fence.sequence)
        release.countDown()
        val result = withTimeout(15_000) { intake.awaitFence(fence) }
        assertFalse(result.complete)
        assertEquals(129L,result.committedCount)
        assertEquals(1L,result.rejectedCount)
        assertTrue(intake.awaitFence(intake.captureFence()).complete)
    }
    @Test fun disablingWhileEncryptionWaitsPreventsOldMemoryTaskFromCommitting() = runBlocking {
        intake.offer(raw()); assertTrue(entered.await(5,TimeUnit.SECONDS))
        policies.setSourceEnabled(PaymentSource.WECHAT,false,clock)
        policies.setSourceEnabled(PaymentSource.WECHAT,true,clock)
        val fence = intake.captureFence(); release.countDown()
        val result = withTimeout(10_000) { intake.awaitFence(fence) }
        assertFalse(result.complete)
        assertEquals(1L,result.failedCount)
        assertTrue(repo.listUpTo(fence.processSessionId,fence.sequence).isEmpty())
    }
    @Test fun aReportedFailureDoesNotPermanentlyPoisonASeparateCollectionAttempt() = runBlocking {
        intake.offer(raw()); assertTrue(entered.await(5,TimeUnit.SECONDS))
        policies.setSourceEnabled(PaymentSource.WECHAT,false,clock)
        val failed = intake.captureFence()
        val later = intake.captureFence()
        release.countDown()
        assertFalse(withTimeout(10_000) { intake.awaitFence(failed) }.complete)
        assertTrue(withTimeout(10_000) { intake.awaitFence(later) }.complete)
        // Reading the same fence again preserves its original outcome.
        assertFalse(intake.awaitFence(failed).complete)
    }
}
