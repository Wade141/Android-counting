package com.example.monthlyexpense.notification

import com.example.monthlyexpense.notification.decision.*
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.repository.*
import kotlinx.coroutines.CancellationException

data class DecisionReplaySummary(val inserted: Int = 0, val existing: Int = 0, val review: Int = 0,
    val ignored: Int = 0, val failed: Int = 0) {
    fun message() = "已补记 $inserted 笔，$review 条待确认，$existing 条已处理，$ignored 条不属于可记支出。" +
        if (failed > 0) "另有 $failed 条未完成，可稍后重试。" else ""
}

class NotificationDecisionProcessor(private val engine: PaymentDecisionEngine, private val repository: NotificationEventRepository) {
    fun stage(raw: RawNotification): Pair<PaymentDecision, StoreResult> {
        val decision = engine.evaluate(raw)
        return decision to repository.stage(decision)
    }
    fun stageForScheduling(raw: RawNotification): Pair<PaymentDecision, StagedResult> {
        return NotificationMetrics.measure(PipelineMetric.STAGE) {
            val decision = engine.evaluate(raw)
            decision to repository.stageForScheduling(decision)
        }
    }
    fun process(eventId: String) = repository.process(eventId)
    fun replay(raws: List<RawNotification>, now: Long, enabled: (PaymentSource) -> Boolean): DecisionReplaySummary =
        replay(raws, now, enabled) {}

    fun replay(raws: List<RawNotification>, now: Long, enabled: (PaymentSource) -> Boolean,
        beforeEach: () -> Unit): DecisionReplaySummary {
        var summary = DecisionReplaySummary()
        val seen = mutableSetOf<String>()
        raws.forEach { raw ->
            beforeEach()
            if (!RecentPaymentNotificationFilter.isEligible(raw.packageName, raw.postTime, now)) return@forEach
            val source = if (raw.packageName == PaymentPackages.WECHAT) PaymentSource.WECHAT else PaymentSource.ALIPAY
            if (!enabled(source)) return@forEach
            try {
                val (decision, staged) = stage(raw)
                if (!seen.add(decision.eventId)) return@forEach
                val result = if (staged == StoreResult.IGNORED || staged == StoreResult.REJECTED) staged else process(decision.eventId)
                summary = when (result) {
                    StoreResult.INSERTED -> summary.copy(inserted = summary.inserted + 1)
                    StoreResult.DUPLICATE -> summary.copy(existing = summary.existing + 1)
                    StoreResult.REVIEW -> summary.copy(review = summary.review + 1)
                    StoreResult.IGNORED -> summary.copy(ignored = summary.ignored + 1)
                    else -> summary.copy(failed = summary.failed + 1)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { summary = summary.copy(failed = summary.failed + 1) }
        }
        return summary
    }
}
