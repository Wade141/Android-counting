package com.example.monthlyexpense.classification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.backup.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryRuleDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var helper: ExpenseDatabaseHelper
    @Before fun setup() { context.deleteDatabase("expenses.db"); helper = ExpenseDatabaseHelper(context) }
    @After fun close() { helper.close(); context.deleteDatabase("expenses.db") }
    private fun rule(key: String = "FOOD", word: String = "瑞幸", field: CategoryMatchField = CategoryMatchField.MERCHANT_OR_NAME) =
        CategoryRuleSet(key, true, field, keywords = listOf(CategoryKeywordRule(key + word, word, normalizeKeyword(word))))

    @Test fun savesAtomicallyRejectsStaleAndOverlappingDuplicates() {
        val dao = helper.categoryRuleDao
        assertTrue(dao.saveRuleSet(rule(), 0) is RuleSaveResult.Success)
        assertEquals(1L, dao.loadRuleSets().single().revision)
        assertEquals(RuleSaveResult.Stale, dao.saveRuleSet(rule(word = "星巴克"), 0))
        assertTrue(dao.saveRuleSet(rule("SHOPPING"), 0) is RuleSaveResult.Invalid)
        assertEquals("瑞幸", dao.loadRuleSets().single().keywords.single().keyword)
        assertTrue(dao.saveRuleSet(rule("SHOPPING").copy(enabled = false), 0) is RuleSaveResult.Success)
        assertTrue(dao.saveRuleSet(rule("SHOPPING"), 1) is RuleSaveResult.Invalid)
    }
    @Test fun validatesEmptyAndLimitsAndDisjointScopes() {
        val dao = helper.categoryRuleDao
        assertTrue(dao.saveRuleSet(rule().copy(keywords = emptyList()), 0) is RuleSaveResult.Invalid)
        assertTrue(dao.saveRuleSet(rule().copy(keywords = List(51) { CategoryKeywordRule("$it", "词$it", "词$it") }), 0) is RuleSaveResult.Invalid)
        assertTrue(dao.saveRuleSet(rule(field = CategoryMatchField.MERCHANT), 0) is RuleSaveResult.Success)
        assertTrue(dao.saveRuleSet(rule("SHOPPING", field = CategoryMatchField.NAME), 0) is RuleSaveResult.Success)
    }
    @Test fun backupRoundTripAndOldRestoreReplaceRules() {
        helper.categoryRuleDao.saveRuleSet(rule(), 0)
        val document = ExpenseBackupDocument(1, helper.backupDao.exportBackupDatabase(), ExpenseBackupSettings(100, false, false, false))
        val encoded = ExpenseBackupJson.encode(document)
        assertEquals(3, JSONObject(encoded).getInt("version"))
        assertEquals(document, ExpenseBackupJson.decode(encoded))
        val old = JSONObject(encoded).put("version", 1)
        old.getJSONObject("database").remove("categoryRules")
        val restored = ExpenseBackupJson.decode(old.toString())
        assertTrue(restored.database.categoryRules.isEmpty())
        assertTrue(helper.backupDao.restoreBackupDatabase(restored.database))
        assertTrue(helper.categoryRuleDao.loadRuleSets().isEmpty())
    }
    @Test fun migratesVersionSevenWithoutChangingLedgerAndMarksLegacy() {
        helper.expenseDao.addExpense(1234, "FOOD", "旧账单", "旧备注")
        val db = helper.writableDatabase
        removeClassificationFromLegacyFixture(db)
        db.version = 7; helper.close()
        helper = ExpenseDatabaseHelper(context)
        val old = helper.expenseDao.currentMonthRecords().single()
        assertEquals(1234L, old.amountCents)
        assertEquals("FOOD", old.category.key)
        assertEquals(ClassificationOrigin.LEGACY, old.classificationOrigin)
        assertTrue(helper.categoryRuleDao.loadRuleSets().isEmpty())
        assertEquals(9, helper.readableDatabase.version)
    }
    @Test fun failedCategoryDeletePreservesRulesAndRenameKeepsStableKey() {
        val category = helper.categoryDao.addCustomCategory("测试", 0) as com.example.monthlyexpense.categories.CategoryMutationResult.Success
        val key = category.category.key
        helper.categoryRuleDao.saveRuleSet(rule(key), 0)
        helper.categoryDao.updateCategoryDefinition(key, "改名", 0)
        assertEquals(key, helper.categoryRuleDao.loadRuleSets().single().categoryKey)
        helper.expenseDao.addExpense(100, key, "一笔", "")
        assertTrue(helper.categoryDao.deleteCustomCategory(key) is com.example.monthlyexpense.categories.CategoryDeleteResult.InUse)
        assertEquals(1, helper.categoryRuleDao.loadRuleSets().size)
        helper.expenseDao.delete(helper.expenseDao.currentMonthRecords().single().id)
        assertEquals(com.example.monthlyexpense.categories.CategoryDeleteResult.Deleted, helper.categoryDao.deleteCustomCategory(key))
        assertTrue(helper.categoryRuleDao.loadRuleSets().isEmpty())
    }
    @Test fun rejectedReplacementKeepsBothGroupAndWords() {
        helper.categoryRuleDao.saveRuleSet(rule(), 0)
        val prior = helper.categoryRuleDao.loadRuleSets()
        assertTrue(helper.categoryRuleDao.saveRuleSet(rule(word = "\n"), 1) is RuleSaveResult.Invalid)
        assertEquals(prior, helper.categoryRuleDao.loadRuleSets())
    }
    @Test fun sqliteFailureRollsBackSettingsWordsAndRevisionTogether() {
        helper.categoryRuleDao.saveRuleSet(rule(), 0)
        val prior = helper.categoryRuleDao.loadRuleSets()
        helper.writableDatabase.execSQL("CREATE TRIGGER keyword_failure BEFORE INSERT ON category_keyword_rules BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { helper.categoryRuleDao.saveRuleSet(rule(word = "咖啡"), 1) }.isFailure)
        assertEquals(prior, helper.categoryRuleDao.loadRuleSets())
    }
    @Test fun restoredEpochRejectsOldDraftEvenWhenRevisionMatches() = kotlinx.coroutines.runBlocking {
        val coordinator = com.example.monthlyexpense.data.ForegroundPersistenceCoordinator(com.example.monthlyexpense.data.ForegroundSettingsBaseline(false, true, true))
        val changes = com.example.monthlyexpense.data.DataChangePublisher()
        val repository = CategoryRuleRepository(helper.categoryRuleDao, coordinator, changes)
        val oldEpoch = coordinator.currentEpoch
        val restore = coordinator.beginRestore()!!
        coordinator.runRestore(restore, { com.example.monthlyexpense.data.RestoreCommit.NotRestored }, {})
        assertEquals(RuleSaveResult.Stale, repository.save(rule(), oldEpoch))
        assertTrue(helper.categoryRuleDao.loadRuleSets().isEmpty())
        assertEquals(0L, changes.revisions.value.settings)
        assertTrue(repository.save(rule(), coordinator.currentEpoch) is RuleSaveResult.Success)
        assertEquals(1L, changes.revisions.value.settings)
        assertEquals(0L, changes.revisions.value.ledger)
    }
    @Test fun rejectsInvalidBackupRulesAndEvidenceAndReadsRealVersionOneExpenses() {
        helper.categoryRuleDao.saveRuleSet(rule(), 0)
        helper.expenseDao.addExpense(123, "FOOD", "瑞幸", "")
        val document = ExpenseBackupDocument(1, helper.backupDao.exportBackupDatabase(), ExpenseBackupSettings(0, false, false, false))
        val json = ExpenseBackupJson.encode(document)
        fun invalid(change: (JSONObject) -> Unit) {
            val root = JSONObject(json); change(root)
            assertTrue(runCatching { ExpenseBackupJson.decode(root.toString()) }.isFailure)
        }
        invalid { it.getJSONObject("database").getJSONArray("categoryRules").getJSONObject(0).put("matchField", "RAW_NOTIFICATION") }
        invalid { it.getJSONObject("database").getJSONArray("categoryRules").getJSONObject(0).put("categoryKey", "missing") }
        invalid { root -> val words = root.getJSONObject("database").getJSONArray("categoryRules").getJSONObject(0).getJSONArray("keywords"); words.put(words.getJSONObject(0)) }
        invalid { it.getJSONObject("database").getJSONArray("expenses").getJSONObject(0).put("classificationOrigin", "INVENTED") }
        invalid { it.getJSONObject("database").getJSONArray("expenses").getJSONObject(0).put("classificationOrigin", "KEYWORD") }
        val old = JSONObject(json).put("version", 1)
        old.getJSONObject("database").remove("categoryRules")
        old.getJSONObject("database").getJSONArray("expenses").getJSONObject(0).apply { remove("classificationOrigin"); remove("classificationHits"); remove("isSpecial") }
        val decoded = ExpenseBackupJson.decode(old.toString())
        assertEquals(ClassificationOrigin.LEGACY, decoded.database.expenses.single().classificationOrigin)
        assertTrue(helper.backupDao.restoreBackupDatabase(decoded.database))
        assertTrue(helper.categoryRuleDao.loadRuleSets().isEmpty())
        assertEquals(123L, helper.expenseDao.currentMonthRecords().single().amountCents)
    }
    @Test fun totalWordLimitCountsDisabledGroupsAndEmptyGroupCountIsNotAWordLimit() {
        val groups = List(11) { n -> CategoryRuleSet("$n", false, keywords = List(50) { i -> CategoryKeywordRule("$n-$i", "$n-$i", "$n-$i") }) }
        assertNotNull(validateRuleSets(groups, groups.map { it.categoryKey }.toSet()))
        val empty = List(501) { CategoryRuleSet("c$it", revision = 1) }
        assertNull(validateRuleSets(empty, empty.map { it.categoryKey }.toSet()))
        assertEquals(empty, ClassificationJson.decodeRules(ClassificationJson.encodeRules(empty)))
    }
    @Test fun importedEnabledRuleWithZeroRevisionIsRejectedBeforeRestore() {
        helper.categoryRuleDao.saveRuleSet(rule(), 0)
        val doc = ExpenseBackupDocument(1, helper.backupDao.exportBackupDatabase(), ExpenseBackupSettings(0, false, false, false))
        val json = JSONObject(ExpenseBackupJson.encode(doc))
        json.getJSONObject("database").getJSONArray("categoryRules").getJSONObject(0).put("revision", 0)
        assertTrue(runCatching { ExpenseBackupJson.decode(json.toString()) }.isFailure)
        assertFalse(helper.backupDao.restoreBackupDatabase(doc.database.copy(categoryRules = doc.database.categoryRules.map { it.copy(revision = 0) })))
        assertEquals(1L, helper.categoryRuleDao.loadRuleSets().single().revision)
    }
}
