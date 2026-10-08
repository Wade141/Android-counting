package com.example.monthlyexpense.ocrtest

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ScreenshotImageHashTest {
    @Test fun copyReturnsKnownSha256AndPreservesBytesAcrossShortReads() {
        val input = object : InputStream() {
            private val bytes = "abc".toByteArray()
            private var index = 0
            override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val value = read()
                if (value < 0) return -1
                buffer[offset] = value.toByte()
                return 1
            }
        }
        val output = ByteArrayOutputStream()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            copyScreenshotAndHash(input, output))
        assertArrayEquals("abc".toByteArray(), output.toByteArray())
    }

    @Test fun refusesMoreThanTwentyMebibytes() {
        val oversized = ByteArray(20 * 1024 * 1024 + 1).inputStream()
        assertThrows(IllegalArgumentException::class.java) {
            copyScreenshotAndHash(oversized, java.io.OutputStream.nullOutputStream())
        }
    }

    @Test fun legacyResultConstructionKeepsHashOptional() {
        assertNull(OcrTestResult("", 0, 1, 1).imageHash)
    }
}
