package com.example.monthlyexpense.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException

class StrictUtf8Test {
    @Test
    fun decodesValidUtf8WithoutReplacement() {
        assertEquals("账单", StrictUtf8.decode("账单".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingBytes() {
        assertThrows(CharacterCodingException::class.java) {
            StrictUtf8.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }
}
