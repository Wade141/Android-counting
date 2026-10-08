package com.example.monthlyexpense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import com.example.monthlyexpense.notification.PaymentNotificationListener
import com.example.monthlyexpense.ui.ExpenseAppRoute

class MainActivity : ComponentActivity() {
    private val refreshSignal = mutableIntStateOf(0)
    private val connectionSignal = mutableIntStateOf(0)
    private val expenseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PaymentNotificationListener.ACTION_LISTENER_STATE_CHANGED) connectionSignal.intValue++
            else refreshSignal.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MonthlyExpenseApplication).container
        setContent {
            ExpenseAppRoute(
                repository = container.expenseRepository,
                backupManager = container.backupManager,
                refreshSignal = refreshSignal.intValue,
                persistenceCoordinator = container.persistenceCoordinator,
                categoryRuleRepository = container.categoryRuleRepository,
                dataChanges = container.dataChanges,
                connectionSignal = connectionSignal.intValue
            )
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            expenseReceiver,
            IntentFilter().apply {
                addAction(PaymentNotificationListener.ACTION_EXPENSE_ADDED)
                addAction(PaymentNotificationListener.ACTION_LISTENER_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        (application as MonthlyExpenseApplication).container.notifications.onForeground()
        refreshSignal.intValue++
    }

    override fun onStop() {
        unregisterReceiver(expenseReceiver)
        super.onStop()
    }
}
