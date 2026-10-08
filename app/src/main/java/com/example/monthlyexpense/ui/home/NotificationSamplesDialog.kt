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
import com.example.monthlyexpense.notification.*
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.rules.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun NotificationSamplesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var json by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("只读取系统仍保留的支付通知；已经消失且未保存的通知无法恢复。") }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val content = json
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(content.toByteArray(Charsets.UTF_8).size <= PaymentRuleStore.MAX_BYTES)
                        JSONObject(content)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("无法写入")
                    }
                    message = "样本已保存；没有上传或启用任何新规则。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "导出失败，请检查内容和保存位置。" }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("识别样本") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message)
            TextButton(enabled = !busy, onClick = {
                run {
                    busy = true
                    scope.launch {
                        try {
                            val result = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication)
                                .container.notifications.snapshots.read()
                            val snapshot = (result as? SnapshotResult.Success)?.snapshot?.notifications
                            if (snapshot == null) {
                                message = "暂时无法读取，请先恢复通知监听。"
                                return@launch
                            }
                            val (content, count) = withContext(Dispatchers.IO) {
                                val store = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container.paymentRules
                                val rules = store.snapshot()
                                val engine = PaymentDecisionEngine(rules)
                                val samples = JSONArray()
                                snapshot.filter { PaymentPackages.isSupported(it.packageName) }.take(50).forEach { raw ->
                                    val decision = engine.evaluate(raw)
                                    if (decision.reasons.any { it in setOf("identity_missing", "unsupported_source", "not_transaction", "oversized") }) return@forEach
                                    val clean = NotificationSampleTools.sanitize(raw)
                                    val d = engine.evaluate(clean)
                                    samples.put(JSONObject().put("id", "sample-${samples.length() + 1}")
                                        .put("source", d.source!!.name).put("title", clean.title).put("text", clean.text)
                                        .put("bigText", clean.bigText).put("textLines", JSONArray(clean.textLines))
                                        .put("subText", clean.subText).put("isGroupSummary", clean.isGroupSummary)
                                        .put("action", d.action.name).put("amountCents", d.amountCents ?: JSONObject.NULL))
                                }
                                JSONObject(store.exportCurrent()).put("samples", samples).toString(2) to samples.length()
                            }
                            json = content
                            message = "已提取 $count 条。已尝试隐藏账号和商户，请检查并删除残留的敏感信息。action 和 amountCents 是当前判定，请修正为你确认的结果再导出。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "样本提取未完成，请重试。" }
                        finally { busy = false }
                    }
                }
            }) { Text("提取仍保留的通知") }
            if (json.isNotBlank()) {
                OutlinedTextField(json, { json = it }, label = { Text("脱敏预览与预期结果") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp))
                TextButton(enabled = !busy, onClick = { export.launch("payment-rule-samples.json") }) { Text("确认内容并导出") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
}
