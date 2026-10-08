package com.example.monthlyexpense.ui.categories

import com.example.monthlyexpense.MainDispatcherRule
import com.example.monthlyexpense.classification.*
import com.example.monthlyexpense.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategoryKeywordViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private class Store : CategoryRuleStore {
        var rules = emptyList<CategoryRuleSet>()
        var writes = 0
        var result: RuleSaveResult? = null
        override suspend fun load() = rules
        override suspend fun save(draft: CategoryRuleSet, epoch: Long): RuleSaveResult {
            writes++
            return result ?: RuleSaveResult.Success(draft.copy(revision = draft.revision + 1)).also {
                rules = rules.filter { it.categoryKey != draft.categoryKey } + it.ruleSet
            }
        }
    }
    @Test fun cancelKeywordDraftLeavesRulesUntouched() = runTest {
        val store = Store(); val vm = CategoryKeywordViewModel(store) { 0 }
        vm.open("FOOD"); advanceUntilIdle()
        vm.input("瑞幸"); vm.addKeyword(); vm.enabled(true); vm.cancel()
        assertEquals(0, store.writes); assertTrue(store.rules.isEmpty()); assertNull(vm.state.value.editor)
    }
    @Test fun previewExplainsCrossCategoryConflict() = runTest {
        val store = Store().apply { rules = listOf(CategoryRuleSet("SHOPPING", true, keywords = listOf(CategoryKeywordRule("s", "咖啡机", "咖啡机")))) }
        val vm = CategoryKeywordViewModel(store) { 0 }
        vm.open("FOOD"); advanceUntilIdle(); vm.input("咖啡"); vm.addKeyword(); vm.enabled(true)
        vm.testMerchant("咖啡机店"); vm.preview()
        val result = vm.state.value.editor!!.preview as CategoryMatch.Conflict
        assertEquals(setOf("FOOD", "SHOPPING"), result.hits.map { it.categoryKey }.toSet())
        assertEquals(0, store.writes)
    }
    @Test fun failedSaveKeepsDraftAndUnaddedTextMustBeAddedFirst() = runTest {
        val store = Store().apply { result = RuleSaveResult.Stale }
        val vm = CategoryKeywordViewModel(store) { 0 }
        vm.open("FOOD"); advanceUntilIdle(); vm.input("瑞幸"); vm.save(); advanceUntilIdle()
        assertEquals(0, store.writes)
        vm.addKeyword(); vm.enabled(true); vm.save(); advanceUntilIdle()
        assertEquals("瑞幸", vm.state.value.editor!!.draft.keywords.single().keyword)
        assertNotNull(vm.state.value.editor!!.error)
    }
    @Test fun correctionPrefillDoesNotSaveAndUsesExactMatch() = runTest {
        val store = Store(); val vm = CategoryKeywordViewModel(store) { 3 }
        vm.open("FOOD", "瑞幸咖啡（北门店）"); advanceUntilIdle()
        assertEquals(KeywordMatchMode.EXACT, vm.state.value.editor!!.inputMode)
        assertEquals("瑞幸咖啡（北门店）", vm.state.value.editor!!.input)
        assertEquals(0, store.writes)
        assertFalse(vm.state.value.editor!!.draft.enabled)
    }
    @Test fun correctionFromPreviousLedgerCannotAdoptNewEpoch() = runTest {
        val store = Store(); val vm = CategoryKeywordViewModel(store) { 3 }
        vm.open("FOOD", "旧账本商户", sourceEpoch = 0); advanceUntilIdle()
        assertNull(vm.state.value.editor)
        assertNotNull(vm.state.value.error)
        assertEquals(0, store.writes)
    }
}
