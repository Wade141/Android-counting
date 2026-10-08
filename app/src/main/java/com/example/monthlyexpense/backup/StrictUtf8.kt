package com.example.monthlyexpense.backup

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object StrictUtf8 {
    fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}
