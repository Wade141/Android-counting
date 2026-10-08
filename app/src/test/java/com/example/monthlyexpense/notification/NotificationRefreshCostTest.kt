package com.example.monthlyexpense.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.repository.NotificationEventRepository
import com.example.monthlyexpense.notification.rules.PaymentRuleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class NotificationRefreshCostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun fixedScenariosCompareFullRefreshPolicyWithScopedPolicy() = runBlocking {
        for ((scenario, size) in listOf("single" to 1, "burst" to 20, "duplicate" to 100, "review" to 100)) {
            val costs = mutableListOf<Map<PipelineMetric, MetricSample>>()
            for (scoped in listOf(false, true)) {
                context.deleteDatabase("expenses.db")
                ExpenseDatabaseHelper(context).use { db ->
                    val events = NotificationEventRepository(db)
                    val repo = ExpenseRepository(db, BudgetSettings(context), AutoBookkeepingSettings(context))
                    val rules = PaymentRuleStore(context)
                    val date = LocalDate.now()
                    val zone = ZoneId.systemDefault()
                    var home = repo.loadHomeSnapshot(date, zone)
                    val base = date.atStartOfDay(zone).toInstant().toEpochMilli() + 60000
                    val raws = (0 until size).map { i -> RawNotification(PaymentPackages.WECHAT, "微信支付",
                        if (scenario == "review") "成功转账20元" else "已支付20元", "", emptyList(),
                        base + (if (scenario == "duplicate") 0 else i * 5000L),
                        if (scenario == "duplicate") "duplicate" else "fixture-$i", null) }
                    if (scenario == "duplicate") {
                        val seed = PaymentDecisionEngine().evaluate(raws.first())
                        events.stage(seed); events.process(seed.eventId)
                    }
                    NotificationMetrics.reset()
                    raws.forEach { raw ->
                        val decision = PaymentDecisionEngine((if (scoped) rules else PaymentRuleStore(context)).snapshot()).evaluate(raw)
                        val staged = events.stageForScheduling(decision)
                        if (scoped) {
                            if (staged.shouldSchedule) {
                                val outcome = events.processWithChanges(decision.eventId)
                                home = repo.refreshHomeSnapshot(date, zone, home, outcome.changedDomains)
                            }
                        } else {
                            events.process(decision.eventId)
                            home = repo.loadHomeSnapshot(date, zone)
                        }
                    }
                    val result = NotificationMetrics.snapshot()
                    costs += result
                    fun count(metric: PipelineMetric) = result[metric]?.count ?: 0L
                    val expectedLedger = if (scenario == "duplicate" || scenario == "review") 0 else size
                    assertEquals(if (scoped) expectedLedger.toLong() else size.toLong(), count(PipelineMetric.LEDGER_READ))
                    assertEquals(if (scoped) 0L else size.toLong(), count(PipelineMetric.SETTINGS_READ))
                    assertEquals(if (scoped) 1L else size.toLong(), count(PipelineMetric.RULE_LOAD))
                    assertEquals(if (scenario == "review") size else 0, events.pendingCount())
                    db.readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(amount_cents), 0) FROM expenses", null).use {
                        it.moveToFirst()
                        val expected = when (scenario) { "duplicate" -> 1; "review" -> 0; else -> size }
                        assertEquals(expected, it.getInt(0)); assertEquals(expected * 2000L, it.getLong(1))
                    }
                    println("refresh-cost scenario=$scenario scoped=$scoped metrics=$result")
                }
            }
            assertEquals(2, costs.size)
        }
        context.deleteDatabase("expenses.db")
        Unit
    }
}
