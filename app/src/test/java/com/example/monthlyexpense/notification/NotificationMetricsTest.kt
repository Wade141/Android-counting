package com.example.monthlyexpense.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationMetricsTest {
    @Test fun countsAndDurationsDoNotRequirePaymentContent() {
        NotificationMetrics.reset()
        repeat(20) { NotificationMetrics.measure(PipelineMetric.LEDGER_READ) { 42 } }
        val sample = NotificationMetrics.snapshot().getValue(PipelineMetric.LEDGER_READ)
        assertEquals(20L, sample.count)
        assertTrue(sample.totalNanos >= 0)
        assertFalse(NotificationMetrics.snapshot().containsKey(PipelineMetric.SETTINGS_READ))
    }
}
