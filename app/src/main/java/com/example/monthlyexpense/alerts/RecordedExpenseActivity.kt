package com.example.monthlyexpense.alerts

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.*
import com.example.monthlyexpense.ui.ExpenseAppTheme
import com.example.monthlyexpense.ui.forms.ExpenseEditDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Private notification destination. Reuses the normal editor and never creates a new expense. */
class RecordedExpenseActivity : ComponentActivity() {
    private val target=mutableStateOf<RecordedExpenseTarget?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target.value=RecordedExpenseTarget.fromUri(intent.data)
        val container=(application as MonthlyExpenseApplication).container
        val store=RecordedExpenseStore(container.database,container.persistenceCoordinator,container.dataChanges::publish,
            initialize={container.notificationIntake.initialize()})
        setContent {
            ExpenseAppTheme {
                key(target.value) {
                    RecordedExpenseEditor(target.value,store,::closeEditor) {
                        Toast.makeText(this,"已保存修改",Toast.LENGTH_SHORT).show()
                        closeEditor()
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target.value=RecordedExpenseTarget.fromUri(intent.data)
    }
    private fun closeEditor() {
        if(isTaskRoot) startActivity(Intent(this,MainActivity::class.java))
        finish()
    }
}

@Composable
internal fun RecordedExpenseEditor(target: RecordedExpenseTarget?, store: RecordedExpenseStore,
    onClose: () -> Unit, onSaved: () -> Unit) {
    var page by remember(target) { mutableStateOf<RecordedExpensePage?>(null) }
    var loading by remember(target) { mutableStateOf(true) }
    var saving by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    BackHandler { if(!saving) onClose() }
    LaunchedEffect(target) {
        try { page=target?.let {store.load(it)} }
        catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {error="暂时无法读取账单，请返回后重试。"}
        finally {loading=false}
    }
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),contentAlignment=Alignment.Center) {
            if(loading) CircularProgressIndicator()
            else if(page==null) Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(error ?: "这笔账单已删除、所属账本已变化，或正在恢复备份。")
                Button(onClick=onClose) {Text("返回账本")}
            }
        }
        page?.let { loaded ->
            ExpenseEditDialog(loaded.expense,loaded.categories,saving,onClose) { name,note,category,date,time,explicit,special ->
                if(name.trim().isEmpty() || name.trim().length > 40 || note.trim().length > 100) {
                    error="消费名称需为 1–40 个字，备注最多 100 个字。"
                } else if(!saving && target!=null) {
                    saving=true
                    scope.launch {
                        try {
                            val saved=store.save(target,ExpenseEditInput(name,note,category,date,time,explicit,special))
                            if(saved) onSaved() else error="保存未完成：账单或账本可能已变化，请返回后重新打开。"
                        } catch(cancelled: CancellationException) {throw cancelled}
                        catch(_: Exception) {error="保存失败，请重试。"}
                        finally {saving=false}
                    }
                }
            }
            error?.let { message -> AlertDialog(onDismissRequest={error=null},title={Text("未保存")},
                text={Text(message)},confirmButton={TextButton(onClick={error=null}) {Text("知道了")}}) }
        }
    }
}
