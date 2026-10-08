package com.example.monthlyexpense.ui.settings

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.categories.ColorCodec
import com.example.monthlyexpense.ui.background.BackgroundImage
import com.example.monthlyexpense.ui.background.BackgroundLayer
import kotlin.math.roundToInt

@Composable
internal fun CardAppearanceDialog(state: AppearanceState, background: BackgroundImage?, onCancel: () -> Unit, onApply: (CardAppearance) -> Unit) {
    val borderEditor = state.cardEditor == CardEditor.BORDER
    var code by rememberSaveable { mutableStateOf(ColorCodec.format(state.cards.border.color)) }
    var borderColor by rememberSaveable { mutableLongStateOf(state.cards.border.color) }
    var transparency by rememberSaveable { mutableIntStateOf(state.cards.transparency) }
    val parsed = parseBorderColor(code)
    val preview = state.cards.copy(border = state.cards.border.copy(color = borderColor), transparency = transparency)
    AlertDialog(
        onDismissRequest = { if (!state.busy) onCancel() },
        title = { Text(if (borderEditor) "边框颜色" else "白框透明度") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (borderEditor) {
                    presets.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { (name, value) ->
                                Column(Modifier.width(56.dp).clickable(enabled = !state.busy) { code = fontColorHex(value); borderColor = value }
                                    .semantics { contentDescription = "边框预设$name" }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(30.dp).background(Color(value.toInt()), CircleShape)
                                        .border(if (parsed == value) 3.dp else 1.dp, if (parsed == value) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape))
                                    Text(name, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    TextButton(enabled = !state.busy, onClick = { code = "#00000000"; borderColor = 0L }) { Text("透明边框") }
                    OutlinedTextField(code, {
                        code = it; parseBorderColor(it)?.let { value -> borderColor = value }
                    }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("border-color-input"), singleLine = true,
                        label = { Text("色号 #RRGGBB 或 #AARRGGBB") }, isError = parsed == null)
                    if (parsed == null) Text("请输入有效色号；AA 为透明通道（00 全透明，FF 不透明）。", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("白框透明度：$transparency%", modifier = Modifier.testTag("card-transparency-value"))
                    Slider(value = transparency.toFloat(), onValueChange = { transparency = (it / 10).roundToInt().coerceIn(0, 10) * 10 },
                        valueRange = 0f..100f, steps = 9, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("card-transparency-slider"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0% 不透明", fontSize = 12.sp)
                        Text("100% 完全透明", fontSize = 12.sp)
                    }
                    Text("每 10% 一档，仅改变卡片底色。")
                }
                Text("效果预览")
                Box(Modifier.fillMaxWidth().height(160.dp).testTag("card-style-preview"), contentAlignment = Alignment.Center) {
                    if (background != null) BackgroundLayer(background) else Canvas(Modifier.fillMaxSize()) {
                        val step = 16.dp.toPx()
                        for (x in 0..(size.width / step).toInt()) for (y in 0..(size.height / step).toInt()) {
                            drawRect(if ((x + y) % 2 == 0) Color(0xFFD8DCE4) else Color(0xFFAAB6CC), Offset(x * step, y * step), Size(step, step))
                        }
                    }
                    CompositionLocalProvider(LocalCardAppearance provides preview) {
                        AppearanceCard(Modifier.fillMaxWidth().padding(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                val textColor = state.color?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.onSurface
                                Text("示例账单", color = textColor)
                                Text("¥28.50", color = textColor, fontSize = 24.sp)
                                Text("文字和边框独立显示", color = textColor, fontSize = 12.sp)
                            }
                        }
                    }
                }
                TextButton(enabled = !state.busy, onClick = {
                    if (borderEditor) { code = "#00000000"; borderColor = 0L } else transparency = 0
                }) { Text("恢复默认") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = !state.busy && (!borderEditor || parsed != null), onClick = { onApply(preview) }) { Text("应用") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onCancel) { Text("取消") } }
    )
}
