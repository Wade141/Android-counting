package com.example.monthlyexpense.notification.work

import androidx.work.Data
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.parser.ExpenseSubtype
import com.example.monthlyexpense.notification.parser.PaymentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentImportWorkDataTest {
    @Test
    fun roundTripsEveryFieldNeededForBookkeeping() {
        val payment = payment(time = 1_700_000_000_000L)

        assertEquals(payment, PaymentImportWorkData.parse(PaymentImportWorkData.create(payment)))
    }

    @Test
    fun rejectsIncompletePersistentWorkData() {
        assertNull(PaymentImportWorkData.parse(Data.EMPTY))
    }

    @Test
    fun defaultsWorkCreatedBeforeSubtypeFieldToPurchase() {
        val legacyData = Data.Builder()
            .putString("source", PaymentSource.WECHAT.name)
            .putLong("amount_cents", 100L)
            .putString("merchant", "")
            .putLong("time", 1_000L)
            .putString("notification_key", "legacy-key")
            .putFloat("confidence", 0.9f)
            .build()

        assertEquals(ExpenseSubtype.PURCHASE, PaymentImportWorkData.parse(legacyData)?.subtype)
    }

    @Test
    fun usesStableEventIdentityForUniqueWork() {
        val first = payment(time = 1_700_000_000_000L)
        val same = first.copy()
        val later = first.copy(time = first.time + 60_000L)

        val firstName = PaymentImportWorkData.uniqueWorkName(first)
        assertEquals(firstName, PaymentImportWorkData.uniqueWorkName(same))
        assertNotEquals(firstName, PaymentImportWorkData.uniqueWorkName(later))
        assertTrue(firstName.startsWith("payment-import:v2:"))
    }

    private fun payment(time: Long) = ParsedPayment(
        source = PaymentSource.ALIPAY,
        amountCents = 2_500L,
        merchant = "示例商城",
        time = time,
        notificationKey = "synthetic-alipay-3",
        confidence = 0.93f,
        subtype = ExpenseSubtype.AUTOPAY
    )
}
