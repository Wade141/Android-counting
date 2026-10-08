package com.example.monthlyexpense.screenshot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.monthlyexpense.MainActivity
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.ocrtest.OcrTestRecognizer
import com.example.monthlyexpense.ui.ExpenseAppTheme

/** A separate activity keeps the main manual-entry draft untouched by external shares. */
class ScreenshotEntryActivity : ComponentActivity() {
    private lateinit var model: ScreenshotEntryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MonthlyExpenseApplication).container
        val appContext = applicationContext
        model = ViewModelProvider(this, viewModelFactory {
            initializer {
                val handle = createSavedStateHandle()
                bindScreenshotSession(handle, "${container.screenshotSessionId}:${container.persistenceCoordinator.currentEpoch}")
                ScreenshotEntryViewModel(
                    ScreenshotExpenseStore(container.database, container.persistenceCoordinator,
                        onSaved = { container.dataChanges.publish(setOf(com.example.monthlyexpense.data.DataDomain.LEDGER)) }),
                    handle,
                    OcrTestRecognizer(appContext.contentResolver, appContext.cacheDir, appContext)::recognize
                )
            }
        })[ScreenshotEntryViewModel::class.java]
        if (savedInstanceState == null) accept(intent)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            var leaving by rememberSaveable { mutableStateOf(false) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.receive(uri)
            }
            fun exit() {
                when {
                    state.saving -> Unit
                    state.savedId != null -> openLedger()
                    state.active -> leaving = true
                    else -> finish()
                }
            }
            BackHandler { exit() }
            ExpenseAppTheme {
                ScreenshotEntryScreen(
                    state = state, onEdit = model::edit, onConfirm = model::confirm,
                    onChooseImage = { picker.launch(arrayOf("image/*")) },
                    onSave = { model.save() }, onReturn = { exit() },
                    onDismissDuplicate = model::dismissDuplicate,
                    onSaveDuplicate = { model.save(allowDuplicate = true) },
                    onReplace = model::resolveReplacement
                )
                if (leaving) AlertDialog(
                    onDismissRequest = { leaving = false },
                    title = { Text("放弃这份截图草稿？") },
                    text = { Text("尚未保存的内容将丢弃；相册中的原图不会被删除。") },
                    confirmButton = { TextButton(onClick = { finish() }) { Text("放弃并返回") } },
                    dismissButton = { TextButton(onClick = { leaving = false }) { Text("继续填写") } }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(incoming: Intent) {
        when (val input = parseScreenshotShare(incoming)) {
            ScreenshotShareInput.Gallery -> Unit
            is ScreenshotShareInput.Image -> model.receive(input.uri)
            is ScreenshotShareInput.Invalid -> model.fail(input.message)
        }
    }

    private fun openLedger() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
