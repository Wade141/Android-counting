package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.backup.BackupRestoreResult
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.intake.QuarantineReason
import com.example.monthlyexpense.notification.parser.PaymentAmountParser
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.repository.StoreResult
import com.example.monthlyexpense.ui.expenseDate
import com.example.monthlyexpense.ui.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.math.BigDecimal

/** UI projection only; raw notification text and decrypted payloads never enter Compose state. */
private data class QuarantinedNotificationItem(val intakeId: String, val bookGeneration: String,
    val source: String, val observedAt: Long, val amountCents: Long?, val candidates: List<Long>,
    val merchant: String?, val explanation: String, val available: Boolean)

private data class QuarantineSnapshot(val generation: String, val count: Int,
    val items: List<QuarantinedNotificationItem>, val settingsPending: Boolean)

@Composable
internal fun NotificationQuarantinePanel() {
    val context = LocalContext.current
    val container = (context.applicationContext as MonthlyExpenseApplication).container
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<QuarantineSnapshot?>(null) }
    var selected by remember { mutableStateOf<QuarantinedNotificationItem?>(null) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val readVersion = remember { java.util.concurrent.atomic.AtomicLong(0) }

    suspend fun refresh() {
        val request = readVersion.incrementAndGet()
        val includeDetails = showList
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                val runtime = container.notificationIntake
                runtime.initialize()
                runtime.classifyQuarantined()
                val protocol = container.database.backupDao.notificationProtocol
                val generation = protocol.bookGeneration()
                val records = runtime.inbox.listQuarantined()
                val parser = if (includeDetails) PaymentDecisionEngine(container.paymentRules.snapshot()) else null
                val items = if (parser == null) emptyList() else records.map { item ->
                    val raw = runtime.inbox.readPayload(item.intakeId)
                    val decision = raw?.let(parser::evaluate)
                    QuarantinedNotificationItem(item.intakeId, generation,
                        if (item.source == PaymentSource.WECHAT) "微信" else "支付宝",
                        raw?.postTime ?: item.receivedAt, decision?.amountCents,
                        decision?.amounts?.map { it.cents }?.distinct().orEmpty(), decision?.merchant,
                        if (raw == null) "通知内容已不可用，请核对当前账本后手动补录。" else quarantineExplanation(item.reason),
                        raw != null)
                }
                QuarantineSnapshot(generation, records.size, items, protocol.pendingRestore() != null)
            }
            if (request == readVersion.get()) {
                if (selected?.bookGeneration?.let { it != loaded.generation } == true) {
                    selected = null
                    message = "账本已发生变化，请重新核对这条通知。"
                }
                snapshot = loaded
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "暂时无法读取待核对通知，请重试。" }
        finally {
            // A disposed lazy item must not publish state into a cancelled composition.
            if (currentCoroutineContext().isActive && request == readVersion.get()) {
                loading = false
            }
        }
    }

    fun confirm(item: QuarantinedNotificationItem, cents: Long, name: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = container.notificationIntake.confirmQuarantined(item.intakeId, item.bookGeneration, cents, name)
                message = when (result) {
                    StoreResult.INSERTED -> "已记入支出"
                    StoreResult.DUPLICATE -> "已处理，不会重复记账"
                    else -> "这条通知暂时不能确认。请核对来源开关和当前账本，必要时手动补录。"
                }
                if (result == StoreResult.INSERTED || result == StoreResult.DUPLICATE) selected = null
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "保存未完成，请重试。" }
            finally { busy = false }
        }
    }

    fun ignore(item: QuarantinedNotificationItem) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val ignored = container.notificationIntake.ignoreQuarantined(item.intakeId, item.bookGeneration)
                message = if (ignored) "已忽略这条通知" else "通知或账本已变化，请重新核对。"
                if (ignored) selected = null
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "忽略未完成，请重试。" }
            finally { busy = false }
        }
    }

    LaunchedEffect(container, lifecycle, showList) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            container.dataChanges.revisions.map { it.review }.distinctUntilChanged().collect { refresh() }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("notification-quarantine-panel")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showList = true }, enabled = !busy,
                modifier = Modifier.testTag("notification-quarantine-open")) {
                Text(if (snapshot == null) "读取待核对通知" else "通知待核对 ${snapshot!!.count} 条")
            }
            TextButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy) { Text("刷新") }
        }
        if (snapshot?.count?.let { it > 0 } == true) {
            Text("这些通知的接收时间或账本归属需要核对，确认后才会记账。", style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot?.settingsPending == true) {
            Text("账本已恢复，设置尚未恢复完成。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val result = container.notificationIntake.continuePendingRestore()
                            message = if (result is BackupRestoreResult.Completed) "恢复设置已完成"
                                else "设置尚未恢复完成，请稍后重试。"
                            refresh()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "设置尚未恢复完成，请稍后重试。" }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy, modifier = Modifier.testTag("notification-continue-restore")) { Text("继续恢复设置") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (showList && selected == null) {
        AlertDialog(onDismissRequest = { if (!busy) showList = false }, title = { Text("待核对通知") }, text = {
            when {
                loading -> Text("正在读取通知…")
                snapshot?.items.isNullOrEmpty() -> Text("目前没有待核对的通知。")
                else -> LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(snapshot!!.items, key = { "intake:${it.intakeId}" }) { item ->
                        TextButton(onClick = { message = null; selected = item }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${item.merchant ?: item.source} · ${item.amountCents?.let(::formatMoney) ?: "金额待核对"}")
                                Text(expenseDate(item.observedAt), style = MaterialTheme.typography.bodySmall)
                                Text(item.explanation, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showList = false }) { Text("关闭") } })
    }
    selected?.let { item -> QuarantineConfirmationDialog(item, busy, message,
        onDismiss = { selected = null }, onConfirm = { cents, name -> confirm(item, cents, name) }, onIgnore = { ignore(item) }) }
}

@Composable
private fun QuarantineConfirmationDialog(item: QuarantinedNotificationItem, busy: Boolean, message: String?,
    onDismiss: () -> Unit, onConfirm: (Long, String) -> Unit, onIgnore: () -> Unit) {
    var amount by rememberSaveable(item.intakeId, item.bookGeneration) {
        mutableStateOf(item.amountCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty())
    }
    var name by rememberSaveable(item.intakeId, item.bookGeneration) { mutableStateOf(item.merchant ?: "${item.source}付款") }
    val cents = PaymentAmountParser.toCents(amount)?.takeIf { it in 1..MoneyLimits.MAX_CENTS }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("核对通知付款") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${item.source} · ${expenseDate(item.observedAt)}")
            Text(item.explanation)
            Text("请先核对当前账本是否已记过这笔付款。")
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (item.candidates.isNotEmpty()) Text("金额候选：${item.candidates.joinToString("、", transform = ::formatMoney)}")
            OutlinedTextField(amount, { amount = it }, label = { Text("实际支出金额") }, singleLine = true,
                enabled = !busy && item.available, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = amount.isNotEmpty() && cents == null, modifier = Modifier.testTag("quarantine-amount"))
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true,
                enabled = !busy && item.available, isError = name.trim().length !in 1..40,
                modifier = Modifier.testTag("quarantine-name"))
        }
    }, confirmButton = {
        TextButton(onClick = { cents?.let { onConfirm(it, name.trim()) } },
            enabled = !busy && item.available && cents != null && name.trim().length in 1..40,
            modifier = Modifier.testTag("quarantine-confirm")) { Text("确认计入支出") }
    }, dismissButton = {
        Row {
            TextButton(onClick = onIgnore, enabled = !busy, modifier = Modifier.testTag("quarantine-ignore")) { Text("忽略") }
            TextButton(onClick = onDismiss, enabled = !busy) { Text("稍后处理") }
        }
    })
}

private fun quarantineExplanation(reason: QuarantineReason?): String = when (reason) {
    QuarantineReason.BOOK_RESTORE, QuarantineReason.BOOK_CHANGED -> "账本已恢复，请核对这笔付款是否属于当前账本。"
    QuarantineReason.ENABLE_BOUNDARY -> "通知接收刚开启，请核对付款时间。"
    QuarantineReason.CLOCK_UNCERTAIN -> "付款通知的时间需要核对。"
    QuarantineReason.KEY_UNAVAILABLE, QuarantineReason.PAYLOAD_INVALID -> "通知内容暂时无法读取，请核对账本后手动补录。"
    QuarantineReason.INITIALIZING, null -> "通知的接收状态需要核对。"
}
