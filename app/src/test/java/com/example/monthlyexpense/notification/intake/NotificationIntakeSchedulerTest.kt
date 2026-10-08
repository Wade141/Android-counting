package com.example.monthlyexpense.notification.intake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.PaymentSource
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationIntakeSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NotificationInboxDatabase
    private lateinit var repo: NotificationInboxRepository
    private lateinit var scheduler: NotificationIntakeScheduler
    private lateinit var scope: CoroutineScope
    private val clock = IntakeClock(System.currentTimeMillis(),1_000,1)
    private val states = mutableMapOf<String,IntakeWorkState>()
    private val enqueued = mutableListOf<Pair<String,Long>>()
    private var failEnqueue = false
    @Before fun setup() {
        context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete()
        db = NotificationInboxDatabase(context)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = NotificationPayloadCipher { key }
        repo = NotificationInboxRepository(db,cipher)
        NotificationSourceGeneration(db,cipher).apply { initialize(true,true,true,clock); completeBoundary(PaymentSource.WECHAT,1,emptyList(),clock) }
        scope = CoroutineScope(SupervisorJob())
        scheduler = NotificationIntakeScheduler(scope,repo,object : IntakeBatchWork {
            override suspend fun state(batchId: String) = states[batchId] ?: IntakeWorkState.MISSING
            override suspend fun enqueue(batchId: String, initialDelayMillis: Long) {
                if(failEnqueue) error("enqueue failed")
                enqueued += batchId to initialDelayMillis; states[batchId] = IntakeWorkState.ACTIVE
            }
        },reconcile={ id -> repo.batchMembers(id).filter { it.state == IntakeState.PROCESSING }.forEach { repo.retry(it.intakeId,clock.wallTime) } },now={ clock.wallTime })
    }
    @After fun close(): Unit = runBlocking { scope.coroutineContext[Job]?.cancelAndJoin(); db.close(); context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete(); Unit }
    private fun save() = (repo.persist(RawNotification(PaymentPackages.WECHAT,"title","pay","",emptyList(),clock.wallTime+1,"key",null),"book",1,1,clock=clock) as IntakePersistResult.Saved).intakeId
    @Test fun constructionCrashIsRecoveredWithoutChangingFixedMembership() = runBlocking {
        val id = save(); val batch = repo.createBatch(clock.wallTime)!!
        scheduler.schedulePending()
        assertEquals(listOf(batch.batchId to 0L),enqueued)
        assertEquals(listOf(id),repo.batchMembers(batch.batchId).map { it.intakeId })
    }
    @Test fun finishedAndPrunedWorkGetNewIdentityInsteadOfReusingTerminalKeep() = runBlocking {
        save(); scheduler.schedulePending(); val first = enqueued.single().first
        states[first] = IntakeWorkState.FINISHED
        scheduler.schedulePending(); val second = enqueued.last().first
        assertNotEquals(first,second)
        states.remove(second)
        scheduler.schedulePending(); assertNotEquals(second,enqueued.last().first)
        assertEquals(1,repo.unfinishedBatches().size)
    }
    @Test fun futureRetriesAreOneDelayedBatchAndFailedEnqueueDoesNotSpin() = runBlocking {
        val id = save(); repo.claim(id,clock.wallTime); repo.retry(id,clock.wallTime)
        failEnqueue = true
        assertTrue(runCatching { scheduler.schedulePending() }.isFailure)
        assertEquals(1,repo.unfinishedBatches().size)
        failEnqueue = false; scheduler.schedulePending()
        assertEquals(10_000L,enqueued.single().second)
        scheduler.schedulePending(); assertEquals(1,enqueued.size)
    }
}
