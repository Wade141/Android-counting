package com.example.monthlyexpense.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticReplaySessionTest {
    @Test fun lateAutomaticYieldCannotReopenSessionAlreadyHandledManually() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("session_manual_race_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AutomaticReplaySession(prefs) { 1_000_000L }
        val automatic = store.open("a")!!
        assertTrue(automatic.reserveRead())
        store.completeForManual()
        store.finish("a", false, yielded = true)
        assertNull(store.open("a"))
        assertFalse(automatic.reserveRead())
        assertEquals(0, store.open("b")!!.state.reads)
    }
    @Test fun yieldedSessionTransferredToNewWorkKeepsRemainingBudget() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("session_yield_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AutomaticReplaySession(prefs) { 1_000_000L }
        val original = store.open("a")!!
        repeat(3) { assertTrue(original.reserveRead()) }
        assertTrue(original.reserveRecovery())
        store.finish("a", false, yielded = true)
        val fallback = store.open("b")!!
        assertEquals(3, fallback.state.reads)
        assertTrue(fallback.state.recoveryUsed)
        assertTrue(fallback.reserveRead())
        assertFalse(fallback.reserveRead())
    }

    @Test fun restartingAfterAcquisitionDeadlineCannotReadAgain() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("session_deadline_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var now = 1_000_000L
        val store = AutomaticReplaySession(prefs) { now }
        assertTrue(store.open("a")!!.reserveRead())
        now += 150_001
        assertNull(store.open("a"))
    }
    @Test fun restartedWorkerKeepsBudgetAndFailureCooldown() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("session_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var now = 1_000_000L
        fun store() = AutomaticReplaySession(prefs) { now }
        val first = store().open("a")!!
        repeat(3) { assertTrue(first.reserveRead()) }
        assertTrue(first.reserveRecovery())
        val restored = store().open("a")!!
        assertEquals(3, restored.state.reads)
        assertFalse(restored.reserveRecovery())
        assertTrue(restored.reserveRead())
        assertFalse(restored.reserveRead())
        store().finish("a", successful = false)
        assertNull(store().open("a"))
        assertNull(store().open("b"))
        now += 300_000
        assertNotNull(store().open("b"))
    }
    @Test fun oldSessionExpiresInsteadOfStartingItsBudgetOver() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("session_expiry_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var now = 1_000_000L
        val store = AutomaticReplaySession(prefs) { now }
        assertNotNull(store.open("a"))
        now += 600_001
        assertNull(store.open("a"))
        now += 300_000
        assertNotNull(store.open("b"))
    }
}
