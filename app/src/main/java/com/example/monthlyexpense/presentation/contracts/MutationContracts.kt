package com.example.monthlyexpense.presentation.contracts

import com.example.monthlyexpense.UiMessage

/** Pure interpretation of persistence output; no navigation or state reducer is carried here. */
internal data class MutationResult(val committed: Boolean, val message: UiMessage? = null)

/** Synchronous feature-owned pending/generation protocol. Calls share the command admission lock.
 * Implementations must not perform IO or acquire the coordinator; reducers must be pure. */
internal interface MutationSlot {
    /** null means duplicate pending with no changes; otherwise marks pending and returns a new generation. */
    fun admit(): Long?
    fun isCurrent(generation: Long): Boolean
    /** Clear only this generation, including cancellation before the coroutine starts. Stale calls are no-ops. */
    fun finish(generation: Long)
}
