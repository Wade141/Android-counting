package com.example.monthlyexpense.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class ReplayBudgetState(val reads: Int = 0, val recoveryUsed: Boolean = false, val readsAfterRecovery: Int = 0)

/** Reserve before acting; persistence failure stops the session rather than resetting its budget. */
class ReplayBudget(initial: ReplayBudgetState = ReplayBudgetState(), private val save: (ReplayBudgetState) -> Boolean = { true }) {
    var state = initial
        private set
    fun reserveRead(): Boolean {
        if (state.reads >= 4 || (!state.recoveryUsed && state.reads >= 3) || state.readsAfterRecovery >= 1) return false
        return update(state.copy(reads = state.reads + 1,
            readsAfterRecovery = state.readsAfterRecovery + if (state.recoveryUsed) 1 else 0))
    }
    fun reserveRecovery(): Boolean = !state.recoveryUsed && update(state.copy(recoveryUsed = true))
    private fun update(next: ReplayBudgetState): Boolean {
        if (!save(next)) return false
        state = next
        return true
    }
}

enum class ReplayFailure { DISABLED, PERMISSION, READ, BUSY, STALE, RECOVERY, STORAGE, BOOK_CHANGED, TIMEOUT }
sealed interface ReplayOutcome {
    data class Completed(val summary: DecisionReplaySummary, val restored: Boolean) : ReplayOutcome
    data class Failed(val reason: ReplayFailure, val restored: Boolean = false) : ReplayOutcome
    data object Yielded : ReplayOutcome
}

class NotificationReplayCoordinator(
    private val enabled: () -> Boolean,
    private val shouldYield: suspend () -> Boolean,
    private val epoch: suspend () -> Long,
    private val read: suspend () -> SnapshotResult,
    private val recover: suspend (Long, Boolean) -> Boolean,
    private val process: suspend (NotificationSnapshot) -> DecisionReplaySummary?,
    private val log: (String) -> Unit = {}
) {
    private data class Acquisition(val snapshot: NotificationSnapshot? = null, val failure: ReplayOutcome? = null, val restored: Boolean = false)

    suspend fun run(budget: ReplayBudget, repairFirst: Boolean = false, remainingMillis: Long = 150_000L): ReplayOutcome {
        val acquired = withTimeoutOrNull(remainingMillis) { acquire(budget, repairFirst) }
            ?: return ReplayOutcome.Failed(ReplayFailure.TIMEOUT)
        acquired.failure?.let { return it }
        if (!enabled()) return ReplayOutcome.Failed(ReplayFailure.DISABLED, acquired.restored)
        if (shouldYield()) return ReplayOutcome.Yielded
        val snapshot = requireNotNull(acquired.snapshot)
        if (epoch() != snapshot.epoch) return ReplayOutcome.Failed(ReplayFailure.STALE, acquired.restored)
        return try {
            val summary = process(snapshot) ?: return ReplayOutcome.Failed(ReplayFailure.BOOK_CHANGED, acquired.restored)
            ReplayOutcome.Completed(summary, acquired.restored)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ReplayYielded) { ReplayOutcome.Yielded }
        catch (_: RuntimeException) { ReplayOutcome.Failed(ReplayFailure.STORAGE, acquired.restored) }
    }

    private suspend fun acquire(budget: ReplayBudget, repairFirst: Boolean): Acquisition {
        var restored = false
        fun failed(reason: ReplayFailure) = Acquisition(failure = ReplayOutcome.Failed(reason, restored))
        if (!enabled()) return failed(ReplayFailure.DISABLED)
        if (shouldYield()) return Acquisition(failure = ReplayOutcome.Yielded)
        if (repairFirst) {
            if (!budget.reserveRecovery() || !recover(epoch(), true)) return failed(ReplayFailure.RECOVERY)
            restored = true
        }
        while (enabled()) {
            if (shouldYield()) return Acquisition(failure = ReplayOutcome.Yielded)
            val expected = epoch()
            if (!budget.reserveRead()) return failed(ReplayFailure.READ)
            when (val result = read()) {
                is SnapshotResult.Success -> return Acquisition(snapshot = result.snapshot, restored = restored)
                SnapshotResult.PermissionMissing -> return failed(ReplayFailure.PERMISSION)
                SnapshotResult.Busy -> return failed(ReplayFailure.BUSY)
                SnapshotResult.TimedOut -> return failed(ReplayFailure.TIMEOUT)
                SnapshotResult.Stale -> {
                    if (budget.state.reads >= 3 || budget.state.recoveryUsed) return failed(ReplayFailure.STALE)
                    continue
                }
                else -> {
                    if (epoch() != expected) return failed(ReplayFailure.STALE)
                    if (budget.state.recoveryUsed) return failed(ReplayFailure.READ)
                    if (result == SnapshotResult.NotConnected || budget.state.reads >= 3) {
                        if (shouldYield()) return Acquisition(failure = ReplayOutcome.Yielded)
                        if (!budget.reserveRecovery() || !recover(expected, false)) return failed(ReplayFailure.RECOVERY)
                        restored = true
                    } else {
                        log("auto_replay_retry attempt=${budget.state.reads}")
                        // Small cancellable slices let a newly queued manual request take priority.
                        repeat(if (budget.state.reads == 1) 8 else 20) {
                            delay(250L)
                            if (!enabled()) return failed(ReplayFailure.DISABLED)
                            if (shouldYield()) return Acquisition(failure = ReplayOutcome.Yielded)
                        }
                    }
                }
            }
        }
        return failed(ReplayFailure.DISABLED)
    }
}
