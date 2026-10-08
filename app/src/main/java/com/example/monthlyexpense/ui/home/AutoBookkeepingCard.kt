package com.example.monthlyexpense.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monthlyexpense.notification.NotificationListenerConnectionState

private val SuccessColor = Color(0xFF58BFA6)

@Composable
internal fun AutoBookkeepingCard(
    notificationState: NotificationListenerConnectionState,
    autoEnabled: Boolean,
    weChatEnabled: Boolean,
    alipayEnabled: Boolean,
    onAutoChanged: (Boolean) -> Unit,
    onWeChatChanged: (Boolean) -> Unit,
    onAlipayChanged: (Boolean) -> Unit,
    onRequestAccess: () -> Unit,
    onReplayRecent: () -> Unit,
    onRepairNotifications: () -> Unit = {},
    operationBusy: Boolean = false,
    operationProgress: String? = null
) {
    val notificationStatusText = when (notificationState) {
        NotificationListenerConnectionState.NOT_AUTHORIZED -> "未开启通知访问权限"
        NotificationListenerConnectionState.DISCONNECTED -> "通知接收已暂停，请重试"
        NotificationListenerConnectionState.RECOVERING -> "正在恢复通知接收…"
        NotificationListenerConnectionState.RECOVERY_FAILED -> "暂未恢复，请重试或检查系统设置"
        NotificationListenerConnectionState.CONNECTED -> "已授权，监听正常"
    }
    val notificationStatusColor = when (notificationState) {
        NotificationListenerConnectionState.CONNECTED -> SuccessColor
        NotificationListenerConnectionState.NOT_AUTHORIZED,
        NotificationListenerConnectionState.RECOVERING,
        NotificationListenerConnectionState.RECOVERY_FAILED,
        NotificationListenerConnectionState.DISCONNECTED -> Color(0xFFB45309)
    }
    com.example.monthlyexpense.ui.settings.AppearanceCard(cornerRadius = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            SettingSwitchRow("自动记账", autoEnabled, onAutoChanged, "仅识别可靠的微信和支付宝付款通知")
            HorizontalDivider(color = Color(0xFFEEEAE5))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通知监听状态", fontWeight = FontWeight.SemiBold)
                    Text(notificationStatusText, color = notificationStatusColor, fontSize = 13.sp)
                }
                TextButton(onClick = onRequestAccess) {
                    Text(
                        if (notificationState == NotificationListenerConnectionState.NOT_AUTHORIZED) {
                            "去授权"
                        } else {
                            "系统设置"
                        }
                    )
                }
            }
            HorizontalDivider(color = Color(0xFFEEEAE5))
            SettingSwitchRow("识别微信付款", weChatEnabled, onWeChatChanged, enabled = autoEnabled)
            SettingSwitchRow("识别支付宝付款", alipayEnabled, onAlipayChanged, enabled = autoEnabled)
            HorizontalDivider(color = Color(0xFFEEEAE5))
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("漏记恢复", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text(
                    "重新扫描系统仍保留的最近 30 分钟通知",
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = com.example.monthlyexpense.ui.settings.secondaryTextColor(),
                    maxLines = 1
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRepairNotifications,
                    enabled = autoEnabled && !operationBusy &&
                        notificationState != NotificationListenerConnectionState.NOT_AUTHORIZED &&
                        notificationState != NotificationListenerConnectionState.RECOVERING) {
                    Text("修复通知接收")
                }
                TextButton(
                    onClick = onReplayRecent,
                    enabled = autoEnabled && !operationBusy &&
                        notificationState != NotificationListenerConnectionState.NOT_AUTHORIZED &&
                        notificationState != NotificationListenerConnectionState.RECOVERING
                ) {
                    Text(if (operationBusy) "正在处理…" else when (notificationState) {
                        NotificationListenerConnectionState.RECOVERING -> "正在恢复…"
                        NotificationListenerConnectionState.DISCONNECTED,
                        NotificationListenerConnectionState.RECOVERY_FAILED -> "重试恢复"
                        else -> "补记最近30分钟"
                    })
                }
            }
            operationProgress?.let {
                Text(it, fontSize = 13.sp, color = com.example.monthlyexpense.ui.settings.secondaryTextColor(),
                    modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.Unspecified else Color.Gray)
            subtitle?.let { Text(it, fontSize = 12.sp, color = com.example.monthlyexpense.ui.settings.secondaryTextColor()) }
        }
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}
