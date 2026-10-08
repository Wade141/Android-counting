package com.example.monthlyexpense.ui

import com.example.monthlyexpense.ui.forms.clockAngleDelta
import com.example.monthlyexpense.ui.forms.wrapClockMinutes
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTimeMathTest {
    @Test fun clockwiseAndCounterclockwiseCrossTwelveWithoutJumping() {
        assertEquals(12f, clockAngleDelta(354f, 6f), 0.001f)
        assertEquals(-12f, clockAngleDelta(6f, 354f), 0.001f)
    }
    @Test fun midnightWrapsBothWays() {
        assertEquals(0, wrapClockMinutes(1440))
        assertEquals(1439, wrapClockMinutes(-1))
        assertEquals(65, wrapClockMinutes(1505))
    }
}
