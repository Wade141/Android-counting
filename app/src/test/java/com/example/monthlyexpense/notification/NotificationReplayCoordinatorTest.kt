package com.example.monthlyexpense.notification

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationReplayCoordinatorTest {
    @Test fun manualPriorityDuringProcessingIsYieldedRatherThanStorageFailure() = runTest {
        val runner = NotificationReplayCoordinator({ true }, { false }, { 1 },
            { SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList())) },
            { _, _ -> error("must not repair") }, { throw ReplayYielded() })
        assertEquals(ReplayOutcome.Yielded, runner.run(ReplayBudget()))
    }
    @Test fun persistenceFailurePreventsUncountedAction() = runTest {
        val runner = NotificationReplayCoordinator({ true }, { false }, { 1 }, { error("must not read") },
            { _, _ -> error("must not repair") }, { error("must not process") })
        assertTrue(runner.run(ReplayBudget(save = { false })) is ReplayOutcome.Failed)
    }

    @Test fun manualRequestDuringDelayYieldsWithoutAnotherRead() = runTest {
        var manual = false
        var reads = 0
        val replay = NotificationReplayCoordinator({ true }, { manual }, { 1 }, {
            reads++; manual = true; SnapshotResult.Unavailable
        }, { _, _ -> error("must not repair") }, { error("must not process") })
        assertEquals(ReplayOutcome.Yielded, replay.run(ReplayBudget()))
        assertEquals(1, reads)
    }

    @Test fun manualRepairNeedsConnectionAndNoFalseSuccessWhenBookChanges() = runTest {
        var recovered = false
        val replay = NotificationReplayCoordinator({ true }, { false }, { 1 }, {
            assertTrue(recovered)
            SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList()))
        }, { _, manual -> assertTrue(manual); recovered = true; true }, { null })
        val result = replay.run(ReplayBudget(), repairFirst = true) as ReplayOutcome.Failed
        assertEquals(ReplayFailure.BOOK_CHANGED, result.reason)
        assertTrue(result.restored)
    }

    @Test fun retriesThreeReadsThenRecoversOnceAndStopsAfterFinalRead() = runTest {
        var reads = 0
        var repairs = 0
        val replay = NotificationReplayCoordinator({ true }, { false }, { 1 },
            { reads++; SnapshotResult.Unavailable }, { _, _ -> repairs++; true },
            { error("unavailable data must not be processed") })
        val budget = ReplayBudget()
        assertTrue(replay.run(budget) is ReplayOutcome.Failed)
        assertEquals(4, reads)
        assertEquals(1, repairs)
        replay.run(budget)
        assertEquals(4, reads)
        assertEquals(1, repairs)
    }
    @Test fun transientFailureThenEmptySuccessDoesNotRepair() = runTest {
        var reads = 0
        val replay = NotificationReplayCoordinator({ true }, { false }, { 1 }, {
            if (++reads == 1) SnapshotResult.Unavailable else SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList()))
        }, { _, _ -> error("must not repair") }, { DecisionReplaySummary() })
        assertTrue(replay.run(ReplayBudget()) is ReplayOutcome.Completed)
        assertEquals(2_000L, testScheduler.currentTime)
    }
    @Test fun storageFailureAndPartialResultNeverResetListener() = runTest {
        val replay = NotificationReplayCoordinator({ true }, { false }, { 1 },
            { SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList())) },
            { _, _ -> error("storage is not a connection failure") }, { DecisionReplaySummary(failed = 1) })
        val result = replay.run(ReplayBudget()) as ReplayOutcome.Completed
        assertEquals(1, result.summary.failed)
    }
    @Test fun manualPriorityYieldsBeforeReadingAndOldSnapshotIsNotWritten() = runTest {
        var yield = true
        val replay = NotificationReplayCoordinator({ true }, { yield }, { 2 },
            { SnapshotResult.Success(NotificationSnapshot(1, 0, emptyList())) },
            { _, _ -> error("must not repair") }, { error("old epoch must not write") })
        assertEquals(ReplayOutcome.Yielded, replay.run(ReplayBudget()))
        yield = false
        assertTrue(replay.run(ReplayBudget()) is ReplayOutcome.Failed)
    }
    @Test fun busyOrTimedOutReaderDoesNotAccumulateReads() = runTest {
        for (result in listOf(SnapshotResult.Busy, SnapshotResult.TimedOut)) {
            var reads = 0
            val replay = NotificationReplayCoordinator({ true }, { false }, { 1 },
                { reads++; result }, { _, _ -> error("do not pile up Binder calls") }, { error("must not write") })
            assertTrue(replay.run(ReplayBudget()) is ReplayOutcome.Failed)
            assertEquals(1, reads)
        }
    }
}
