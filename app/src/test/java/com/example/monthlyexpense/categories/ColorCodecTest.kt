package com.example.monthlyexpense.categories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorCodecTest {
    @Test
    fun parsesSixAndEightDigitArgbAndFormatsCanonicalHex() {
        assertEquals(0xFF112233L, ColorCodec.parse("#112233"))
        assertEquals(0x80112233L, ColorCodec.parse("#80112233"))
        assertEquals(0xFFAABBCCL, ColorCodec.parse("#aabbcc"))
        assertEquals("#FFAABBCC", ColorCodec.format(0xFFAABBCCL))
    }

    @Test
    fun rejectsMalformedColors() {
        listOf("112233", "#123", "#1234567", "#GG1122", "", "#100000000")
            .forEach { assertNull(ColorCodec.parse(it)) }
    }
}
