package com.example.monthlyexpense.ui

import com.example.monthlyexpense.ui.settings.CardAppearance
import com.example.monthlyexpense.ui.settings.parseBorderColor
import org.junit.Assert.*
import org.junit.Test

class CardAppearanceTest {
    @Test fun transparencyOnlyChangesFillAlpha() {
        assertEquals(1f, CardAppearance(transparency = 0).fillAlpha)
        assertEquals(.5f, CardAppearance(transparency = 50).fillAlpha)
        assertEquals(0f, CardAppearance(transparency = 100).fillAlpha)
    }
    @Test fun borderAcceptsOpaqueAndTransparentCodes() {
        assertEquals(0xFF123456L, parseBorderColor("#123456"))
        assertEquals(0L, parseBorderColor("#00000000"))
        assertEquals(0x80123456L, parseBorderColor("#80123456"))
        assertNull(parseBorderColor("#oops"))
    }
}
