package com.example.monthlyexpense.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class DataChangePublisherTest {
    @Test fun foregroundOwnerPublishesWithoutRequestingSecondHomeRefresh() {
        val publisher = DataChangePublisher()
        publisher.publishHandledByHome(setOf(DataDomain.LEDGER, DataDomain.REVIEW))
        assertEquals(DataRevisions(ledger = 1, review = 1), publisher.revisions.value)
        assertEquals(DataRevisions(), publisher.homeRefreshes.value)
        publisher.publish(setOf(DataDomain.LEDGER))
        assertEquals(1L, publisher.homeRefreshes.value.ledger)
    }
    @Test fun emptyChangesDoNotInvalidateAndConcurrentChangesAreNotLost() {
        val publisher = DataChangePublisher()
        val start = publisher.revisions.value
        publisher.publish(emptySet())
        assertEquals(start, publisher.revisions.value)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..100).map { pool.submit { publisher.publish(setOf(DataDomain.REVIEW)) } }
            jobs.forEach { it.get() }
        } finally { pool.shutdown() }
        assertEquals(DataRevisions(review = 100), publisher.revisions.value)
        assertEquals(setOf(DataDomain.REVIEW), publisher.revisions.value.changedSince(start))
        publisher.publish(setOf(DataDomain.LEDGER, DataDomain.REVIEW))
        assertEquals(DataRevisions(ledger = 1, review = 101), publisher.revisions.value)
    }
}
