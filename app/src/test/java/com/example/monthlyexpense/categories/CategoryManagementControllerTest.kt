package com.example.monthlyexpense.categories

import com.example.monthlyexpense.*
import com.example.monthlyexpense.categories.api.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.*
import com.example.monthlyexpense.refresh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagementControllerTest {
    private val food = ExpenseCategory("FOOD", "餐饮", 0, true, 0)
    private val custom = ExpenseCategory("CUSTOM", "旧名称", 0, false, 1)
    private inner class Writes : CategoryWritePort {
        val calls = mutableListOf<String>(); val names = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null
        var result: CategoryMutationResult = CategoryMutationResult.Success(custom)
        var deleted: CategoryDeleteResult = CategoryDeleteResult.Deleted
        override suspend fun addCustomCategory(name: String, colorArgb: Long): CategoryMutationResult {
            calls += "add"; names += name; gate?.await(); return result
        }
        override suspend fun updateCategory(categoryKey: String, name: String?, colorArgb: Long): CategoryMutationResult {
            calls += categoryKey; names += name; gate?.await(); return result
        }
        override suspend fun deleteCustomCategory(categoryKey: String): CategoryDeleteResult {
            calls += "delete:$categoryKey"; gate?.await(); return deleted
        }
    }
    private inner class Fixture(scope: CoroutineScope) {
        val common = MutationControllerFixture(scope); val state = MutableSlice(CategoryOperationState())
        val writes = Writes(); val categories = MutableRead(listOf(food, custom)); val loaded = MutableRead(false)
        val controller = CategoryManagementController(state, writes, categories, common.epoch, loaded,
            common.runner, common.coordinator, common.lock)
        suspend fun restore() = common.restore { controller.invalidateForRestore(); state.value = CategoryOperationState() }
    }

    @Test fun saveAndDeleteAreMutuallyExclusiveUntilOwnRefreshCompletes() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>(); f.common.refresh = { gate.await(); RefreshResult.APPLIED }
        f.controller.addCategory(CategoryInput(" 新分类 ", 1)); f.controller.deleteCategory("CUSTOM"); runCurrent()
        assertEquals(listOf("add"), f.writes.calls); assertEquals(listOf("新分类"), f.writes.names)
        assertTrue(f.state.value.saving); assertFalse(f.state.value.deleting)
        gate.complete(Unit); advanceUntilIdle()
        f.writes.gate = CompletableDeferred(); f.controller.deleteCategory("CUSTOM"); runCurrent()
        f.controller.updateCategory("FOOD", CategoryInput("改名", 1)); runCurrent()
        assertEquals(listOf("add", "delete:CUSTOM"), f.writes.calls); assertTrue(f.state.value.deleting)
        f.writes.gate!!.complete(Unit); advanceUntilIdle(); assertFalse(f.state.value.deleting)
    }

    @Test fun builtInNameIsNullCustomNameIsTrimmedAndLoadedHistoryChoosesAll() = runTest {
        val f = Fixture(this)
        f.controller.updateCategory("FOOD", CategoryInput("不允许改名", 1)); advanceUntilIdle()
        f.loaded.value = true; f.controller.updateCategory("CUSTOM", CategoryInput(" 新名 ", 2)); advanceUntilIdle()
        assertEquals(listOf(null, "新名"), f.writes.names)
        assertEquals(listOf(RefreshScope.HOME, RefreshScope.ALL), f.common.refreshes.map { it.first })
        assertTrue(f.common.refreshes.all { it.second == setOf(DataDomain.LEDGER, DataDomain.SETTINGS) })
    }

    @Test fun mutationResultsKeepMessagesAndOnlySuccessClosesMatchingEditor() = runTest {
        val f = Fixture(this)
        val cases = listOf(CategoryMutationResult.DuplicateName to "分类名称已存在",
            CategoryMutationResult.InvalidName to "分类名称应为 1 至 20 个字符",
            CategoryMutationResult.BuiltInNameLocked to "内置分类名称不能修改", CategoryMutationResult.NotFound to "分类不存在")
        for ((result, message) in cases) {
            f.writes.result = result; f.controller.showCategoryEditor(CategoryEditorTarget.New)
            f.controller.addCategory(CategoryInput("名字", 1)); advanceUntilIdle()
            assertEquals(CategoryEditorTarget.New, f.state.value.management.editor)
            assertEquals(message, f.common.messages().last())
        }
        assertTrue(f.common.refreshes.isEmpty())
        f.writes.result = CategoryMutationResult.Success(custom); f.writes.gate = CompletableDeferred()
        f.controller.addCategory(CategoryInput("名字", 1)); runCurrent()
        f.controller.showCategoryEditor(CategoryEditorTarget.Existing("CUSTOM"))
        f.writes.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(CategoryEditorTarget.Existing("CUSTOM"), f.state.value.management.editor)
        f.controller.updateCategory("CUSTOM", CategoryInput("名字", 1)); advanceUntilIdle()
        assertNull(f.state.value.management.editor)
    }

    @Test fun deleteResultsRetainTargetUntilSuccessAndUseCapturedName() = runTest {
        val f = Fixture(this)
        for ((result, message) in listOf(CategoryDeleteResult.InUse(3) to "该分类仍有 3 条消费记录，请先编辑这些记录并更改分类",
            CategoryDeleteResult.BuiltInLocked to "内置分类不能删除", CategoryDeleteResult.NotFound to "分类不存在")) {
            f.writes.deleted = result; f.controller.requestCategoryDelete("CUSTOM")
            f.controller.deleteCategory("CUSTOM"); advanceUntilIdle()
            assertEquals("CUSTOM", f.state.value.management.deleteTargetKey); assertEquals(message, f.common.messages().last())
        }
        assertTrue(f.common.refreshes.isEmpty())
        f.writes.deleted = CategoryDeleteResult.Deleted; f.writes.gate = CompletableDeferred()
        f.controller.deleteCategory("CUSTOM"); runCurrent(); f.categories.value = emptyList()
        f.controller.requestCategoryDelete("FOOD"); f.writes.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("FOOD", f.state.value.management.deleteTargetKey)
        assertEquals("分类“旧名称”已删除", f.common.messages().last())
        f.controller.dismissCategoryDelete(); f.controller.dismissCategoryEditor()
        assertEquals(CategoryManagementUiState(), f.state.value.management)
    }

    @Test fun restoreRejectsQueuedCategoryWritesAndInvalidationDoesNotPublishPartialReset() = runTest {
        for (delete in listOf(false, true)) {
            val f = Fixture(this); val gate = CompletableDeferred<Unit>()
            val holding = launch { f.common.coordinator.runMutation(f.common.coordinator.mutationTicket()!!) { gate.await() } }
            runCurrent()
            if (delete) f.controller.deleteCategory("CUSTOM") else f.controller.updateCategory("CUSTOM", CategoryInput("旧命令", 1))
            runCurrent(); val before = f.state.value; f.controller.invalidateForRestore(); assertEquals(before, f.state.value)
            val restoring = launch { f.restore() }; runCurrent(); gate.complete(Unit); holding.join(); restoring.join(); advanceUntilIdle()
            assertTrue(f.writes.calls.isEmpty()); assertTrue(f.common.events.isEmpty())
            f.controller.addCategory(CategoryInput("恢复后", 1)); advanceUntilIdle(); assertEquals(listOf("add"), f.writes.calls)
        }
    }

    @Test fun oldRefreshCompletionCannotClearNewDeletePendingOrEmitOldFailure() = runTest {
        val f = Fixture(this); val old = CompletableDeferred<Unit>(); val next = CompletableDeferred<Unit>()
        f.common.refresh = { old.await(); RefreshResult.FAILED }
        f.controller.addCategory(CategoryInput("旧", 1)); runCurrent(); f.restore()
        f.common.refresh = { next.await(); RefreshResult.APPLIED }; f.controller.deleteCategory("CUSTOM"); runCurrent()
        f.common.events.clear(); old.complete(Unit); runCurrent()
        assertTrue(f.state.value.deleting); assertTrue(f.common.events.isEmpty())
        next.complete(Unit); advanceUntilIdle(); assertFalse(f.state.value.deleting)
    }

    @Test fun cancellationBeforeStartAndDuringWriteClearsBothKindsSilently() = runTest {
        for (delete in listOf(false, true)) for (start in listOf(false, true)) {
            val job = SupervisorJob(); val f = Fixture(CoroutineScope(StandardTestDispatcher(testScheduler) + job))
            f.writes.gate = CompletableDeferred()
            if (delete) f.controller.deleteCategory("CUSTOM") else f.controller.addCategory(CategoryInput("新", 1))
            if (start) runCurrent(); job.cancel(); advanceUntilIdle()
            assertFalse(f.state.value.saving); assertFalse(f.state.value.deleting); assertTrue(f.common.events.isEmpty())
        }
    }
}
