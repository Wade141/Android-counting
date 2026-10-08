package com.example.monthlyexpense.ui.background

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
internal fun BackgroundLayer(image: BackgroundImage, modifier: Modifier = Modifier) {
    val bitmap = remember(image.bitmap) { image.bitmap.asImageBitmap() }
    Canvas(modifier.fillMaxSize().clipToBounds().background(Color(0xFFFFF9F2))) {
        if (size.width > 0 && size.height > 0) {
            val crop = backgroundCrop(bitmap.width, bitmap.height, size.width.toInt(), size.height.toInt(), image.x, image.y)
            drawImage(bitmap, dstOffset = IntOffset(crop.left, crop.top),
                dstSize = IntSize(crop.width, crop.height), filterQuality = FilterQuality.High)
            drawRect(Color(0xFFFFF9F2).copy(alpha = .25f))
        }
    }
}

@Composable
internal fun BackgroundEditor(
    state: BackgroundState,
    onChoose: () -> Unit,
    onClose: () -> Unit,
    onCancelPreview: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit
) {
    if (!state.editorOpen) return
    BackHandler { if (!state.busy) { if (state.draft != null) onCancelPreview() else onClose() } }
    val image = state.draft
    if (image == null) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) onClose() },
            title = { Text("个性化背景") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择相册图片，拖动预览调整显示区域后应用。")
                    if (state.busy) CircularProgressIndicator()
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = !state.busy && state.saved != null, onClick = onReset) { Text("恢复默认背景") }
                }
            },
            confirmButton = { TextButton(enabled = !state.busy, onClick = onChoose) { Text("从相册选择") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text("关闭") } }
        )
    } else {
        val currentImage by rememberUpdatedState(image)
        val move by rememberUpdatedState(onMove)
        Box(Modifier.fillMaxSize().background(Color(0xFFFFF9F2)).testTag("background-preview")) {
            BackgroundLayer(image, Modifier
                .semantics { contentDescription = "拖动图片调整背景取景" }
                .pointerInput(image.bitmap, state.busy) {
                    var x = currentImage.x
                    var y = currentImage.y
                    if (!state.busy) detectDragGestures(onDragStart = {
                        x = currentImage.x
                        y = currentImage.y
                    }) { change, amount ->
                        val current = currentImage
                        val crop = backgroundCrop(current.bitmap.width, current.bitmap.height, size.width, size.height, current.x, current.y)
                        val overflowX = crop.width - size.width
                        val overflowY = crop.height - size.height
                        x = if (overflowX == 0) .5f else (x - amount.x / overflowX).coerceIn(0f, 1f)
                        y = if (overflowY == 0) .5f else (y - amount.y / overflowY).coerceIn(0f, 1f)
                        move(x, y)
                        change.consume()
                    }
                })
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("背景预览", style = MaterialTheme.typography.titleLarge)
                    Text("拖动图片选择显示区域，超出屏幕的部分会自动裁剪。")
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                Column(Modifier.padding(16.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !state.busy, onClick = onCancelPreview) { Text("取消") }
                        Button(enabled = !state.busy, onClick = onApply) { Text("应用背景") }
                    }
                }
            }
        }
    }
}
