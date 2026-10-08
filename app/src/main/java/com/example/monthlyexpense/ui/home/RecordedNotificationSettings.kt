package com.example.monthlyexpense.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.monthlyexpense.alerts.*

@Composable
internal fun RecordedNotificationSettings() {
    val context=LocalContext.current
    val sink=remember(context) {AndroidNotificationSink(context).also {it.ensureChannels()}}
    var enabled by remember {mutableStateOf(sink.enabled(NoticeKind.RECORDED))}
    val prefs=remember(context) {context.getSharedPreferences("app_notification_settings",0)}
    var requested by rememberSaveable {mutableStateOf(prefs.getBoolean("permission_requested",false))}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enabled=sink.enabled(NoticeKind.RECORDED) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,sink) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_RESUME) enabled=sink.enabled(NoticeKind.RECORDED)}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    HorizontalDivider(Modifier.padding(vertical=8.dp))
    Text("记账成功提醒",style=MaterialTheme.typography.titleSmall)
    Text(if(enabled) "已开启：保存后发送通知，点击直接编辑这笔账单。" else "未开启：账单仍会保存，但不会发送成功提醒。",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={
        if(Build.VERSION.SDK_INT>=33 && !requested && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requested=true;prefs.edit().putBoolean("permission_requested",true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val appEnabled=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            val intent=Intent(if(appEnabled) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
            if(appEnabled) intent.putExtra(Settings.EXTRA_CHANNEL_ID,NoticeKind.RECORDED.channelId)
            context.startActivity(intent)
        }
    }) {Text(if(enabled) "管理提醒" else "开启提醒")}
}
