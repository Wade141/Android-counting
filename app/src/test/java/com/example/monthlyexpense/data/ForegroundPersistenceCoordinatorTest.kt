package com.example.monthlyexpense.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundPersistenceCoordinatorTest {
    @Test fun pendingRestoreSettingsPermitCurrentReadsWhileAllWritesRemainBlocked() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val oldWrite = requireNotNull(coordinator.mutationTicket())
        coordinator.setDurableRestorePending(true)
        val read = requireNotNull(coordinator.readTicket())
        assertEquals(CoordinatedMutation.Executed("restored ledger"), coordinator.runRead(read) { "restored ledger" })
        assertEquals(CoordinatedMutation.Executed(coordinator.currentEpoch), coordinator.commitReadIfCurrent(read) { it })
        assertTrue(coordinator.isReadCurrent(read))
        assertNull(coordinator.mutationTicket())
        assertNull(coordinator.mutationTicket(coordinator.currentEpoch))
        assertEquals(CoordinatedMutation.Rejected, coordinator.runMutation(oldWrite) { error("write must stay blocked") })
        assertNull(coordinator.beginRestore())
    }

    @Test fun readProjectionCannotCrossRestoreOrPendingSettingsEpochChanges() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val beforePending = requireNotNull(coordinator.readTicket())
        coordinator.setDurableRestorePending(true)
        assertFalse(coordinator.isReadCurrent(beforePending))
        assertEquals(CoordinatedMutation.Rejected, coordinator.commitReadIfCurrent(beforePending) { error("stale projection") })
        val pendingRead = requireNotNull(coordinator.readTicket())
        coordinator.setDurableRestorePending(false)
        assertEquals(CoordinatedMutation.Rejected, coordinator.runRead(pendingRead) { error("stale read") })
        val beforeRestore = requireNotNull(coordinator.readTicket())
        val restore = requireNotNull(coordinator.beginRestore())
        assertNull(coordinator.readTicket())
        assertEquals(CoordinatedMutation.Rejected, coordinator.commitReadIfCurrent(beforeRestore) { error("old book projection") })
        coordinator.runRestore(restore, { RestoreCommit.Restored(BASELINE_AFTER_RESTORE) }, {})
        assertFalse(coordinator.isReadCurrent(beforeRestore))
        assertTrue(coordinator.readTicket() != null)
    }
    @Test
    fun queuedOldEpochMutationIsRejectedAndPostRestoreMutationExecutes() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val oldTicket = requireNotNull(coordinator.mutationTicket())
        val restoreTicket = requireNotNull(coordinator.beginRestore())
        assertNull(coordinator.mutationTicket())
        val restoreEntered = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        var mutationCount = 0

        val restore = async {
            coordinator.runRestore(
                ticket = restoreTicket,
                restore = {
                    restoreEntered.complete(Unit)
                    releaseRestore.await()
                    RestoreCommit.Restored(BASELINE_AFTER_RESTORE)
                },
                refreshAfterRestore = {}
            )
        }
        restoreEntered.await()
        val staleMutation = async {
            coordinator.runMutation(oldTicket) {
                mutationCount++
            }
        }
        runCurrent()
        assertFalse(staleMutation.isCompleted)

        releaseRestore.complete(Unit)

        assertEquals(RestoreOutcome.RESTORED, restore.await())
        assertEquals(CoordinatedMutation.Rejected, staleMutation.await())
        assertEquals(0, mutationCount)
        val newTicket = requireNotNull(coordinator.mutationTicket())
        assertEquals(
            CoordinatedMutation.Executed(Unit),
            coordinator.runMutation(newTicket) {
                mutationCount++
                Unit
            }
        )
        assertEquals(1, mutationCount)
    }

    @Test
    fun staleUiEpochCannotObtainTicketAfterRestoreCompletes() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val staleUiEpoch = coordinator.currentEpoch
        val restoreTicket = requireNotNull(coordinator.beginRestore())
        coordinator.runRestore(
            ticket = restoreTicket,
            restore = { RestoreCommit.Restored(BASELINE_AFTER_RESTORE) },
            refreshAfterRestore = {}
        )

        assertNull(coordinator.mutationTicket(staleUiEpoch))
        assertTrue(coordinator.mutationTicket(coordinator.currentEpoch) != null)
    }

    @Test
    fun successfulRestoreUpdatesRollbackBaselineBeforeFailingSnapshot() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val restoreTicket = requireNotNull(coordinator.beginRestore())

        val outcome = coordinator.runRestore(
            ticket = restoreTicket,
            restore = { RestoreCommit.Restored(BASELINE_AFTER_RESTORE) },
            refreshAfterRestore = { throw SnapshotFailure() }
        )

        assertEquals(RestoreOutcome.RESTORED_REFRESH_FAILED, outcome)
        assertEquals(BASELINE_AFTER_RESTORE, coordinator.persistedSettings)
        assertFalse(coordinator.isRestoring)
    }

    @Test
    fun admittedMutationCompletesBeforeFailedRestoreRefreshesCurrentState() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val mutationTicket = requireNotNull(coordinator.mutationTicket())
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        var committed = false
        var refreshed = false
        val mutation = async {
            coordinator.runMutation(mutationTicket) {
                mutationEntered.complete(Unit)
                releaseMutation.await()
                committed = true
            }
        }
        mutationEntered.await()
        val restoreTicket = requireNotNull(coordinator.beginRestore())
        val restore = async {
            coordinator.runRestore(
                ticket = restoreTicket,
                restore = { RestoreCommit.NotRestored },
                refreshAfterRestore = {
                    assertTrue(committed)
                    refreshed = true
                }
            )
        }

        releaseMutation.complete(Unit)

        assertTrue(mutation.await() is CoordinatedMutation.Executed)
        assertEquals(RestoreOutcome.NOT_RESTORED, restore.await())
        assertTrue(refreshed)
        assertEquals(BASELINE_BEFORE_RESTORE, coordinator.persistedSettings)
        assertFalse(coordinator.isRestoring)
        assertTrue(coordinator.mutationTicket() != null)
    }

    @Test
    fun failedRestoreAndFailedSnapshotStillClearsRestoring() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val restoreTicket = requireNotNull(coordinator.beginRestore())

        val outcome = coordinator.runRestore(
            ticket = restoreTicket,
            restore = { RestoreCommit.NotRestored },
            refreshAfterRestore = { throw SnapshotFailure() }
        )

        assertEquals(RestoreOutcome.NOT_RESTORED_REFRESH_FAILED, outcome)
        assertEquals(BASELINE_BEFORE_RESTORE, coordinator.persistedSettings)
        assertFalse(coordinator.isRestoring)
    }

    @Test
    fun failingReadDoesNotReportOldEpochErrorAfterRestoreStarts() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val readTicket = requireNotNull(coordinator.mutationTicket())
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var failureReports = 0
        val read = async {
            coordinator.runReadWithCurrentFailure(
                ticket = readTicket,
                block = {
                    readEntered.complete(Unit)
                    releaseRead.await()
                    throw SnapshotFailure()
                },
                onCurrentFailure = { failureReports++ }
            )
        }
        readEntered.await()
        val restoreTicket = requireNotNull(coordinator.beginRestore())
        val restore = async {
            coordinator.runRestore(
                ticket = restoreTicket,
                restore = { RestoreCommit.NotRestored },
                refreshAfterRestore = {}
            )
        }

        releaseRead.complete(Unit)

        read.await()
        assertEquals(0, failureReports)
        assertEquals(RestoreOutcome.NOT_RESTORED, restore.await())
        assertFalse(coordinator.isRestoring)
    }

    @Test
    fun cancellationClearsRestoringAndPropagates() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val restoreTicket = requireNotNull(coordinator.beginRestore())
        val entered = CompletableDeferred<Unit>()
        val restore = async {
            coordinator.runRestore(
                ticket = restoreTicket,
                restore = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
                refreshAfterRestore = {}
            )
        }
        entered.await()

        restore.cancel()
        runCurrent()

        assertTrue(restore.isCancelled)
        assertFalse(coordinator.isRestoring)
        assertTrue(coordinator.mutationTicket() != null)
    }

    @Test
    fun restoreExceptionClearsRestoringAndPropagates() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(BASELINE_BEFORE_RESTORE)
        val restoreTicket = requireNotNull(coordinator.beginRestore())

        try {
            coordinator.runRestore(
                ticket = restoreTicket,
                restore = { throw RestoreFailure() },
                refreshAfterRestore = {}
            )
            fail("restore exception must propagate")
        } catch (_: RestoreFailure) {
            // Expected: unexpected persistence failures are not swallowed.
        }

        assertFalse(coordinator.isRestoring)
        assertTrue(coordinator.mutationTicket() != null)
    }

    private class SnapshotFailure : RuntimeException()
    private class RestoreFailure : RuntimeException()

    private companion object {
        val BASELINE_BEFORE_RESTORE = ForegroundSettingsBaseline(
            autoBookkeepingEnabled = false,
            weChatEnabled = true,
            alipayEnabled = false
        )
        val BASELINE_AFTER_RESTORE = ForegroundSettingsBaseline(
            autoBookkeepingEnabled = true,
            weChatEnabled = false,
            alipayEnabled = true
        )
    }
}
