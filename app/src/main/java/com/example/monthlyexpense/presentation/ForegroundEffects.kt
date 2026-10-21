package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal class ForegroundEffects(capacity: Int = Channel.BUFFERED) : UiEventSink {
    private val channel = Channel<UiEvent>(capacity)
    val events = channel.receiveAsFlow()
    override fun emit(event: UiEvent): Boolean = channel.trySend(event).isSuccess
    fun close() { channel.close() }
}
