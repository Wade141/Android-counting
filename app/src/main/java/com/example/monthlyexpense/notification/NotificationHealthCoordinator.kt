package com.example.monthlyexpense.notification

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

class NotificationHealthCoordinator(
    private val enabled: () -> Boolean,
    private val recovering: () -> Boolean,
    private val epoch: suspend () -> Long,
    private val read: suspend () -> SnapshotResult,
    private val repair: suspend (Long) -> Boolean,
    private val elapsed: () -> Long,
    private val log: (String) -> Unit = {}
) {
    private val checkLock = Mutex()
    @Volatile private var lastCompleted: Long? = null

    fun onSnapshotChecked() { lastCompleted = elapsed() }

    suspend fun check(background: Boolean = false) {
        if (!checkLock.tryLock()) return
        var checked = false
        try {
            if (!enabled() || recovering()) return
            val last = lastCompleted
            if (!background && last != null && elapsed() - last < 60_000L) return
            checked = true
            var staleRetries = 0
            var failures = 0
            var expected = epoch()
            log("health_check_started epoch=$expected")
            while (enabled() && !recovering()) {
                val result = read()
                val current = epoch()
                if (current != expected || result == SnapshotResult.Stale) {
                    if (staleRetries++ >= 1) return
                    expected = current
                    failures = 0
                    continue
                }
                when (result) {
                    is SnapshotResult.Success, SnapshotResult.PermissionMissing, SnapshotResult.Busy -> return
                    SnapshotResult.NotConnected, SnapshotResult.TimedOut -> { repair(expected); return }
                    else -> {
                        if (++failures >= 2) { repair(expected); return }
                        delay(2_000L)
                    }
                }
            }
        } finally {
            if (checked) onSnapshotChecked()
            checkLock.unlock()
        }
    }
}
