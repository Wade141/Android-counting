package com.example.monthlyexpense.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class NotificationSnapshot(val epoch: Long, val readAtElapsedMs: Long, val notifications: List<RawNotification>)
data class SnapshotConnection(val epoch: Long, val read: () -> List<RawNotification>?)
enum class ReadErrorKind { SECURITY, SYSTEM }
sealed interface SnapshotResult {
    data class Success(val snapshot: NotificationSnapshot) : SnapshotResult
    data object PermissionMissing : SnapshotResult
    data object NotConnected : SnapshotResult
    data object Unavailable : SnapshotResult
    data object TimedOut : SnapshotResult
    data object Busy : SnapshotResult
    data object Stale : SnapshotResult
    data class ReadError(val kind: ReadErrorKind) : SnapshotResult
}

/** A timeout cancels the waiter, never releases the slot owned by a synchronous Binder call. */
class NotificationSnapshotReader(
    private val capture: suspend () -> SnapshotConnection?,
    private val currentEpoch: suspend () -> Long,
    private val hasAccess: () -> Boolean,
    private val executor: Executor,
    private val elapsed: () -> Long,
    private val log: (String) -> Unit = {}
) {
    private val inFlight = AtomicBoolean(false)

    suspend fun read(): SnapshotResult {
        if (!hasAccess()) return SnapshotResult.PermissionMissing
        val connection = capture() ?: return SnapshotResult.NotConnected
        if (!inFlight.compareAndSet(false, true)) return SnapshotResult.Busy
        val result = CompletableDeferred<SnapshotResult>()
        try {
            executor.execute {
                try {
                    val notifications = connection.read()
                    result.complete(if (notifications == null) SnapshotResult.Unavailable else
                        SnapshotResult.Success(NotificationSnapshot(connection.epoch, elapsed(), notifications)))
                } catch (_: SecurityException) {
                    result.complete(SnapshotResult.ReadError(ReadErrorKind.SECURITY))
                } catch (_: RuntimeException) {
                    result.complete(SnapshotResult.ReadError(ReadErrorKind.SYSTEM))
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (_: RuntimeException) {
            inFlight.set(false)
            return SnapshotResult.ReadError(ReadErrorKind.SYSTEM)
        }
        val received = withTimeoutOrNull(5_000L) { result.await() } ?: SnapshotResult.TimedOut
        if (!hasAccess()) return SnapshotResult.PermissionMissing
        if (currentEpoch() != connection.epoch) return SnapshotResult.Stale
        log("snapshot_read_finished epoch=${connection.epoch} result=${received.javaClass.simpleName}")
        return received
    }
}
