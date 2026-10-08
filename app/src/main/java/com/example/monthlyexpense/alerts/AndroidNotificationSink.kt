package com.example.monthlyexpense.alerts

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.monthlyexpense.MainActivity
import com.example.monthlyexpense.R

class AndroidNotificationSink(private val context: Context) : NotificationSink {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        NoticeKind.entries.forEach { kind ->
            manager.createNotificationChannel(NotificationChannel(kind.channelId, kind.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = if (kind == NoticeKind.RECORDED) "支出保存成功后提醒，点击直接编辑该笔账单" else "月度预算使用情况提醒"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
    }

    fun enabled(kind: NoticeKind): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(kind.channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun post(notice: AppNotice): NoticeDelivery = try {
        ensureChannels()
        if (!enabled(notice.kind)) NoticeDelivery.DISABLED else {
            val intent = when (val destination=notice.destination) {
                is NoticeDestination.EditExpense -> Intent(context, RecordedExpenseActivity::class.java).setData(destination.target.toUri())
                NoticeDestination.Home -> Intent(context, MainActivity::class.java).setData(
                    Uri.Builder().scheme("monthlyexpense").authority("notice").appendPath(notice.kind.name).appendPath(notice.key).build())
            }.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, notice.kind.channelId)
                .setSmallIcon(R.drawable.ic_recorded_notification)
                .setContentTitle(notice.title).setContentText(notice.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .apply { if (notice.destination is NoticeDestination.EditExpense) addAction(0,"编辑这笔",pending) }
                .build()
            manager.notify("${notice.kind.name}:${notice.key}", 1, notification)
            NoticeDelivery.POSTED
        }
    } catch (_: RuntimeException) { NoticeDelivery.FAILED }
}
