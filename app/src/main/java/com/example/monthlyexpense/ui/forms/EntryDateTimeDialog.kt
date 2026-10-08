package com.example.monthlyexpense.ui.forms

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun EntryDateTimeDialog(
    initialDate: LocalDate,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalTime) -> Unit
) {
    var dateText by rememberSaveable { mutableStateOf(initialDate.toString()) }
    var timeText by rememberSaveable { mutableStateOf(initialTime.toString()) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var showClock by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    if (showClock) {
        ClockTimeDialog(
            initialTime = LocalTime.parse(timeText),
            onDismiss = { showClock = false },
            onConfirm = { time -> timeText = time.toString(); showClock = false }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("修改入账时间") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("入账日期")
                            Text(dateText)
                        }
                        TextButton(onClick = { showCalendar = true }) { Text("修改日期") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("入账时间")
                            Text(LocalTime.parse(timeText).format(DateTimeFormatter.ofPattern("HH:mm")))
                        }
                        TextButton(onClick = { showClock = true }) { Text("修改时间") }
                    }
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("date-time-confirm"), onClick = {
                    onConfirm(LocalDate.parse(dateText), LocalTime.parse(timeText))
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.testTag("date-time-cancel"), onClick = onDismiss) { Text("取消") }
            }
        )
    }

    if (showCalendar) {
        DisposableEffect(context) {
            val date = LocalDate.parse(dateText)
            val dialog = DatePickerDialog(context, { _, year, month, day ->
                dateText = LocalDate.of(year, month + 1, day).toString()
            }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
                setOnDismissListener { showCalendar = false }
                show()
            }
            onDispose {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
    }
}
