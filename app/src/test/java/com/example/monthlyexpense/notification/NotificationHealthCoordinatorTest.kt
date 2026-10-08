package com.example.monthlyexpense.notification

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationHealthCoordinatorTest {
    @Test fun frequentResumesDoNotExtendThrottleForever() = runTest {
        var reads = 0
        val health = NotificationHealthCoordinator({ true }, { false }, { 1 }, {
            reads++; SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList()))
        }, { error("must not repair") }, { testScheduler.currentTime })
        health.check()
        repeat(6) { advanceTimeBy(10_000); health.check() }
        assertEquals(2, reads)
    }

    @Test fun disablingDuringConfirmationStopsBeforeRepair() = runTest {
        var enabled = true
        var reads = 0
        val health = NotificationHealthCoordinator({ enabled }, { false }, { 1 }, {
            reads++; enabled = false; SnapshotResult.Unavailable
        }, { error("disabled must not repair") }, { testScheduler.currentTime })
        health.check()
        assertEquals(1, reads)
    }

    @Test fun emptySnapshotIsHealthyAndForegroundIsThrottled() = runTest {
        var reads = 0
        val health = NotificationHealthCoordinator({ true }, { false }, { 1 }, {
            reads++; SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList()))
        }, { error("must not repair") }, { testScheduler.currentTime })
        health.check()
        health.check()
        assertEquals(1, reads)
        advanceTimeBy(60_000)
        health.check()
        assertEquals(2, reads)
    }
    @Test fun transientReadFailureDoesNotResetListener() = runTest {
        var reads = 0
        val health = NotificationHealthCoordinator({ true }, { false }, { 1 }, {
            if (++reads == 1) SnapshotResult.Unavailable else SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList()))
        }, { error("must not repair") }, { testScheduler.currentTime })
        health.check()
        assertEquals(2, reads)
        assertEquals(2_000L, testScheduler.currentTime)
    }
    @Test fun repeatedFailureRepairsOnceButBusyDoesNotRepair() = runTest {
        for (outcome in listOf(SnapshotResult.Unavailable, SnapshotResult.Busy, SnapshotResult.TimedOut)) {
            var repairs = 0
            var reads = 0
            val health = NotificationHealthCoordinator({ true }, { false }, { 7 }, { reads++; outcome },
                { assertEquals(7L, it); repairs++; true }, { testScheduler.currentTime })
            health.check()
            assertEquals(if (outcome == SnapshotResult.Busy) 0 else 1, repairs)
            assertEquals(if (outcome == SnapshotResult.Unavailable) 2 else 1, reads)
        }
    }
    @Test fun changedConnectionCannotBeRepairedByOldFailure() = runTest {
        var epoch = 1L
        val health = NotificationHealthCoordinator({ true }, { false }, { epoch }, {
            epoch++; SnapshotResult.Unavailable
        }, { error("old generation must not repair") }, { testScheduler.currentTime })
        health.check()
    }
}
