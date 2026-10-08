package com.example.monthlyexpense.ocrtest

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class OcrTestState(
    val running: Boolean = false,
    val result: OcrTestResult? = null,
    val error: String? = null
)

internal class OcrTestViewModel(private val recognize: suspend (Uri) -> OcrTestResult) : ViewModel() {
    private val mutableState = MutableStateFlow(OcrTestState())
    val state = mutableState.asStateFlow()

    fun select(uri: Uri) {
        if (mutableState.value.running) return
        mutableState.value = OcrTestState(running = true)
        viewModelScope.launch {
            try {
                mutableState.value = OcrTestState(result = recognize(uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val cause = error.cause ?: error
                mutableState.value = OcrTestState(error = "${cause.javaClass.simpleName}: ${cause.message ?: "识别失败，请重新选择图片"}")
            }
        }
    }
}
