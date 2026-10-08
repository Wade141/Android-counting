package com.example.monthlyexpense.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

object NotificationAccessChecker {
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val listener = ComponentName(context, PaymentNotificationListener::class.java)
            return manager.isNotificationListenerAccessGranted(listener)
        }
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
}
