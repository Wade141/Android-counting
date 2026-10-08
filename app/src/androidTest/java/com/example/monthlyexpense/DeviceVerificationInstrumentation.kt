package com.example.monthlyexpense

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.notification.*
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.repository.*
import com.example.monthlyexpense.notification.rules.PaymentRuleStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Opt-in device verification. All SQLite mutations use a uniquely named disposable database. */
class DeviceVerificationInstrumentation : Instrumentation() {
    private var searchBenchmarkOnly = false
    override fun onCreate(arguments: Bundle?) {
        searchBenchmarkOnly = arguments?.getString("searchBenchmark") == "true"
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        val prefix = "device-verification-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                baseContext.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
        }
        val report = JSONObject()
        if (searchBenchmarkOnly) {
            runSearchBenchmark(isolated, prefix)
            return
        }
        val started = System.nanoTime()
        var resultCode = Activity.RESULT_CANCELED
        try {
            val cipher = com.example.monthlyexpense.notification.intake.NotificationPayloadCipher(targetContext)
            val plaintext = "notification-device-roundtrip".toByteArray()
            val encrypted = cipher.encrypt(plaintext, "isolated-device-check")
            check(cipher.decrypt(encrypted, "isolated-device-check").contentEquals(plaintext))
            check(runCatching { cipher.decrypt(encrypted, "wrong-identity") }.isFailure)
            val damaged = encrypted.copy(ciphertext = encrypted.ciphertext.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            })
            check(runCatching { cipher.decrypt(damaged, "isolated-device-check") }.isFailure)
            report.put("notificationKeystoreRoundtrip", "passed").put("notificationCipherTamperRejected", true)
            ExpenseDatabaseHelper(isolated).use { db -> runBlocking {
                check(db.writableDatabase.path.endsWith(prefix + "expenses.db")) { "Database isolation failed" }
                val publisher = DataChangePublisher()
                val events = NotificationEventRepository(db, publisher::publish)
                val engine = PaymentDecisionEngine()
                val now = System.currentTimeMillis()
                fun decision(index: Int, text: String = "已支付20元") = engine.evaluate(RawNotification(
                    PaymentPackages.WECHAT, "微信支付", text, "", emptyList(), now + index * 5000L, "device-$index", null))
                val first = decision(0)
                repeat(20) { i ->
                    val d = decision(i)
                    check(events.stageForScheduling(d).shouldSchedule)
                    check(events.processWithChanges(d.eventId).changedDomains == setOf(DataDomain.LEDGER))
                }
                check(publisher.revisions.value.ledger == 20L)
                val before = publisher.revisions.value
                repeat(100) {
                    check(!events.stageForScheduling(first).shouldSchedule)
                    check(events.processWithChanges(first.eventId).changedDomains.isEmpty())
                }
                check(publisher.revisions.value == before)
                db.readableDatabase.rawQuery("SELECT COUNT(*), SUM(amount_cents) FROM expenses", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 20 && it.getLong(1) == 40000L)
                }
                report.put("consecutivePayments", 20).put("duplicateReplays", 100).put("duplicateInvalidations", 0)

                repeat(503) { i ->
                    val d = decision(1000 + i, "成功转账20元").copy(observedAt = now)
                    events.stage(d)
                }
                val page = events.stagedPage()
                val tail = events.stagedPage(page.last())
                check(page.size == 500 && tail.size == 3)
                (page + tail).forEach { check(events.process(it.eventId) == StoreResult.REVIEW) }
                check(events.pendingCount() == 503)
                val pending = events.pendingPage()
                val next = events.pendingPage(pending.last())
                check(pending.size == 50 && (pending + next).map { it.eventId }.toSet().size == 100)
                check(publisher.revisions.value.ledger == 20L)
                val id = db.readableDatabase.rawQuery("SELECT id FROM expenses LIMIT 1", null).use { it.moveToFirst(); it.getLong(0) }
                val linked = events.linkWithChanges(pending.first().eventId, id)
                check(linked.result == StoreResult.DUPLICATE && linked.changedDomains == setOf(DataDomain.REVIEW))
                check(events.linkWithChanges(pending.first().eventId, id).changedDomains.isEmpty())
                check(events.ignoreWithChanges(pending[1].eventId).changedDomains == setOf(DataDomain.REVIEW))
                check(events.confirmWithChanges(pending[2].eventId, 2000, "设备测试").changedDomains == setOf(DataDomain.LEDGER, DataDomain.REVIEW))
                report.put("stagedRecovery", 503).put("pendingPageSize", 50).put("reviewMutations", "passed")

                val repo = ExpenseRepository(db, BudgetSettings(targetContext), AutoBookkeepingSettings(targetContext))
                val date = LocalDate.now()
                val zone = ZoneId.systemDefault()
                var home = repo.loadHomeSnapshot(date, zone)
                NotificationMetrics.reset()
                repeat(20) { home = repo.refreshHomeSnapshot(date, zone, home, setOf(DataDomain.LEDGER)) }
                check(NotificationMetrics.snapshot()[PipelineMetric.LEDGER_READ]?.count == 20L)
                check(NotificationMetrics.snapshot()[PipelineMetric.SETTINGS_READ] == null)
                repeat(100) { check(home === repo.refreshHomeSnapshot(date, zone, home, setOf(DataDomain.REVIEW))) }
                check(NotificationMetrics.snapshot()[PipelineMetric.LEDGER_READ]?.count == 20L)
                report.put("ledgerReads", 20).put("unrelatedSettingsReads", 0).put("reviewLedgerReads", 0)

                val store = PaymentRuleStore(targetContext)
                NotificationMetrics.reset()
                val rules = store.snapshot()
                repeat(100) { check(rules === store.snapshot()) }
                check(NotificationMetrics.snapshot()[PipelineMetric.RULE_LOAD]?.count == 1L)
                report.put("ruleSnapshotRequests", 101).put("ruleLoads", 1)
            } }
            report.put("status", "passed").put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.javaClass.simpleName + ": " + error.message)
        } finally {
            targetContext.deleteDatabase(prefix + "expenses.db")
        }
        finish(resultCode, Bundle().apply { putString("verification", report.toString()) })
    }

    private fun runSearchBenchmark(isolated: Context, prefix: String) {
        val report = JSONObject().put("device", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE).put("measurement", "DAO first page and summary; excludes debounce/UI")
        val results = org.json.JSONArray()
        var resultCode = Activity.RESULT_CANCELED
        try {
            for (count in listOf(10_000, 50_000)) {
                try {
                    ExpenseDatabaseHelper(isolated).use { db ->
                        check(db.writableDatabase.path.endsWith(prefix + "expenses.db"))
                        ExpenseSearchPerformanceFixture.seed(db, count)
                        ExpenseSearchPerformanceFixture.measure(db).forEach { row ->
                            results.put(JSONObject().put("rows", count).put("case", row.case)
                                .put("coldMs", row.coldMs).put("p50Ms", row.p50Ms).put("p95Ms", row.p95Ms)
                                .put("loaded", row.loaded).put("matches", row.matches))
                        }
                    }
                } finally { targetContext.deleteDatabase(prefix + "expenses.db") }
            }
            report.put("status", "passed").put("results", results)
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
        finish(resultCode, Bundle().apply { putString("searchBenchmark", report.toString()) })
    }
}
