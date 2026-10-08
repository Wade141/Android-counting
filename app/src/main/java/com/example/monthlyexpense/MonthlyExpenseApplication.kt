package com.example.monthlyexpense

import android.app.Application
import com.example.monthlyexpense.ocrtest.clearAbandonedOcrImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MonthlyExpenseApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.example.monthlyexpense.notification.NotificationComponentReset.forContext(this).restorePendingEnable()
        val processStartedAt = System.currentTimeMillis()
        AppStrictMode.enable()
        container = AppContainer(this)
        com.example.monthlyexpense.notification.work.NotificationRecoveryWork.initialize(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // The cutoff protects pictures imported by this process while cleanup is queued.
            runCatching { clearAbandonedOcrImages(cacheDir, processStartedAt) }
        }
    }
}
