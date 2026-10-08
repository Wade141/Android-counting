package com.example.monthlyexpense.ui.background

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackgroundState(
    val saved: BackgroundImage? = null,
    val draft: BackgroundImage? = null,
    val editorOpen: Boolean = false,
    val busy: Boolean = true,
    val error: String? = null
)

internal class BackgroundViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BackgroundStore(application)
    private val mutableState = MutableStateFlow(BackgroundState())
    val state = mutableState.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
            mutableState.update { it.copy(saved = image, busy = false) }
            initialized.complete(Unit)
        }
    }

    fun open() { mutableState.update { it.copy(editorOpen = true, error = null) } }
    fun close() {
        if (!state.value.busy) mutableState.update { it.copy(editorOpen = false, draft = null, error = null) }
    }
    fun cancelPreview() {
        if (!state.value.busy) mutableState.update { it.copy(draft = null, error = null) }
    }
    fun move(x: Float, y: Float) {
        if (!state.value.busy) mutableState.update { it.copy(draft = it.draft?.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))) }
    }
    fun select(uri: Uri) {
        viewModelScope.launch {
            // A restored photo-picker result can arrive before startup loading completes.
            initialized.await()
            if (state.value.busy) return@launch
            mutableState.update { it.copy(busy = true, error = null, editorOpen = true) }
            val image = withContext(Dispatchers.IO) { runCatching { store.importImage(uri) }.getOrNull() }
            mutableState.update { it.copy(busy = false, draft = image, error = if (image == null) "图片读取失败，请重新选择" else null) }
        }
    }
    fun apply() {
        val image = state.value.draft ?: return
        persist(image)
    }
    fun reset() = persist(null)
    private fun persist(image: BackgroundImage?) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) {
                runCatching { if (image == null) store.reset() else store.save(image) }
            }
            mutableState.update {
                if (result.isSuccess) it.copy(saved = image, draft = null, editorOpen = false, busy = false)
                else it.copy(busy = false, error = "背景保存失败，请重试")
            }
        }
    }
}
