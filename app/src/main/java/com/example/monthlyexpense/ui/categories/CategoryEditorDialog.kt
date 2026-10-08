package com.example.monthlyexpense.ui.categories

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.categories.ColorCodec

@Composable
internal fun CategoryEditorDialog(
    existing: ExpenseCategory?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
    onKeywords: () -> Unit = {}
) {
    val targetKey = existing?.key ?: "new"
    val initialColor = existing?.colorArgb ?: 0xFFEF6C45L
    val initialHsv = remember(targetKey, initialColor) { argbToHsv(initialColor) }
    var name by rememberSaveable(targetKey, key = "category-$targetKey-name") {
        mutableStateOf(existing?.name.orEmpty())
    }
    var hue by rememberSaveable(targetKey, key = "category-$targetKey-hue") {
        mutableFloatStateOf(initialHsv[0])
    }
    var saturation by rememberSaveable(targetKey, key = "category-$targetKey-saturation") {
        mutableFloatStateOf(initialHsv[1])
    }
    var brightness by rememberSaveable(targetKey, key = "category-$targetKey-brightness") {
        mutableFloatStateOf(initialHsv[2])
    }
    var alpha by rememberSaveable(targetKey, key = "category-$targetKey-alpha") {
        mutableFloatStateOf(((initialColor ushr 24) and 0xFF).toFloat() / 255f)
    }
    var hex by rememberSaveable(targetKey, key = "category-$targetKey-hex") {
        mutableStateOf(ColorCodec.format(initialColor))
    }

    fun selectedColor(): Long = AndroidColor.HSVToColor(
        (alpha * 255).toInt().coerceIn(0, 255),
        floatArrayOf(hue, saturation, brightness)
    ).toLong() and 0xFFFFFFFFL

    fun syncHex() {
        hex = ColorCodec.format(selectedColor())
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (existing == null) "添加分类" else "编辑分类") },
        text = {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.72f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (existing != null) TextButton(onKeywords, enabled = !saving) { Text("自动归类 · 管理关键词") }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    enabled = existing?.builtIn != true,
                    supportingText = {
                        if (existing?.builtIn == true) Text("内置分类名称固定，但颜色可以修改")
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).background(
                            Color(selectedColor().toInt()),
                            RoundedCornerShape(12.dp)
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { value ->
                            hex = value
                            ColorCodec.parse(value)?.let { parsed ->
                                val hsv = argbToHsv(parsed)
                                hue = hsv[0]
                                saturation = hsv[1]
                                brightness = hsv[2]
                                alpha = ((parsed ushr 24) and 0xFF).toFloat() / 255f
                            }
                        },
                        label = { Text("颜色 #AARRGGBB") },
                        isError = ColorCodec.parse(hex) == null,
                        supportingText = {
                            if (ColorCodec.parse(hex) == null) Text("请输入 #RRGGBB 或 #AARRGGBB")
                        },
                        singleLine = true
                    )
                }
                Text("色相 ${hue.toInt()}°")
                Slider(
                    hue,
                    { hue = it; syncHex() },
                    modifier = Modifier.testTag("category-hue-slider"),
                    valueRange = 0f..360f
                )
                Text("饱和度 ${(saturation * 100).toInt()}%")
                Slider(
                    saturation,
                    { saturation = it; syncHex() },
                    modifier = Modifier.testTag("category-saturation-slider"),
                    valueRange = 0f..1f
                )
                Text("明度 ${(brightness * 100).toInt()}%")
                Slider(
                    brightness,
                    { brightness = it; syncHex() },
                    modifier = Modifier.testTag("category-brightness-slider"),
                    valueRange = 0f..1f
                )
                Text("透明度 ${(alpha * 100).toInt()}%")
                Slider(
                    alpha,
                    { alpha = it; syncHex() },
                    modifier = Modifier.testTag("category-alpha-slider"),
                    valueRange = 0f..1f
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { ColorCodec.parse(hex)?.let { onSave(name, it) } },
                enabled = !saving && ColorCodec.parse(hex) != null && name.trim().isNotEmpty()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        }
    )
}

private fun argbToHsv(argb: Long): FloatArray = FloatArray(3).also {
    AndroidColor.colorToHSV(argb.toInt(), it)
}
