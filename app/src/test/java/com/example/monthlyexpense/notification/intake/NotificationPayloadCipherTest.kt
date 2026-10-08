package com.example.monthlyexpense.notification.intake

import com.example.monthlyexpense.notification.RawNotification
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class NotificationPayloadCipherTest {
    private val raw = RawNotification("package", "title", "payment secret", "large", listOf("line"), 123, "private-key", "channel", "sub", true, "group")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripKeepsEveryNecessaryField() {
        val cipher = NotificationPayloadCipher { key }
        val payload = cipher.encrypt(NotificationPayloadCodec.encode(raw), "identity")
        assertEquals(raw, NotificationPayloadCodec.decode(cipher.decrypt(payload, "identity")))
        assertFalse(String(payload.ciphertext).contains("payment secret"))
    }
    @Test fun authenticatedCipherRejectsTamperingAndIdentitySwaps() {
        val cipher = NotificationPayloadCipher { key }
        val payload = cipher.encrypt(NotificationPayloadCodec.encode(raw), "identity")
        assertTrue(runCatching { cipher.decrypt(payload, "other") }.isFailure)
        payload.ciphertext[0] = (payload.ciphertext[0].toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(payload, "identity") }.isFailure)
    }
    @Test fun unknownSerializationVersionIsRejected() {
        val encoded = NotificationPayloadCodec.encode(raw)
        encoded[3] = 99
        assertTrue(runCatching { NotificationPayloadCodec.decode(encoded) }.isFailure)
    }
    @Test fun keyFailureNeverFallsBackToPlaintext() {
        val cipher = NotificationPayloadCipher { error("key unavailable") }
        assertTrue(runCatching { cipher.encrypt(NotificationPayloadCodec.encode(raw), "identity") }.isFailure)
    }
}
