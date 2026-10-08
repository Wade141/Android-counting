package com.example.monthlyexpense.refresh

import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import kotlinx.coroutines.CompletableDeferred

enum class RefreshScope { HOME, ALL }

data class RefreshRequest(
    val scope: RefreshScope,
    val notificationState: NotificationListenerConnectionState,
    val domains: Set<com.example.monthlyexpense.data.DataDomain> = setOf(
        com.example.monthlyexpense.data.DataDomain.LEDGER, com.example.monthlyexpense.data.DataDomain.SETTINGS)
)

enum class RefreshResult { APPLIED, STALE, FAILED }

internal data class QueuedRefresh(
    val request: RefreshRequest,
    val waiters: List<CompletableDeferred<RefreshResult>> = emptyList(),
    val reportPageFailure: Boolean = false,
    val notificationStateIsExternal: Boolean
)

internal class RefreshRequestMerger {
    private val lock = Any()
    private var draining = false
    private var pending: QueuedRefresh? = null

    fun offer(entry: QueuedRefresh): QueuedRefresh? = synchronized(lock) {
        if (!draining) {
            draining = true
            entry
        } else {
            com.example.monthlyexpense.notification.NotificationMetrics.increment(com.example.monthlyexpense.notification.PipelineMetric.REFRESH_MERGED)
            pending = pending?.merge(entry) ?: entry
            null
        }
    }

    fun nextOrFinish(): QueuedRefresh? = synchronized(lock) {
        pending.also {
            pending = null
            if (it == null) draining = false
        }
    }

    fun invalidatePending(): QueuedRefresh? = synchronized(lock) {
        pending.also { pending = null }
    }

    fun cancelDrain(): QueuedRefresh? = synchronized(lock) {
        pending.also {
            pending = null
            draining = false
        }
    }
}

private fun QueuedRefresh.merge(newer: QueuedRefresh): QueuedRefresh {
    val notificationOwner = when {
        newer.notificationStateIsExternal -> newer
        notificationStateIsExternal -> this
        else -> newer
    }
    return QueuedRefresh(
        request = RefreshRequest(
            scope = if (
                request.scope == RefreshScope.ALL || newer.request.scope == RefreshScope.ALL
            ) RefreshScope.ALL else RefreshScope.HOME,
            notificationState = notificationOwner.request.notificationState,
            domains = request.domains + newer.request.domains
        ),
        waiters = waiters + newer.waiters,
        reportPageFailure = reportPageFailure || newer.reportPageFailure,
        notificationStateIsExternal = notificationOwner.notificationStateIsExternal
    )
}
