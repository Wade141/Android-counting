package com.example.monthlyexpense.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ForegroundSettingsBaseline(
    val autoBookkeepingEnabled: Boolean,
    val weChatEnabled: Boolean,
    val alipayEnabled: Boolean
)

@JvmInline
value class ForegroundMutationTicket internal constructor(internal val epoch: Long)

@JvmInline
value class ForegroundReadTicket internal constructor(internal val epoch: Long)

@JvmInline
value class ForegroundRestoreTicket internal constructor(internal val epoch: Long)

sealed interface CoordinatedMutation<out T> {
    data class Executed<T>(val value: T) : CoordinatedMutation<T>
    data object Rejected : CoordinatedMutation<Nothing>
}

sealed interface RestoreCommit {
    data object NotRestored : RestoreCommit
    data object SettingsPending : RestoreCommit
    data class Restored(val settings: ForegroundSettingsBaseline) : RestoreCommit
}

enum class RestoreOutcome {
    NOT_RESTORED,
    NOT_RESTORED_REFRESH_FAILED,
    RESTORED,
    RESTORED_REFRESH_FAILED,
    SETTINGS_PENDING
}

class ForegroundPersistenceCoordinator(initialSettings: ForegroundSettingsBaseline,
    private val onRestoreFinished: () -> Unit = {}) {
    private val persistenceMutex = Mutex()
    private val stateLock = Any()
    private var epoch = 0L
    private var restoring = false
    private var durableRestorePending = false
    private var settingsBaseline = initialSettings

    val isRestoring: Boolean
        get() = synchronized(stateLock) { restoring }

    val isDurableRestorePending: Boolean
        get() = synchronized(stateLock) { durableRestorePending }

    val currentEpoch: Long
        get() = synchronized(stateLock) { epoch }

    val persistedSettings: ForegroundSettingsBaseline
        get() = synchronized(stateLock) { settingsBaseline }

    fun mutationTicket(): ForegroundMutationTicket? = synchronized(stateLock) {
        if (restoring || durableRestorePending) null else ForegroundMutationTicket(epoch)
    }

    fun mutationTicket(expectedEpoch: Long): ForegroundMutationTicket? = synchronized(stateLock) {
        if (restoring || durableRestorePending || expectedEpoch != epoch) null else ForegroundMutationTicket(epoch)
    }

    fun beginRestore(): ForegroundRestoreTicket? = synchronized(stateLock) {
        if (restoring || durableRestorePending) {
            null
        } else {
            restoring = true
            ForegroundRestoreTicket(++epoch)
        }
    }

    fun isCurrent(ticket: ForegroundMutationTicket): Boolean = synchronized(stateLock) {
        !restoring && !durableRestorePending && ticket.epoch == epoch
    }

    fun <T> commitIfCurrent(
        ticket: ForegroundMutationTicket,
        block: (displayedDataEpoch: Long) -> T
    ): CoordinatedMutation<T> = synchronized(stateLock) {
        if (!restoring && !durableRestorePending && ticket.epoch == epoch) {
            CoordinatedMutation.Executed(block(epoch))
        } else {
            CoordinatedMutation.Rejected
        }
    }

    fun updatePersistedSettings(settings: ForegroundSettingsBaseline) {
        synchronized(stateLock) { settingsBaseline = settings }
    }

    /** A committed ledger remains viewable while its settings wait for forward completion. */
    fun readTicket(): ForegroundReadTicket? = synchronized(stateLock) {
        if (restoring) null else ForegroundReadTicket(epoch)
    }

    fun isReadCurrent(ticket: ForegroundReadTicket): Boolean = synchronized(stateLock) {
        !restoring && ticket.epoch == epoch
    }

    fun <T> commitReadIfCurrent(ticket: ForegroundReadTicket, block: (displayedDataEpoch: Long) -> T): CoordinatedMutation<T> =
        synchronized(stateLock) {
            if (!restoring && ticket.epoch == epoch) CoordinatedMutation.Executed(block(epoch))
            else CoordinatedMutation.Rejected
        }

    fun setDurableRestorePending(pending: Boolean) = synchronized(stateLock) {
        if (durableRestorePending != pending) {
            durableRestorePending = pending
            if (!restoring) epoch++
        }
    }

    suspend fun <T> reconcileDurableRestore(block: suspend () -> T): T = persistenceMutex.withLock { block() }

    suspend fun <T> runMutation(
        ticket: ForegroundMutationTicket,
        block: suspend () -> T
    ): CoordinatedMutation<T> = persistenceMutex.withLock {
        val admitted = synchronized(stateLock) {
            !restoring && !durableRestorePending && ticket.epoch == epoch
        }
        if (admitted) CoordinatedMutation.Executed(block()) else CoordinatedMutation.Rejected
    }

    suspend fun <T> runRead(
        ticket: ForegroundMutationTicket,
        block: suspend () -> T
    ): CoordinatedMutation<T> = runMutation(ticket, block)

    suspend fun <T> runRead(ticket: ForegroundReadTicket, block: suspend () -> T): CoordinatedMutation<T> =
        persistenceMutex.withLock {
            if (isReadCurrent(ticket)) CoordinatedMutation.Executed(block()) else CoordinatedMutation.Rejected
        }

    suspend fun runReadWithCurrentFailure(
        ticket: ForegroundMutationTicket,
        block: suspend () -> Unit,
        onCurrentFailure: (Exception) -> Unit
    ) {
        try {
            runRead(ticket, block)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isCurrent(ticket)) onCurrentFailure(error)
        }
    }

    suspend fun runRestore(
        ticket: ForegroundRestoreTicket,
        restore: suspend () -> RestoreCommit,
        refreshAfterRestore: suspend () -> Unit
    ): RestoreOutcome = persistenceMutex.withLock {
        check(synchronized(stateLock) { restoring && ticket.epoch == epoch }) {
            "Restore ticket is no longer active"
        }
        try {
            when (val commit = restore()) {
                RestoreCommit.SettingsPending -> {
                    synchronized(stateLock) { durableRestorePending = true }
                    try { refreshAfterRestore() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The committed restore remains pending. */ }
                    RestoreOutcome.SETTINGS_PENDING
                }
                RestoreCommit.NotRestored -> try {
                    refreshAfterRestore()
                    RestoreOutcome.NOT_RESTORED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    RestoreOutcome.NOT_RESTORED_REFRESH_FAILED
                }
                is RestoreCommit.Restored -> {
                    updatePersistedSettings(commit.settings)
                    try {
                        refreshAfterRestore()
                        RestoreOutcome.RESTORED
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        RestoreOutcome.RESTORED_REFRESH_FAILED
                    }
                }
            }
        } finally {
            synchronized(stateLock) {
                if (ticket.epoch == epoch) restoring = false
            }
            onRestoreFinished()
        }
    }
}
