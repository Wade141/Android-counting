package com.example.monthlyexpense.presentation.contracts

internal interface ReadState<out S> {
    val value: S
}

internal interface SliceState<S> : ReadState<S> {
    /** Synchronous. The reducer may be retried and must have no side effects. */
    fun update(transform: (S) -> S)
}
