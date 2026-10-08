package com.example.monthlyexpense.notification

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSnapshotReaderTest {
    @Test fun cancellationDoesNotFreeUnderlyingSlotAndPermissionLossWinsOverSuccess() = runTest {
        val pending = ArrayDeque<Runnable>()
        var granted = true
        val reader = NotificationSnapshotReader({ SnapshotConnection(1) { emptyList() } }, { 1 }, { granted },
            Executor { pending.add(it) }, { testScheduler.currentTime })
        val request = launch { reader.read() }
        runCurrent()
        request.cancelAndJoin()
        assertEquals(SnapshotResult.Busy, reader.read())
        pending.removeFirst().run()
        val next = async { reader.read() }
        runCurrent()
        granted = false
        pending.removeFirst().run()
        assertEquals(SnapshotResult.PermissionMissing, next.await())
    }

    @Test fun emptyAndUnavailableAreDifferent() = runTest {
        var value: List<RawNotification>? = emptyList()
        val reader = NotificationSnapshotReader({ SnapshotConnection(1) { value } }, { 1 }, { true }, Executor { it.run() }, { testScheduler.currentTime })
        assertTrue(reader.read() is SnapshotResult.Success)
        value = null
        assertEquals(SnapshotResult.Unavailable, reader.read())
    }

    @Test fun timedOutCallKeepsTheSlotUntilUnderlyingCallActuallyCompletes() = runTest {
        val pending = ArrayDeque<Runnable>()
        val reader = NotificationSnapshotReader({ SnapshotConnection(1) { emptyList() } }, { 1 }, { true }, Executor { pending.add(it) }, { testScheduler.currentTime })
        assertEquals(SnapshotResult.TimedOut, reader.read())
        assertEquals(5_000, testScheduler.currentTime)
        repeat(10) { assertEquals(SnapshotResult.Busy, reader.read()) }
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        val next = async { reader.read() }
        runCurrent()
        pending.removeFirst().run()
        assertTrue(next.await() is SnapshotResult.Success)
    }

    @Test fun resultsFromOldConnectionAreDiscarded() = runTest {
        var epoch = 1L
        val reader = NotificationSnapshotReader({ SnapshotConnection(epoch) { epoch++; emptyList() } }, { epoch }, { true }, Executor { it.run() }, { testScheduler.currentTime })
        assertEquals(SnapshotResult.Stale, reader.read())
    }

    @Test fun securityExceptionDoesNotInventPermissionRevocation() = runTest {
        var granted = true
        var revoke = false
        val reader = NotificationSnapshotReader({ SnapshotConnection(1) {
            if (revoke) granted = false
            throw SecurityException("private text")
        } }, { 1 }, { granted }, Executor { it.run() }, { testScheduler.currentTime })
        assertEquals(SnapshotResult.ReadError(ReadErrorKind.SECURITY), reader.read())
        revoke = true
        assertEquals(SnapshotResult.PermissionMissing, reader.read())
    }
}
