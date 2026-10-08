package com.example.monthlyexpense.classification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseCategoryResolverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var helper: ExpenseDatabaseHelper
    @Before fun setup() { context.deleteDatabase("expenses.db"); helper = ExpenseDatabaseHelper(context) }
    @After fun close() { helper.close(); context.deleteDatabase("expenses.db") }
    private fun addRule(key: String, word: String) {
        assertTrue(helper.categoryRuleDao.saveRuleSet(CategoryRuleSet(key, true, keywords = listOf(CategoryKeywordRule(key, word, word))), 0) is RuleSaveResult.Success)
    }
    @Test fun automaticEntryUsesFreshRulesAndPreservesDeduplication() {
        addRule("FOOD", "咖啡")
        assertTrue(helper.expenseDao.addAutomaticExpense(1200, "OTHER", "咖啡店", System.currentTimeMillis(), ExpenseSource.WECHAT_AUTO, "咖啡店", "n1"))
        assertFalse(helper.expenseDao.addAutomaticExpense(1200, "OTHER", "咖啡店", System.currentTimeMillis(), ExpenseSource.WECHAT_AUTO, "咖啡店", "n1"))
        assertEquals("FOOD", helper.expenseDao.currentMonthRecords().single().category.key)
    }
    @Test fun conflictStillRecordsAmountAndManualOtherWins() {
        addRule("FOOD", "咖啡"); addRule("SHOPPING", "咖啡机")
        helper.expenseDao.addAutomaticExpense(10000, "OTHER", "咖啡机店", System.currentTimeMillis(), ExpenseSource.ALIPAY_AUTO, "咖啡机店", "n2")
        val record = helper.expenseDao.currentMonthRecords().single()
        assertEquals(10000L, record.amountCents)
        assertEquals("OTHER", record.category.key)
        assertEquals("CONFLICT", helper.readableDatabase.rawQuery("SELECT classification_origin FROM expenses", null).use { it.moveToFirst(); it.getString(0) })
        helper.expenseDao.addExpense(1200, "OTHER", "咖啡店", "")
        assertEquals("OTHER", helper.expenseDao.currentMonthRecords().first().category.key)
    }
    @Test fun manualCorrectionClearsOldKeywordReasonButNoteEditPreservesIt() {
        addRule("FOOD", "咖啡")
        helper.expenseDao.addAutomaticExpense(1200, "OTHER", "咖啡店", System.currentTimeMillis(), ExpenseSource.WECHAT_AUTO, "咖啡店", "n3")
        val id = helper.expenseDao.currentMonthRecords().single().id
        helper.expenseDao.updateExpense(id, "咖啡店", "新备注", "FOOD")
        fun origin() = helper.readableDatabase.rawQuery("SELECT classification_origin FROM expenses", null).use { it.moveToFirst(); it.getString(0) }
        assertEquals("KEYWORD", origin())
        helper.expenseDao.updateExpense(id, "咖啡店", "新备注", "OTHER")
        assertEquals("MANUAL", origin())
        helper.readableDatabase.rawQuery("SELECT classification_match_json FROM expenses", null).use { it.moveToFirst(); assertTrue(it.isNull(0)) }
    }
    @Test fun conflictNeverFallsBackToHistory() {
        val rules = listOf("FOOD" to "咖啡", "SHOPPING" to "咖啡机").map { (key, word) -> CategoryRuleSet(key, true, keywords = listOf(CategoryKeywordRule(key, word, word))) }
        assertEquals(ClassificationOrigin.CONFLICT, ExpenseCategoryResolver.resolve(ClassificationInput("咖啡机", ""), rules, historyCategoryKey = "FOOD").origin)
    }
    @Test fun manualDefaultMatchesNameButExplicitOtherDoesNotAndEvidenceSurvivesRuleChange() {
        addRule("FOOD", "咖啡")
        helper.expenseDao.addExpense(100, "OTHER", "咖啡", "", categoryExplicit = false)
        val before = helper.expenseDao.currentMonthRecords().single()
        assertEquals("FOOD", before.category.key)
        assertEquals("咖啡", before.classificationHits.single().keyword)
        val rule = helper.categoryRuleDao.loadRuleSets().single()
        helper.categoryRuleDao.saveRuleSet(rule.copy(enabled = false, keywords = emptyList()), rule.revision)
        assertEquals(before, helper.expenseDao.currentMonthRecords().single())
        helper.expenseDao.updateExpense(before.id, before.name, "", "FOOD", categoryExplicit = true)
        assertEquals(ClassificationOrigin.MANUAL, helper.expenseDao.currentMonthRecords().single().classificationOrigin)
        assertTrue(helper.expenseDao.currentMonthRecords().single().classificationHits.isEmpty())
    }
    @Test fun keywordAndEvidenceSurviveBackupRoundTrip() {
        addRule("FOOD", "咖啡")
        helper.expenseDao.addExpense(100, "OTHER", "咖啡", "", categoryExplicit = false)
        val backup = com.example.monthlyexpense.backup.ExpenseBackupDocument(1, helper.backupDao.exportBackupDatabase(),
            com.example.monthlyexpense.backup.ExpenseBackupSettings(0, false, true, true))
        val decoded = com.example.monthlyexpense.backup.ExpenseBackupJson.decode(com.example.monthlyexpense.backup.ExpenseBackupJson.encode(backup))
        assertEquals(backup, decoded)
        assertTrue(helper.backupDao.restoreBackupDatabase(decoded.database))
        assertEquals(ClassificationOrigin.KEYWORD, helper.expenseDao.currentMonthRecords().single().classificationOrigin)
    }
    @Test fun notificationNormalReviewAndQuarantineAllResolveRulesWithoutChangingEventIdempotency() {
        addRule("FOOD", "咖啡")
        val events = com.example.monthlyexpense.notification.repository.NotificationEventRepository(helper)
        val engine = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine()
        val base = engine.evaluate(com.example.monthlyexpense.notification.RawNotification(
            com.example.monthlyexpense.notification.PaymentPackages.WECHAT, "微信支付", "向咖啡店付款成功，金额20元", "", emptyList(), System.currentTimeMillis(), "category-case", null))
        for ((index, source) in com.example.monthlyexpense.notification.parser.PaymentSource.entries.withIndex()) {
            val d = base.copy(eventId = "category-normal-$index", notificationIdentity = "category-$index", source = source, legacyEventId = null, legacyNotificationKey = null)
            events.stage(d)
            assertEquals(com.example.monthlyexpense.notification.repository.StoreResult.INSERTED, events.process(d.eventId))
            assertEquals(com.example.monthlyexpense.notification.repository.StoreResult.DUPLICATE, events.process(d.eventId))
        }
        val review = base.copy(eventId = "category-review", notificationIdentity = "review", merchant = null,
            legacyEventId = null, legacyNotificationKey = null, action = com.example.monthlyexpense.notification.decision.DecisionAction.REVIEW)
        events.stage(review); events.process(review.eventId)
        assertEquals(com.example.monthlyexpense.notification.repository.StoreResult.INSERTED, events.confirm(review.eventId, 2000, "咖啡"))
        val quarantined = review.copy(eventId = "category-quarantine", notificationIdentity = "quarantine")
        val generation = helper.backupDao.notificationProtocol.bookGeneration()
        assertEquals(com.example.monthlyexpense.notification.repository.StoreResult.INSERTED, events.confirmQuarantined(quarantined, generation, 1, 2000, "咖啡").result)
        assertEquals(com.example.monthlyexpense.notification.repository.StoreResult.DUPLICATE, events.confirmQuarantined(quarantined, generation, 1, 2000, "咖啡").result)
        assertEquals(4, helper.expenseDao.currentMonthRecords().size)
        assertTrue(helper.expenseDao.currentMonthRecords().all { it.category.key == "FOOD" && it.classificationOrigin == ClassificationOrigin.KEYWORD })
    }
    @Test fun screenshotResolvesFreshRulesAndNeverReplacesExplicitOther() = kotlinx.coroutines.runBlocking {
        val coordinator = com.example.monthlyexpense.data.ForegroundPersistenceCoordinator(com.example.monthlyexpense.data.ForegroundSettingsBaseline(false, true, true))
        val store = com.example.monthlyexpense.screenshot.ScreenshotExpenseStore(helper, coordinator)
        helper.expenseDao.addExpense(100, "TRAVEL", "咖啡机", "")
        addRule("FOOD", "咖啡"); addRule("SHOPPING", "咖啡机")
        assertEquals(ClassificationOrigin.CONFLICT, store.suggestClassification("咖啡机", "咖啡机").origin)
        val input = com.example.monthlyexpense.screenshot.ScreenshotExpenseInput(java.util.UUID.randomUUID().toString(), null, 300, "咖啡机", "", "TRAVEL", System.currentTimeMillis(), "咖啡机", false)
        assertTrue(store.save(input, true) is com.example.monthlyexpense.screenshot.ScreenshotSaveResult.Saved)
        assertEquals(ClassificationOrigin.CONFLICT, helper.expenseDao.currentMonthRecords().first().classificationOrigin)
        val manual = input.copy(taskId = java.util.UUID.randomUUID().toString(), categoryKey = "OTHER", categoryExplicit = true)
        assertTrue(store.save(manual, true) is com.example.monthlyexpense.screenshot.ScreenshotSaveResult.Saved)
        assertEquals(ClassificationOrigin.MANUAL, helper.expenseDao.currentMonthRecords().first().classificationOrigin)
        assertEquals("OTHER", helper.expenseDao.currentMonthRecords().first().category.key)
    }
}
