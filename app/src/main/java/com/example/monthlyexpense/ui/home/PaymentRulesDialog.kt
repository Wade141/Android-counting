package com.example.monthlyexpense.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.notification.rules.*
import kotlinx.coroutines.*

@Composable
internal fun PaymentRulesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container.paymentRules }
    var version by remember { mutableStateOf("读取中") }
    var preview by remember { mutableStateOf<RulePreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var samplesOpen by remember { mutableStateOf(false) }
    fun runAction(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = withContext(Dispatchers.IO) { action() }; version = withContext(Dispatchers.IO) { store.snapshot().version } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "操作未完成，当前规则未更改，请重试。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { version = withContext(Dispatchers.IO) { store.snapshot().version } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (output.size() <= PaymentRuleStore.MAX_BYTES) {
                                val count = input.read(buffer, 0, minOf(buffer.size, PaymentRuleStore.MAX_BYTES + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                            ?: error("无法读取文件")
                        require(bytes.size <= PaymentRuleStore.MAX_BYTES)
                        store.preview(bytes.toString(Charsets.UTF_8))
                    }
                    preview = result
                    message = if (result.valid) "样本验证通过，请查看结果后启用。" else "验证未通过，当前规则保持不变。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "无法导入，请选择不超过128KB的有效规则文件。" }
                finally { busy = false }
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runAction {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                context.assets.open("payment-rules/default.json").use { it.copyTo(output) }
            } ?: error("无法写入文件")
            "已导出内置规则模板，可修改词组并添加测试样本。"
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("通知识别规则") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前版本：$version")
            Text("导入前会验证已有样本；新规则只影响之后处理的通知，不会修改历史账单。")
            TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("导入规则文件") }
            TextButton(onClick = { export.launch("payment-rules-template.json") }, enabled = !busy) { Text("导出规则模板") }
            TextButton(onClick = { samplesOpen = true }, enabled = !busy) { Text("提取与导出识别样本") }
            preview?.let { p ->
                Text("样本通过 ${p.checks.count { it.passed }}/${p.checks.size}，判定变化 ${p.checks.count { it.changed }} 条")
                p.failures.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                p.checks.filter { it.changed }.forEach { Text("${it.id}：${it.previous} → ${it.actual}", style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = p.valid && !busy, onClick = { runAction {
                    if (store.activate(p)) "新规则已启用" else "规则已变化或验证未通过，请重新导入。"
                } }) { Text("启用导入的规则") }
            }
            TextButton(enabled = !busy, onClick = { runAction { if (store.rollback()) "已恢复上一版本" else "没有可恢复的有效版本" } }) { Text("回退上一版本") }
            TextButton(enabled = !busy, onClick = { runAction { store.reset(); "已恢复内置规则" } }) { Text("恢复内置规则") }
            message?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
    if (samplesOpen) NotificationSamplesDialog { samplesOpen = false }
}
