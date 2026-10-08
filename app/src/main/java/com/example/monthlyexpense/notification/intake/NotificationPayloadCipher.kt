package com.example.monthlyexpense.notification.intake

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.monthlyexpense.notification.RawNotification
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedPayload(val ciphertext: ByteArray, val nonce: ByteArray)

interface PayloadCipher {
    fun encrypt(plaintext: ByteArray, identity: String): EncryptedPayload
    fun decrypt(payload: EncryptedPayload, identity: String): ByteArray
}

/** No key lookup at construction; inaccessible keys fail closed, without a plaintext fallback. */
class NotificationPayloadCipher(private val keyProvider: () -> SecretKey) : PayloadCipher {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this({ androidKey() })
    override fun encrypt(plaintext: ByteArray, identity: String): EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(cipher.doFinal(plaintext), cipher.iv)
    }
    override fun decrypt(payload: EncryptedPayload, identity: String): ByteArray {
        require(payload.nonce.size == 12) { "Invalid payload nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, payload.nonce))
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(payload.ciphertext)
    }
    companion object {
        private const val ALIAS = "notification-intake-v1"
        @Synchronized private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}

object NotificationPayloadCodec {
    const val VERSION = 1
    private const val MAX_BYTES = 65_536
    fun encode(raw: RawNotification): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            fun write(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                require(encoded.size <= MAX_BYTES) { "Payload too large" }
                out.writeInt(encoded.size); out.write(encoded)
                require(bytes.size() <= MAX_BYTES) { "Payload too large" }
            }
            fun optional(value: String?) { out.writeBoolean(value != null); if (value != null) write(value) }
            out.writeInt(VERSION)
            write(raw.packageName); write(raw.title); write(raw.text); write(raw.bigText)
            require(raw.textLines.size <= MAX_BYTES / 4)
            out.writeInt(raw.textLines.size); raw.textLines.forEach(::write)
            out.writeLong(raw.postTime); write(raw.notificationKey); optional(raw.channelId)
            write(raw.subText); out.writeBoolean(raw.isGroupSummary); optional(raw.groupKey)
        }
        bytes.toByteArray().also { require(it.size <= MAX_BYTES) { "Payload too large" } }
    }
    fun decode(bytes: ByteArray): RawNotification {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun read(): String {
                val size = input.readInt(); require(size in 0..input.available())
                return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
            }
            fun optional(): String? = if (input.readBoolean()) read() else null
            require(input.readInt() == VERSION) { "Unsupported notification payload version" }
            val pkg = read(); val title = read(); val text = read(); val big = read()
            val count = input.readInt(); require(count in 0..(input.available() / 4))
            val lines = List(count) { read() }
            val raw = RawNotification(pkg, title, text, big, lines, input.readLong(), read(), optional(), read(), input.readBoolean(), optional())
            require(input.available() == 0)
            raw
        }
    }
}
