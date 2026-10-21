package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundEffectsTest {
    @Test fun documentEventIsConsumedOnceAndNotReplayed() = runTest {
        val effects = ForegroundEffects()
        val write = UiEvent.WriteDocument(1L, "content://export", "{}")
        assertTrue(effects.emit(write))
        assertEquals(write, effects.events.first())
        val nextConsumer = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { effects.events.first() }
        assertFalse(nextConsumer.isCompleted)
        val message = UiEvent.ShowMessage(UiMessage("完成"))
        assertTrue(effects.emit(message))
        assertEquals(message, nextConsumer.await())
    }

    @Test fun closedOrFullChannelReportsFailedEnqueue() {
        val effects = ForegroundEffects(capacity = 1)
        val write = UiEvent.WriteDocument(2L, "content://export", "{}")
        assertTrue(effects.emit(write))
        assertFalse(effects.emit(write.copy(writeId = 3L)))
        effects.close()
        assertFalse(effects.emit(write.copy(writeId = 4L)))
    }
}
