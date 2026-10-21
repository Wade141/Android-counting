package com.example.monthlyexpense.presentation.contracts

import com.example.monthlyexpense.UiEvent

internal fun interface UiEventSink {
    /** True means queued, not that the document writer has completed. Never replays. */
    fun emit(event: UiEvent): Boolean
}
