package com.example.monthlyexpense.notification.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.parser.PaymentSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoBookkeepingRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase("expenses.db")
        database = ExpenseDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("expenses.db")
    }

    @Test
    fun recordsParsedPaymentOnceWithBookkeepingFieldsOnly() {
        val repository = AutoBookkeepingRepository(database.expenseDao)
        val payment = ParsedPayment(
            source = PaymentSource.WECHAT,
            amountCents = 2_580,
            merchant = "星巴克",
            time = 1_777_777_777_000,
            notificationKey = "notification-key",
            confidence = 0.94f
        )

        val first = repository.record(payment)
        val repeated = repository.record(payment)

        assertTrue(first.inserted)
        assertFalse(repeated.inserted)
        assertEquals(first.dedupeId, repeated.dedupeId)

        database.readableDatabase.rawQuery(
            "SELECT amount_cents, name, note, spent_at, source, merchant, source_key FROM expenses",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2_580L, cursor.getLong(0))
            assertEquals("星巴克", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals(1_777_777_777_000, cursor.getLong(3))
            assertEquals(ExpenseSource.WECHAT_AUTO.name, cursor.getString(4))
            assertEquals("星巴克", cursor.getString(5))
            assertEquals(first.dedupeId, cursor.getString(6))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun usesProviderLabelWhenMerchantIsUnavailable() {
        val repository = AutoBookkeepingRepository(database.expenseDao)
        val payment = ParsedPayment(
            source = PaymentSource.ALIPAY,
            amountCents = 880,
            merchant = null,
            time = 1_777_777_777_000,
            notificationKey = "alipay-key",
            confidence = 0.85f
        )

        assertTrue(repository.record(payment).inserted)

        database.readableDatabase.rawQuery("SELECT name, note, source FROM expenses", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("支付宝自动记账", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals(ExpenseSource.ALIPAY_AUTO.name, cursor.getString(2))
        }
    }

    @Test
    fun reusedNotificationKeyCanRecordALaterRealPayment() {
        val repository = AutoBookkeepingRepository(database.expenseDao)
        val first = ParsedPayment(
            source = PaymentSource.WECHAT,
            amountCents = 1_200,
            merchant = null,
            time = 10_000,
            notificationKey = "fixed-wechat-key",
            confidence = 0.86f
        )
        val later = first.copy(time = 20_000)

        assertTrue(repository.record(first).inserted)
        assertTrue(repository.record(later).inserted)
        assertEquals(2, expenseCount())
    }

    @Test
    fun sameNotificationUpdateWithinThreeSecondsIsSuppressedWithoutMerchant() {
        val repository = AutoBookkeepingRepository(database.expenseDao)
        val first = ParsedPayment(
            source = PaymentSource.WECHAT,
            amountCents = 1_200,
            merchant = null,
            time = 10_000,
            notificationKey = "fixed-wechat-key",
            confidence = 0.86f
        )

        assertTrue(repository.record(first).inserted)
        assertFalse(repository.record(first.copy(time = 12_000)).inserted)
        assertEquals(1, expenseCount())
    }

    @Test
    fun legacyKeyForActiveNotificationIsSuppressedOnlyDuringUpdateWindow() {
        val repository = AutoBookkeepingRepository(database.expenseDao)
        val payment = ParsedPayment(
            source = PaymentSource.WECHAT,
            amountCents = 1_200,
            merchant = null,
            time = 10_000,
            notificationKey = "fixed-wechat-key",
            confidence = 0.86f
        )
        assertTrue(
            database.expenseDao.addAutomaticExpense(
                amountCents = payment.amountCents,
                categoryKey = com.example.monthlyexpense.BuiltInCategoryKeys.OTHER,
                name = "微信自动记账",
                spentAt = payment.time,
                source = ExpenseSource.WECHAT_AUTO,
                merchant = null,
                sourceKey = "com.tencent.mm:fixed-wechat-key"
            )
        )

        assertFalse(repository.record(payment.copy(time = 12_000)).inserted)
        assertTrue(repository.record(payment.copy(time = 20_000)).inserted)
        assertEquals(2, expenseCount())
    }

    private fun expenseCount(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM expenses",
        null
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
}
