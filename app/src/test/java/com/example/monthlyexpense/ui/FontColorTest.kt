package com.example.monthlyexpense.ui

import com.example.monthlyexpense.ui.settings.parseFontColor
import org.junit.Assert.*
import org.junit.Test

class FontColorTest {
    @Test fun acceptsRgbAndRejectsInvalidOrTransparentColors() {
        assertEquals(0xFF123ABCL, parseFontColor("#123abc"))
        assertEquals(0xFFFFFFFFL, parseFontColor("#FFFFFF"))
        assertNull(parseFontColor("#00123456"))
        assertNull(parseFontColor("#12345"))
        assertNull(parseFontColor("#GGGGGG"))
    }
}
