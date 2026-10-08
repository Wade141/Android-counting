package com.example.monthlyexpense.notification

import org.junit.Assert.*
import org.junit.Test

class ReplayBatchImporterTest {
    private val now = 1_800_000_000_000L
    private fun raw(key: String, text: String = "付款成功 ￥8.80", time: Long = now) = RawNotification(
        PaymentPackages.ALIPAY, "支付宝", text, "", emptyList(), time, key, null)

    @Test fun countsCommittedRecordsAndDuplicatesInsteadOfReceivedNotifications() {
        val saved = mutableSetOf<String>()
        saved.add("old")
        val importer = ReplayBatchImporter({ true }) { saved.add(it.notificationKey) }
        val result = importer.import(listOf(raw("new"), raw("new"), raw("old"), raw("ad", "领红包"),
            raw("expired", time = now - 1_800_001)), now)
        assertEquals(ReplaySummary(inserted = 1, existing = 1, unmatched = 1), result)
        assertEquals(setOf("old", "new"), saved)
        assertEquals(ReplaySummary(existing = 2), importer.import(listOf(raw("new"), raw("old")), now))
    }

    @Test fun storageFailureIsNotReportedAsSuccessAndDisabledSourcesAreNotWritten() {
        val failing = ReplayBatchImporter({ true }) { throw IllegalStateException("storage failure") }
        assertEquals(1, failing.import(listOf(raw("new")), now).failed)
        val disabled = ReplayBatchImporter({ false }) { error("must not record") }
        assertEquals(ReplaySummary(), disabled.import(listOf(raw("new")), now))
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancellationIsNotSwallowedAsOneFailedPayment() {
        ReplayBatchImporter({ true }) { throw kotlinx.coroutines.CancellationException() }
            .import(listOf(raw("new")), now)
    }
}
