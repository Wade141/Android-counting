package com.example.monthlyexpense.ocrtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.monthlyexpense.ui.ExpenseAppTheme

class OcrTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recognizer = OcrTestRecognizer(contentResolver, cacheDir, applicationContext)
        val factory = viewModelFactory { initializer { OcrTestViewModel(recognizer::recognize) } }
        setContent {
            val model: OcrTestViewModel = viewModel(factory = factory)
            val state by model.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(model::select)
            }
            ExpenseAppTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(onClick = { finish() }) { Text("返回记账") }
                        Text("OCR 识别测试", style = MaterialTheme.typography.headlineSmall)
                        Text("选择支付截图查看原始文字。此页面不保存账目，不删除相册原图。")
                        Text("内置中文模型 · 可断网测试 · 耗时包含读图和模型初始化")
                        Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !state.running) {
                            Text(if (state.running) "正在识别…" else "选择图片")
                        }
                        if (state.running) {
                            LinearProgressIndicator()
                            Text("正在读取图片并识别文字，请稍候。")
                        }
                        state.error?.let {
                            SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        state.result?.let { result ->
                            Text("图片：${result.width} × ${result.height}；耗时：${result.elapsedMs} 毫秒")
                            Text("解析候选（请对照原图确认）", style = MaterialTheme.typography.titleMedium)
                            OcrAnalysisPanel(result.analysis)
                            Text("原始识别文字（长按可选择复制）")
                            SelectionContainer {
                                Text(result.text.ifBlank { "未识别到文字，请选择清晰的支付截图重试。" })
                            }
                            Text("文字＋坐标（行与元素，长按可复制）", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Text(result.lines.coordinateReport()) }
                        }
                    }
                }
            }
        }
    }
}
