package com.example.monthlyexpense.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.monthlyexpense.notification.replay.ReplayResultPresentation

@Composable
internal fun NotificationReplayPopup(message: String, completed: Boolean, onDismiss: () -> Unit) {
    ReplayPopupContent(if (completed) "补记完成" else "补记提示", null, message, null, onDismiss)
}

@Composable
internal fun NotificationReplayPopup(
    result: ReplayResultPresentation,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    key(result.operationId) {
        ReplayPopupContent(result.title, result.connectionMessage, result.resultMessage,
            onRetry.takeIf { result.canRetry }, onDismiss)
    }
}

@Composable
private fun ReplayPopupContent(
    title: String,
    connectionMessage: String?,
    message: String,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Popup(alignment = Alignment.TopCenter, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        AnimatedVisibility(entered, enter = slideInVertically { -it } + fadeIn()) {
            Surface(modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 520.dp).fillMaxWidth().testTag("notification_replay_popup")
                .semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (connectionMessage != null) {
                        Text(connectionMessage, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (onRetry != null) {
                        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text("重试补记") }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
                }
            }
        }
    }
}
