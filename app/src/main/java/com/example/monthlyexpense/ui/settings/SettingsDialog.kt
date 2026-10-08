package com.example.monthlyexpense.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.ui.ExpenseAppTheme
import com.example.monthlyexpense.ui.background.BackgroundImage
import com.example.monthlyexpense.ui.background.BackgroundLayer

internal val presets = listOf(
    "墨黑" to 0xFF292521L, "纯黑" to 0xFF000000L, "深灰" to 0xFF555555L,
    "纯白" to 0xFFFFFFFFL, "米白" to 0xFFFFF3D6L, "海蓝" to 0xFF1565C0L,
    "藏蓝" to 0xFF1E3A5FL, "松绿" to 0xFF2E7D32L, "青绿" to 0xFF00796BL,
    "紫色" to 0xFF7B1FA2L, "酒红" to 0xFF9F1239L, "棕色" to 0xFF795548L
)

@Composable
internal fun SettingsDialog(
    state: AppearanceState,
    background: BackgroundImage?,
    onClose: () -> Unit,
    onOpenFont: () -> Unit,
    onCancelFont: () -> Unit,
    onApplyFont: (Long?) -> Unit,
    onBackground: () -> Unit,
    onOpenCard: (CardEditor) -> Unit = {},
    onApplyCard: (CardAppearance) -> Unit = {},
    onCancelCard: () -> Unit = {}
) {
    // Keep settings controls readable even when a light custom color is selected.
    ExpenseAppTheme {
        if (state.cardEditor != null) {
            CardAppearanceDialog(state, background, onCancelCard, onApplyCard)
        } else if (state.editorOpen) {
            FontColorDialog(state, background, onCancelFont, onApplyFont)
        } else {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("设置") },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onOpenFont, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("选择字体颜色") }
                        OutlinedButton(onClick = { onOpenCard(CardEditor.BORDER) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("边框颜色") }
                        OutlinedButton(onClick = { onOpenCard(CardEditor.TRANSPARENCY) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("白框透明度") }
                        OutlinedButton(onClick = onBackground, modifier = Modifier.fillMaxWidth()) { Text("个性化背景") }
                    }
                },
                confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
            )
        }
    }
}

@Composable
private fun FontColorDialog(state: AppearanceState, background: BackgroundImage?, onCancel: () -> Unit, onApply: (Long?) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(fontColorHex(state.color ?: 0xFF292521L)) }
    var useDefault by rememberSaveable { mutableStateOf(state.color == null) }
    val parsed = parseFontColor(draft)
    var lastValid by rememberSaveable { mutableLongStateOf(state.color ?: 0xFF292521L) }
    fun select(color: Long) { draft = fontColorHex(color); lastValid = color; useDefault = false }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onCancel() },
        title = { Text("选择字体颜色") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                presets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (name, value) ->
                            Column(Modifier.width(56.dp).clickable(enabled = !state.busy) { select(value) }
                                .semantics { contentDescription = "预设颜色$name"; selected = parsed == value }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(32.dp).background(Color(value.toInt()), CircleShape)
                                    .border(if (parsed == value) 3.dp else 1.dp, if (parsed == value) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape))
                                Text(name, fontSize = 12.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(value = draft, onValueChange = {
                    draft = it; useDefault = false; parseFontColor(it)?.let { color -> lastValid = color }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("font-color-input"),
                    label = { Text("颜色编号 #RRGGBB") }, singleLine = true, isError = parsed == null)
                if (parsed == null) Text("请输入 # 加 6 位十六进制数字，例如 #1565C0", color = MaterialTheme.colorScheme.error)
                Text("效果预览")
                Box(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFFFFF9F2)).testTag("font-color-preview")) {
                    background?.let { BackgroundLayer(it) }
                    val previewColor = Color(lastValid.toInt())
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本月消费概览", color = previewColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("午餐 · 示例账单", color = previewColor, fontWeight = FontWeight.Bold)
                                Text("¥28.50", color = previewColor, fontSize = 20.sp)
                                Text("9月19日 12:30", color = if (useDefault) Color.Gray else previewColor, fontSize = 12.sp)
                            }
                        }
                    }
                }
                TextButton(enabled = !state.busy, onClick = { draft = "#292521"; lastValid = 0xFF292521L; useDefault = true }) { Text("恢复默认颜色") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = parsed != null && !state.busy, onClick = { onApply(if (useDefault) null else parsed) }) { Text("应用") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onCancel) { Text("取消") } }
    )
}
