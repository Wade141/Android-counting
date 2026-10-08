package com.example.monthlyexpense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseUiStateTest {
    @Test
    fun defaultsAreSafeAndIdle() {
        val state = ExpenseUiState()

        assertTrue(state.expenses.isEmpty())
        assertFalse(state.autoBookkeeping.enabled)
        assertTrue(state.autoBookkeeping.weChatEnabled)
        assertTrue(state.autoBookkeeping.alipayEnabled)
        assertFalse(state.pending.anyWrite)
        assertNull(state.overlay)
        assertEquals(0L, state.displayedDataEpoch)
    }
}
