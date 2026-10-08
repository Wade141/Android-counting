package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.parser.PaymentSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentImportSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun initializeWorkManager() {
        context.deleteDatabase("androidx.work.workdb")
        context.deleteDatabase("expenses.db")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After
    fun deleteDatabases() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.deleteDatabase("androidx.work.workdb")
        context.deleteDatabase("expenses.db")
    }

    @Test
    fun returnsOnlyAfterUniqueWorkIsPersisted() = runBlocking {
        val payment = ParsedPayment(
            source = PaymentSource.WECHAT,
            amountCents = 1_680L,
            merchant = null,
            time = 1_700_000_000_000L,
            notificationKey = "synthetic-wechat-1",
            confidence = 0.86f
        )
        val scheduler = PaymentImportScheduler(context)

        val workName = scheduler.enqueueAndAwait(payment)
        scheduler.enqueueAndAwait(payment)

        val persisted = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(workName)
            .get()
        assertEquals(1, persisted.size)
    }
}
