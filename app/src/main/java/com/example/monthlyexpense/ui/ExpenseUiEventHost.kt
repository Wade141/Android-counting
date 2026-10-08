package com.example.monthlyexpense.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.monthlyexpense.BackupExportFormat
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal data class DocumentEffect(
    val format: BackupExportFormat,
    val generation: Long,
    val writeId: Long
)

@Composable
internal fun ExpenseUiEventHost(
    events: Flow<UiEvent>,
    documentEffect: (writeId: Long) -> DocumentEffect?,
    documentWriter: suspend (targetUri: String, content: String) -> Boolean,
    onDocumentWriteCompleted: (effect: DocumentEffect, success: Boolean) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentDocumentEffect by rememberUpdatedState(documentEffect)
    val currentDocumentWriter by rememberUpdatedState(documentWriter)
    val currentDocumentWriteCompleted by rememberUpdatedState(onDocumentWriteCompleted)
    var visibleMessage by remember { mutableStateOf<UiMessage?>(null) }

    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { event ->
                when (event) {
                    is UiEvent.ShowMessage -> visibleMessage = event.message
                    is UiEvent.WriteDocument -> {
                        val effect = currentDocumentEffect(event.writeId) ?: return@collect
                        val writer = currentDocumentWriter
                        val writeCompleted = currentDocumentWriteCompleted
                        val success = try {
                            withContext(NonCancellable) {
                                writer(event.targetUri, event.content)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        writeCompleted(effect, success)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        }
    }

    visibleMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { visibleMessage = null },
            confirmButton = {
                TextButton(onClick = { visibleMessage = null }) { Text("知道了") }
            },
            title = { Text("提示") },
            text = { Text(message.text) }
        )
    }
}
