package com.example.monthlyexpense.notification

import com.example.monthlyexpense.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Fixed dimensions only: no notification text, identity, amount or merchant is accepted. */
enum class PipelineMetric { RECEIVED, STAGE, PROCESS, SCHEDULE, RULE_LOAD, HOME_READ, LEDGER_READ,
    SETTINGS_READ, REVIEW_COUNT, REVIEW_PAGE, REFRESH_MERGED, UI_APPLY }
data class MetricSample(val count: Long, val totalNanos: Long)

object NotificationMetrics {
    private class Counters { val count = AtomicLong(); val nanos = AtomicLong() }
    private val counters = ConcurrentHashMap<PipelineMetric, Counters>()

    fun increment(metric: PipelineMetric) {
        if (BuildConfig.DEBUG) counters.getOrPut(metric, ::Counters).count.incrementAndGet()
    }

    fun begin(metric: PipelineMetric): Long {
        if (!BuildConfig.DEBUG) return 0
        counters.getOrPut(metric, ::Counters).count.incrementAndGet()
        return System.nanoTime()
    }
    fun end(metric: PipelineMetric, start: Long) {
        if (!BuildConfig.DEBUG || start == 0L) return
        counters[metric]?.nanos?.addAndGet((System.nanoTime() - start).coerceAtLeast(0))
    }
    inline fun <T> measure(metric: PipelineMetric, block: () -> T): T {
        val started = begin(metric)
        return try { block() } finally { end(metric, started) }
    }
    fun snapshot(): Map<PipelineMetric, MetricSample> = counters.mapValues { MetricSample(it.value.count.get(), it.value.nanos.get()) }
    internal fun reset() { counters.clear() }
    fun logSnapshot() {
        if (BuildConfig.DEBUG) android.util.Log.d("BookkeepingMetrics", snapshot().toString())
    }
}
