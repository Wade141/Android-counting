package com.example.monthlyexpense.ui.forms

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.util.Locale
import kotlin.math.*

internal fun wrapClockMinutes(minutes: Int): Int = Math.floorMod(minutes, 1440)
internal fun clockAngleDelta(previous: Float, next: Float): Float =
    ((next - previous + 540f) % 360f) - 180f

private fun clockText(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
private fun parseClockText(text: String): Int? {
    if (!text.matches(Regex("[0-9]{1,2}:[0-9]{2}"))) return null
    val (hour, minute) = text.split(':').map { it.toInt() }
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

@Composable
internal fun ClockTimeDialog(initialTime: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(initialTime.hour * 60 + initialTime.minute) }
    var draft by rememberSaveable { mutableStateOf(clockText(minutes)) }
    val parsed = parseClockText(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改入账时间") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { value ->
                        draft = value
                        parseClockText(value)?.let { minutes = it }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("clock-time-input"),
                    label = { Text("24 小时制 HH:mm") },
                    singleLine = true,
                    isError = parsed == null,
                    textStyle = TextStyle(fontSize = 32.sp, textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                if (parsed == null) Text("请输入 00:00–23:59 的时间", color = MaterialTheme.colorScheme.error)
                LinkedClock(minutes) { value ->
                    minutes = value
                    draft = clockText(value)
                }
                Text("拖动短时针或长分针，也可直接输入时间。日期保持不变。", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                parsed?.let { onConfirm(LocalTime.of(it / 60, it % 60)) }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun handEnd(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return center + Offset(sin(radians).toFloat() * radius, -cos(radians).toFloat() * radius)
}

private fun segmentDistance(point: Offset, start: Offset, end: Offset): Float {
    val line = end - start
    val relative = point - start
    val fraction = ((relative.x * line.x + relative.y * line.y) /
        (line.x * line.x + line.y * line.y)).coerceIn(0f, 1f)
    return (point - (start + line * fraction)).getDistance()
}

@Composable
private fun LinkedClock(minutes: Int, onChange: (Int) -> Unit) {
    val currentMinutes by rememberUpdatedState(minutes)
    val change by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val face = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("linked-clock")
        .semantics { contentDescription = "可拖动的时针和分针"; stateDescription = clockText(minutes) }
        .pointerInput(Unit) {
            var lastAngle = 0f
            var accumulated = 0f
            var startMinutes = 0
            var minuteHand = true
            fun angle(point: Offset): Float {
                val center = Offset(size.width / 2f, size.height / 2f)
                return ((Math.toDegrees(atan2((point.x - center.x).toDouble(), -(point.y - center.y).toDouble())) + 360) % 360).toFloat()
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                val point = down.position
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = min(size.width, size.height) / 2f
                val minuteTip = handEnd(center, radius * .74f, currentMinutes % 60 * 6f)
                val hourTip = handEnd(center, radius * .48f, currentMinutes % 720 * .5f)
                val hourTipDistance = (point - hourTip).getDistance()
                val minuteTipDistance = (point - minuteTip).getDistance()
                minuteHand = when {
                    hourTipDistance < radius * .06f && hourTipDistance < minuteTipDistance -> false
                    minuteTipDistance < radius * .06f -> true
                    else -> segmentDistance(point, center, minuteTip) < segmentDistance(point, center, hourTip)
                }
                startMinutes = currentMinutes
                accumulated = 0f
                lastAngle = angle(point)
                down.consume()
                drag(down.id) { event ->
                    val next = angle(event.position)
                    accumulated += clockAngleDelta(lastAngle, next)
                    lastAngle = next
                    val deltaMinutes = accumulated / if (minuteHand) 6f else .5f
                    change(wrapClockMinutes(startMinutes + deltaMinutes.roundToInt()))
                    event.consume()
                }
            }
        }) {
        val radius = size.minDimension / 2f
        drawCircle(face, radius)
        for (tick in 0 until 60) {
            drawLine(ink.copy(alpha = if (tick % 5 == 0) .7f else .3f),
                handEnd(center, radius * .94f, tick * 6f),
                handEnd(center, radius * if (tick % 5 == 0) .86f else .9f, tick * 6f),
                strokeWidth = if (tick % 5 == 0) 2.dp.toPx() else 1.dp.toPx())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb((ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
            textAlign = Paint.Align.CENTER
            textSize = radius * .14f
        }
        for (hour in 1..12) {
            val point = handEnd(center, radius * .75f, hour * 30f)
            drawContext.canvas.nativeCanvas.drawText(hour.toString(), point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        val hourTip = handEnd(center, radius * .48f, minutes % 720 * .5f)
        val minuteTip = handEnd(center, radius * .74f, minutes % 60 * 6f)
        drawLine(ink, center, hourTip, 7.dp.toPx(), StrokeCap.Round)
        drawLine(accent, center, minuteTip, 4.dp.toPx(), StrokeCap.Round)
        drawCircle(ink, 7.dp.toPx(), hourTip)
        drawCircle(accent, 6.dp.toPx(), minuteTip)
        drawCircle(ink, 6.dp.toPx())
    }
}
