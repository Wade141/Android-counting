package com.example.monthlyexpense.notification.intake

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.parser.PaymentSource

data class IntakeClock(val wallTime: Long, val elapsedTime: Long, val bootCount: Int?) {
    companion object {
        fun now(context: Context) = IntakeClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull())
    }
}
enum class IntakeState { PENDING, PROCESSING, HANDED_OFF, QUARANTINED, FAILED, EXPIRED, DISCARDED }
enum class QuarantineReason { INITIALIZING, ENABLE_BOUNDARY, BOOK_RESTORE, BOOK_CHANGED, CLOCK_UNCERTAIN, KEY_UNAVAILABLE, PAYLOAD_INVALID }
enum class IntakeFailure { QUEUE_FULL, UNSUPPORTED_SOURCE, SOURCE_DISABLED, SOURCE_CHANGED, TOO_LARGE, CAPACITY, ENCRYPTION, STORAGE, CLOSED }
sealed interface IntakePersistResult {
    data class Saved(val intakeId: String) : IntakePersistResult
    data class Rejected(val reason: IntakeFailure) : IntakePersistResult
}
data class IntakeRecord(
    val intakeId: String, val processSessionId: String, val sequence: Long,
    val bookGeneration: String, val sourceGeneration: Long, val source: PaymentSource,
    val receivedAt: Long, val receivedElapsed: Long, val bootCount: Int?,
    val state: IntakeState, val attempts: Int, val nextAttemptAt: Long,
    val eventId: String?, val reason: QuarantineReason?
)
data class IntakeBatch(val batchId: String, val state: String, val createdAt: Long, val notBefore: Long)
internal fun paymentSource(packageName: String): PaymentSource? = when (packageName) {
    PaymentPackages.WECHAT -> PaymentSource.WECHAT
    PaymentPackages.ALIPAY -> PaymentSource.ALIPAY
    else -> null
}
