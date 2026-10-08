package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.notification.decision.DecisionReasons
import com.example.monthlyexpense.notification.repository.*
import com.example.monthlyexpense.ui.expenseDate
import com.example.monthlyexpense.ui.formatMoney
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun NotificationManagementPanel() {
    val context = LocalContext.current
    val app = context.applicationContext as MonthlyExpenseApplication
    val coordinator = app.container.persistenceCoordinator
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pending by remember { mutableStateOf(emptyList<PendingNotification>()) }
    var pendingCount by remember { mutableIntStateOf(0) }
    var pageLoading by remember { mutableStateOf(false) }
    val readGeneration = remember { java.util.concurrent.atomic.AtomicLong(0) }
    val displayedEpoch = remember { java.util.concurrent.atomic.AtomicLong(coordinator.currentEpoch) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PendingNotification?>(null) }
    var links by remember { mutableStateOf<List<LinkableExpense>?>(null) }
    var rulesOpen by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val generation = readGeneration.incrementAndGet()
        val includeDetails = showList
        val ticket = coordinator.mutationTicket() ?: return
        val result = coordinator.runRead(ticket) { withContext(Dispatchers.IO) {
            ExpenseDatabaseHelper(context).use {
                val repository = NotificationEventRepository(it)
                repository.pendingCount() to if (includeDetails) repository.pendingPage() else emptyList()
            }
        } }
        if (result is CoordinatedMutation.Executed && coordinator.isCurrent(ticket) && generation == readGeneration.get()) {
            if (displayedEpoch.get() != coordinator.currentEpoch) { selected = null; links = null }
            displayedEpoch.set(coordinator.currentEpoch)
            pendingCount = result.value.first
            pending = result.value.second
        }
    }
    fun reload() { scope.launch {
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "暂时无法读取待确认记录，请重试。" }
    } }
    fun loadMore() {
        val after = pending.lastOrNull() ?: return
        if (pageLoading) return
        pageLoading = true
        val generation = readGeneration.get()
        val revision = app.container.dataChanges.revisions.value.review
        scope.launch {
            try {
                val ticket = coordinator.mutationTicket(displayedEpoch.get()) ?: return@launch
                val result = coordinator.runRead(ticket) { withContext(Dispatchers.IO) {
                    ExpenseDatabaseHelper(context).use { NotificationEventRepository(it).pendingPage(after) }
                } }
                if (result is CoordinatedMutation.Executed && coordinator.isCurrent(ticket) &&
                    generation == readGeneration.get() && revision == app.container.dataChanges.revisions.value.review && showList) {
                    pending = pending + result.value
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "暂时无法读取更多记录，请重试。" }
            finally { pageLoading = false }
        }
    }
    fun mutate(operation: (NotificationEventRepository) -> StoreResult) {
        if (busy) return
        val ticket = coordinator.mutationTicket(displayedEpoch.get())
        if (ticket == null) { message = "账本已发生变化，请重新打开待确认记录。"; selected = null; reload(); return }
        busy = true
        scope.launch {
            try {
                val result = coordinator.runMutation(ticket) { withContext(Dispatchers.IO) {
                    ExpenseDatabaseHelper(context).use { operation(app.container.notificationEvents(it)) }
                } }
                val value = (result as? CoordinatedMutation.Executed)?.value
                if (value == null || value == StoreResult.REJECTED) message = "记录已变化或输入无效，请重新核对。"
                else {
                    message = when (value) { StoreResult.INSERTED -> "已记入支出"; StoreResult.IGNORED -> "已忽略"; else -> "已处理，不会重复记账" }
                    selected = null; links = null
                }
                if (value == null || value == StoreResult.REJECTED) refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "保存未完成，请重试。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(lifecycle, app, showList) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            app.container.dataChanges.revisions.map { it.review }.distinctUntilChanged().collect {
                try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "暂时无法读取待确认记录，请重试。" }
            }
        }
    }
    com.example.monthlyexpense.ui.settings.AppearanceCard(cornerRadius = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showList = true }) { Text("待确认 $pendingCount 条") }
                TextButton(onClick = { rulesOpen = true }) { Text("识别规则") }
            }
            if (pendingCount > 0) Text("这些付款尚未计入消费，请核对后确认。", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            NotificationQuarantinePanel()
            RecordedNotificationSettings()
        }
    }
    if (showList && selected == null) AlertDialog(onDismissRequest = { showList = false }, title = { Text("待确认付款") }, text = {
        if (pending.isEmpty()) Text("目前没有待确认的付款。") else LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(pending, key = { it.eventId }) { item ->
                TextButton(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${item.merchant ?: if (item.source == "WECHAT") "微信" else "支付宝"} · ${item.amountCents?.let(::formatMoney) ?: "金额待核对"}")
                        Text(expenseDate(item.observedAt), style = MaterialTheme.typography.bodySmall)
                        Text(item.reasons.joinToString("；", transform = DecisionReasons::describe), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (pending.size < pendingCount) item {
                TextButton(onClick = { loadMore() }, enabled = !pageLoading) { Text(if (pageLoading) "读取中" else "加载更多") }
            }
        }
    }, confirmButton = { TextButton(onClick = { showList = false }) { Text("关闭") } })
    selected?.let { item ->
        if (links == null) NotificationReviewDialog(item, busy, { selected = null },
            { cents, name -> mutate { it.confirm(item.eventId, cents, name) } },
            { mutate { it.ignore(item.eventId) } }, {
                scope.launch {
                    try {
                        val ticket = coordinator.mutationTicket(displayedEpoch.get()) ?: return@launch
                        val result = coordinator.runRead(ticket) { withContext(Dispatchers.IO) {
                            ExpenseDatabaseHelper(context).use { NotificationEventRepository(it).linkCandidates(item.eventId) }
                        } }
                        if (coordinator.isCurrent(ticket)) links = (result as? CoordinatedMutation.Executed)?.value
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "暂时无法读取账单。" }
                }
            })
        else AlertDialog(onDismissRequest = { if (!busy) links = null }, title = { Text("选择已经记过的账单") }, text = {
            if (links!!.isEmpty()) Text("附近日期没有可关联的账单。") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(links!!, key = { it.id }) { expense ->
                    TextButton(onClick = { mutate { it.link(item.eventId, expense.id) } }, enabled = !busy) {
                        Text("${expense.name} · ${formatMoney(expense.amountCents)}\n${expenseDate(expense.spentAt)}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { links = null }, enabled = !busy) { Text("返回") } })
    }
    if (rulesOpen) PaymentRulesDialog { rulesOpen = false }
}
