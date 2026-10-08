package com.example.monthlyexpense.notification.replay

import com.example.monthlyexpense.notification.ReplayBudgetState

enum class ConnectionStatus { CHECKING, CONNECTING, CONNECTED, DISCONNECTED, FAILED, UNKNOWN }
enum class ReplayPhase {
    QUEUED, CHECKING_CONNECTION, COLLECTING, PROCESSING, COMPLETED, PARTIAL, FAILED, INTERRUPTED;
    val terminal: Boolean get() = this in setOf(COMPLETED, PARTIAL, FAILED, INTERRUPTED)
}
enum class ReplayCollectionSource { INTAKE, STAGED, SNAPSHOT }
enum class ReplayTerminationReason { READ, STORAGE, CAPACITY, TIMEOUT, BOOK_CHANGED, PERMISSION, DISABLED, INTERRUPTED, UNKNOWN }
enum class ReplayItemResult { BOOKED, REVIEW, IGNORED, ALREADY_PROCESSED, SOURCE_DISABLED, UNRECOGNIZED, FAILED }

/** Identifiers only: never put notification keys, payment text or merchant names here. */
data class ReplayOperationItem(
    val itemId: String,
    val eventId: String? = null,
    val result: ReplayItemResult? = null,
    val aliases: Set<String> = emptySet()
)

/** A copy of a committed ledger receipt, never an increment based on in-memory processing. */
data class ReplayOperationReceipt(
    val itemId: String,
    val eventId: String?,
    val bookGeneration: String,
    val result: ReplayItemResult
)

data class ReplayAttempt(
    val attemptId: String,
    val createdAt: Long,
    val createdElapsed: Long,
    val bootCount: Int?,
    val deadlineAt: Long,
    val budget: ReplayBudgetState = ReplayBudgetState(),
    val processSessionId: String? = null
)

data class ReplayOperationSummary(
    val booked: Int = 0,
    val review: Int = 0,
    val ignored: Int = 0,
    val alreadyProcessed: Int = 0,
    val unfinished: Int = 0
)

data class ReplayOperation(
    val operationId: String,
    val manual: Boolean,
    val createdAt: Long,
    val bookGeneration: String,
    val phase: ReplayPhase = ReplayPhase.QUEUED,
    val reason: ReplayTerminationReason? = null,
    val confirmedConnectionEpoch: Long? = null,
    val confirmedConnectionAt: Long? = null,
    val fenceSessionId: String? = null,
    val fenceSequence: Long? = null,
    val collected: Set<ReplayCollectionSource> = emptySet(),
    val collectionSealed: Boolean = false,
    val items: List<ReplayOperationItem> = emptyList(),
    val attempts: List<ReplayAttempt> = emptyList(),
    val dismissed: Boolean = false,
    val superseded: Boolean = false,
    val finishedAt: Long? = null
) {
    val attempt: ReplayAttempt? get() = attempts.lastOrNull()
    val summary: ReplayOperationSummary get() = ReplayOperationSummary(
        booked = items.count { it.result == ReplayItemResult.BOOKED },
        review = items.count { it.result == ReplayItemResult.REVIEW },
        ignored = items.count { it.result in setOf(ReplayItemResult.IGNORED, ReplayItemResult.SOURCE_DISABLED, ReplayItemResult.UNRECOGNIZED) },
        alreadyProcessed = items.count { it.result == ReplayItemResult.ALREADY_PROCESSED },
        unfinished = items.count { it.result == null || it.result == ReplayItemResult.FAILED }
    )
    val canRetry: Boolean get() = phase.terminal && phase != ReplayPhase.COMPLETED && reason != ReplayTerminationReason.BOOK_CHANGED
    val progressMessage: String get() = when (phase) {
        ReplayPhase.QUEUED, ReplayPhase.CHECKING_CONNECTION -> "正在恢复通知接收…"
        ReplayPhase.COLLECTING -> "正在检查补记…"
        ReplayPhase.PROCESSING -> "正在处理已保存的通知…"
        else -> ""
    }
}

data class ReplayResultPresentation(
    val operationId: String,
    val title: String,
    val connectionMessage: String,
    val resultMessage: String,
    val canRetry: Boolean
) {
    companion object {
        /** Current connection comes only from the live listener, never from a historical DB fact. */
        fun from(operation: ReplayOperation, currentConnection: ConnectionStatus): ReplayResultPresentation? {
            if (!operation.manual || !operation.phase.terminal || operation.dismissed || operation.superseded) return null
            val complete = operation.phase == ReplayPhase.COMPLETED
            val summary = operation.summary
            val connection = when (currentConnection) {
                ConnectionStatus.CONNECTED -> if (operation.confirmedConnectionAt != null) "通知接收已恢复。" else "通知接收当前正常。"
                ConnectionStatus.DISCONNECTED -> "通知接收当前已断开。"
                ConnectionStatus.FAILED -> "暂未恢复通知接收。"
                else -> "通知接收当前状态尚未确认。"
            }
            val review = if (summary.review > 0) "，${summary.review} 条待确认" else ""
            val message = when {
                operation.reason == ReplayTerminationReason.BOOK_CHANGED -> "账本已变更，本次补记检查已中断。"
                complete && summary.booked == 0 && summary.review == 0 -> "本次检查完成，没有新增账目。"
                complete -> "本次补记 ${summary.booked} 笔$review。"
                summary.unfinished > 0 -> "已补记 ${summary.booked} 笔$review，另有 ${summary.unfinished} 条未完成，可重试。"
                summary.booked > 0 || summary.review > 0 -> "已补记 ${summary.booked} 笔$review，本次补记检查未完成，可重试。"
                else -> "本次补记检查未完成，可重试。"
            }
            return ReplayResultPresentation(operation.operationId, if (complete) "补记检查完成" else "补记尚未完成",
                connection, message, operation.canRetry)
        }
    }
}
