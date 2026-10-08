package com.example.monthlyexpense.notification

import com.example.monthlyexpense.notification.parser.*
import com.example.monthlyexpense.notification.repository.PaymentEventId
import kotlinx.coroutines.CancellationException

data class ReplaySummary(val inserted: Int = 0, val existing: Int = 0, val unmatched: Int = 0, val failed: Int = 0) {
    fun message(): String = when {
        failed > 0 -> "已补记 $inserted 笔，另有 $failed 条未完成，可稍后重试。"
        inserted > 0 -> "已补记 $inserted 笔，已记过的 $existing 笔未重复添加。"
        existing > 0 -> "可识别的 $existing 笔付款均已记过，没有重复添加。"
        else -> "最近 30 分钟仍保留的通知中，没有找到可补记的付款。"
    }
}

/** Counts actual database inserts, not queued work or the number of visible notifications. */
class ReplayBatchImporter(
    private val sourceEnabled: (PaymentSource) -> Boolean,
    private val record: (ParsedPayment) -> Boolean
) {
    fun import(notifications: List<RawNotification>, now: Long): ReplaySummary {
        var result = ReplaySummary()
        val seen = mutableSetOf<String>()
        for (raw in notifications) {
            if (!RecentPaymentNotificationFilter.isEligible(raw.packageName, raw.postTime, now)) continue
            val source = if (raw.packageName == PaymentPackages.WECHAT) PaymentSource.WECHAT else PaymentSource.ALIPAY
            if (!sourceEnabled(source)) continue
            try {
                val payment = when (source) {
                    PaymentSource.WECHAT -> WeChatPaymentParser().parse(raw)
                    PaymentSource.ALIPAY -> AlipayPaymentParser().parse(raw)
                }
                if (payment == null) {
                    result = result.copy(unmatched = result.unmatched + 1)
                    continue
                }
                val key = PaymentEventId.create(raw.packageName, payment.notificationKey, payment.time, payment.amountCents).dedupeId
                if (!seen.add(key)) continue
                result = if (record(payment)) result.copy(inserted = result.inserted + 1)
                    else result.copy(existing = result.existing + 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                result = result.copy(failed = result.failed + 1)
            }
        }
        return result
    }
}
