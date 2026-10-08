package com.example.monthlyexpense.notification.intake

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.monthlyexpense.AppContainer
import com.example.monthlyexpense.backup.BackupRestoreResult
import com.example.monthlyexpense.backup.ExpenseBackupManager
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import com.example.monthlyexpense.notification.NotificationIntakeRuntime
import com.example.monthlyexpense.notification.NotificationRuntime
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.repository.StoreResult
import javax.crypto.KeyGenerator
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationIntakeRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var runtime: NotificationIntakeRuntime
    private lateinit var cipher: PayloadCipher
    private val clock = IntakeClock(System.currentTimeMillis(),1_000,1)
    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        context.deleteDatabase("expenses.db")
        context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete()
        context.getSharedPreferences(AutoBookkeepingSettings.PREFERENCES_NAME,Context.MODE_PRIVATE).edit().clear().commit()
        container = AppContainer(context)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cipher = NotificationPayloadCipher { key }
        runtime = NotificationIntakeRuntime(context,container,cipher)
        ReflectionHelpers.setField(container,"notificationIntake\$delegate",lazyOf(runtime))
        runtime.initialize()
        runtime.policies.setEnabled(true,clock)
        Unit
    }
    @After fun close() = runBlocking {
        // Stop intake first: restore completion may still be creating the notification observer.
        ReflectionHelpers.getField<CoroutineScope>(runtime,"scope").coroutineContext[Job]?.cancelAndJoin()
        val notifications = ReflectionHelpers.getField<Lazy<NotificationRuntime>>(container,"notifications\$delegate")
        if (notifications.isInitialized()) {
            val observer = ReflectionHelpers.getField<CoroutineScope>(notifications.value,"scope").coroutineContext[Job]
            observer?.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            observer?.join()
            ReflectionHelpers.getField<ExecutorService>(notifications.value.snapshots,"executor").shutdownNow()
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        shadowOf(Looper.getMainLooper()).idle()
        WorkManagerTestInitHelper.closeWorkDatabase()
        runtime.inbox.database.close()
        container.database.close()
        container.replayOperations.close()
        context.deleteDatabase("expenses.db")
        context.noBackupFilesDir.resolve(NotificationInboxDatabase.NAME).delete()
        Unit
    }
    private fun save(text: String): String {
        val generation = runtime.policies.snapshot().sources.getValue(PaymentSource.WECHAT).generation
        return (runtime.inbox.persist(RawNotification(PaymentPackages.WECHAT,"微信支付",text,"",emptyList(),clock.wallTime+1,"key",null),
            container.database.backupDao.notificationProtocol.bookGeneration(),generation,1,clock=clock) as IntakePersistResult.Saved).intakeId
    }
    @Test fun sourceInitializationInsideExistingCoordinatorDoesNotReenterItsMutex() = runBlocking {
        ReflectionHelpers.setField(runtime,"legacyBindingsReady",false)
        val ticket = requireNotNull(container.persistenceCoordinator.mutationTicket())
        withTimeout(2_000) { container.persistenceCoordinator.runMutation(ticket) { runtime.initializeWithinCoordinator() } }
        Unit
    }
    @Test fun normalChatIsRemovedButPaymentCandidateRemainsForConfirmation() = runBlocking {
        val chat = save("明天见")
        val payment = save("向便利店付款成功，金额20元")
        runtime.classifyQuarantined()
        assertEquals(IntakeState.DISCARDED,runtime.inbox.find(chat)?.state)
        assertEquals(IntakeState.QUARANTINED,runtime.inbox.find(payment)?.state)
    }
    @Test fun expiredPayloadCanBeIgnoredButCannotBeConfirmedAsSaved() = runBlocking {
        val id = save("向便利店付款成功，金额20元")
        val book = container.database.backupDao.notificationProtocol.bookGeneration()
        runtime.inbox.readPayload(id,clock.wallTime+NotificationInboxRepository.RETENTION_MS)
        assertEquals(StoreResult.REJECTED,runtime.confirmQuarantined(id,book,2_000,"shop"))
        assertTrue(runtime.ignoreQuarantined(id,book))
        assertEquals(IntakeState.DISCARDED,runtime.inbox.find(id)?.state)
    }
    private suspend fun commitRestoreMarker() {
        runtime.inbox.setRestoreBarrier("restart-restore")
        val database = container.database.writableDatabase
        database.beginTransaction()
        try {
            container.database.backupDao.notificationProtocol.markRestoreCommitted(database,"restart-restore","replacement-book",
                """{"budget":10000,"enabled":true,"wechat":true,"alipay":false}""")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        ReflectionHelpers.getField<CoroutineScope>(runtime,"scope").coroutineContext[Job]?.cancelAndJoin()
        runtime.inbox.database.close()
        runtime = NotificationIntakeRuntime(context,container,cipher)
        ReflectionHelpers.setField(container,"notificationIntake\$delegate",lazyOf(runtime))
    }
    @Test fun startupReconcilesCommittedRestoreBeforeBindingLegacyEvents() = runBlocking {
        val oldGeneration = runtime.policies.snapshot().sources.getValue(PaymentSource.WECHAT).generation
        commitRestoreMarker()
        withTimeout(5_000) { runtime.initialize() }
        val protocol = container.database.backupDao.notificationProtocol
        assertNull(protocol.pendingRestore())
        assertEquals("replacement-book",protocol.bookGeneration())
        assertEquals("COMPLETED",protocol.latestRestore()?.state)
        assertNull(runtime.inbox.restoreBarrierId())
        assertEquals(oldGeneration+1,runtime.policies.snapshot().sources.getValue(PaymentSource.WECHAT).generation)
        assertEquals(SourcePolicyState.ARMING,runtime.policies.snapshot().sources.getValue(PaymentSource.WECHAT).state)
    }
    @Test fun startupSettingsFailureKeepsRestoreBlockedAndRetryOnlyFinishesSettings() = runBlocking {
        commitRestoreMarker()
        ReflectionHelpers.setField(container,"backupManager\$delegate",lazyOf(ExpenseBackupManager(
            container.database.backupDao,container.budgetSettings,container.autoSettings,runtime,settingsWriter={false})))
        withTimeout(5_000) { runtime.initialize() }
        assertNotNull(container.database.backupDao.notificationProtocol.pendingRestore())
        assertNull(container.persistenceCoordinator.mutationTicket())
        assertFalse(ReflectionHelpers.getField<Boolean>(runtime,"legacyBindingsReady"))
        val sourceGeneration = runtime.policies.snapshot().sources.getValue(PaymentSource.WECHAT).generation
        ReflectionHelpers.setField(container,"backupManager\$delegate",lazyOf(ExpenseBackupManager(
            container.database.backupDao,container.budgetSettings,container.autoSettings,runtime)))
        assertTrue(withTimeout(5_000) { runtime.continuePendingRestore() } is BackupRestoreResult.Completed)
        assertEquals("replacement-book",container.database.backupDao.notificationProtocol.bookGeneration())
        assertEquals(sourceGeneration,runtime.policies.snapshot().sources.getValue(PaymentSource.WECHAT).generation)
        assertNotNull(container.persistenceCoordinator.mutationTicket())
    }
}
