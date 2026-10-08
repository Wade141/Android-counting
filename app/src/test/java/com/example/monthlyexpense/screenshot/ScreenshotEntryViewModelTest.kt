package com.example.monthlyexpense.screenshot

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.example.monthlyexpense.*
import com.example.monthlyexpense.ocrtest.*
import com.example.monthlyexpense.classification.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotEntryViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val uri = Uri.parse("content://test/screenshot")
    private fun result() = OcrTestResult("raw", 1, 100, 200,
        analysis = ScreenshotAnalysis(amountCents = 1543, merchant = "SEVEN ELEVEN"),
        imageHash = "a".repeat(64))
    private fun model(store: ScreenshotStore = FakeStore(), handle: SavedStateHandle = SavedStateHandle(),
        recognize: suspend (Uri) -> OcrTestResult = { result() },
        now: () -> ZonedDateTime = { ZonedDateTime.parse("2026-09-15T14:35:42.123+08:00[Asia/Shanghai]") }) = ScreenshotEntryViewModel(
        store, handle, recognize, today = { LocalDate.of(2026, 9, 15) }, now = now)

    @Test fun recognitionPrefillsButNeverSavesOrConfirms() = runTest {
        val store = FakeStore()
        val vm = model(store)
        vm.receive(uri); advanceUntilIdle()
        assertEquals("15.43", vm.state.value.amount)
        assertEquals("SEVEN ELEVEN", vm.state.value.name)
        assertEquals("2026-09-15", vm.state.value.date)
        assertEquals(BuiltInCategoryKeys.OTHER, vm.state.value.categoryKey)
        assertFalse(vm.state.value.confirmed)
        vm.save(); advanceUntilIdle()
        assertTrue(store.inputs.isEmpty())
    }
    @Test fun explicitConfirmationSavesOnceDespiteDoubleTap() = runTest {
        val store = FakeStore(); val vm = model(store)
        vm.receive(uri); advanceUntilIdle(); vm.confirm(true)
        vm.save(); vm.save(); advanceUntilIdle(); vm.save()
        assertEquals(1, store.inputs.size)
        assertEquals(1543L, store.inputs.single().amountCents)
        assertEquals(1L, vm.state.value.savedId)
    }
    @Test fun savingKeepsSelectedDateAndUsesCurrentSystemTimeInsteadOfMidnight() = runTest {
        val store = FakeStore(); val vm = model(store)
        vm.receive(uri); advanceUntilIdle(); vm.confirm(true)
        vm.edit(ScreenshotField.DATE, "2026-08-31")
        vm.save(); advanceUntilIdle()
        assertEquals(Instant.parse("2026-08-31T06:35:42.123Z").toEpochMilli(), store.inputs.single().spentAt)
    }
    @Test fun timestampComesFromSaveNotFromOpeningTheImage() = runTest {
        var clock = ZonedDateTime.parse("2026-09-15T09:00:00+08:00[Asia/Shanghai]")
        val store = FakeStore(); val vm = model(store, now = { clock })
        vm.receive(uri); advanceUntilIdle(); vm.confirm(true)
        clock = clock.plusHours(2).plusSeconds(37)
        vm.save(); advanceUntilIdle()
        assertEquals(Instant.parse("2026-09-15T03:00:37Z").toEpochMilli(), store.inputs.single().spentAt)
    }
    @Test fun duplicateConfirmationRetainsTimestampEvenWhenSystemTimeAdvances() = runTest {
        var clock = ZonedDateTime.parse("2026-09-15T23:59:58+08:00[Asia/Shanghai]")
        val store = FakeStore().apply { duplicate = true }; val vm = model(store, now = { clock })
        vm.receive(uri); advanceUntilIdle(); vm.confirm(true)
        vm.save(); advanceUntilIdle()
        clock = clock.plusMinutes(2)
        vm.save(true); advanceUntilIdle()
        assertEquals(store.inputs.first(), store.inputs.last())
        assertTrue(store.overrides.last())
        assertNotNull(vm.state.value.savedId)
    }
    @Test fun editingAfterDuplicateWarningUsesFreshSystemTimeAndRechecksDuplicates() = runTest {
        var clock = ZonedDateTime.parse("2026-09-15T10:00:00+08:00[Asia/Shanghai]")
        val store = FakeStore().apply { duplicate = true }; val vm = model(store, now = { clock })
        vm.receive(uri); advanceUntilIdle(); vm.confirm(true)
        vm.save(); advanceUntilIdle()
        clock = clock.plusMinutes(5)
        vm.edit(ScreenshotField.DATE, "2026-09-14")
        vm.save(true); advanceUntilIdle()
        assertFalse(store.overrides.last())
        assertEquals(Instant.parse("2026-09-14T02:05:00Z").toEpochMilli(), store.inputs.last().spentAt)
    }
    @Test fun lateOcrDoesNotOverwriteUserEdits() = runTest {
        val deferred = CompletableDeferred<OcrTestResult>()
        val vm = model(recognize = { deferred.await() })
        vm.receive(uri); runCurrent()
        vm.edit(ScreenshotField.AMOUNT, "25.00")
        vm.edit(ScreenshotField.NAME, "我的名称")
        deferred.complete(result()); advanceUntilIdle()
        assertEquals("25.00", vm.state.value.amount)
        assertEquals("我的名称", vm.state.value.name)
    }
    @Test fun secondImageRequiresReplacementAndNewTaskIdentity() = runTest {
        val vm = model(); vm.receive(uri); advanceUntilIdle()
        val task = vm.state.value.taskId
        vm.receive(Uri.parse("content://test/second"))
        assertTrue(vm.state.value.replacePending)
        assertEquals(task, vm.state.value.taskId)
        vm.resolveReplacement(true); advanceUntilIdle()
        assertNotEquals(task, vm.state.value.taskId)
    }
    @Test fun failureAllowsManualEntryAndValidatesDate() = runTest {
        val store = FakeStore(); val vm = model(store, recognize = { error("bad image") })
        vm.receive(uri); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        vm.edit(ScreenshotField.AMOUNT, "12.00"); vm.edit(ScreenshotField.NAME, "早餐")
        vm.edit(ScreenshotField.DATE, "2026-02-30"); vm.confirm(true); vm.save(); advanceUntilIdle()
        assertTrue(store.inputs.isEmpty())
        vm.edit(ScreenshotField.DATE, "2026-09-14"); vm.save(); advanceUntilIdle()
        assertEquals(1, store.inputs.size)
    }
    @Test fun duplicateOverrideRequiresShownUnchangedCandidate() = runTest {
        val store = FakeStore().apply { duplicate = true }; val vm = model(store)
        vm.receive(uri); advanceUntilIdle(); vm.confirm(true)
        vm.save(true); advanceUntilIdle()
        assertFalse(store.overrides.single())
        assertTrue(vm.state.value.duplicates.isNotEmpty())
        vm.edit(ScreenshotField.NAME, "新名称")
        vm.save(true); advanceUntilIdle()
        assertFalse(store.overrides.last())
        vm.save(true); advanceUntilIdle()
        assertTrue(store.overrides.last())
        assertNotNull(vm.state.value.savedId)
    }
    @Test fun savedStateRestoresDraftAndTaskButNotRawOcr() = runTest {
        val handle = SavedStateHandle(); val vm = model(handle = handle)
        vm.receive(uri); advanceUntilIdle(); vm.edit(ScreenshotField.NOTE, "补记")
        val restored = model(handle = handle)
        assertEquals(vm.state.value.taskId, restored.state.value.taskId)
        assertEquals("补记", restored.state.value.note)
        assertEquals("15.43", restored.state.value.amount)
        assertTrue(handle.keys().none { it.contains("raw", true) })
    }
    @Test fun missingFieldsRemainBlankAndStaleSaveDoesNotSucceed() = runTest {
        val store = FakeStore().apply { stale = true }
        val vm = model(store, recognize = { OcrTestResult("", 0, 1, 1) })
        vm.receive(uri); advanceUntilIdle()
        assertEquals("", vm.state.value.amount)
        vm.edit(ScreenshotField.AMOUNT, "1.00"); vm.edit(ScreenshotField.NAME, "核对")
        vm.confirm(true); vm.save(); advanceUntilIdle()
        assertNull(vm.state.value.savedId)
        assertNotNull(vm.state.value.error)
    }

    @Test fun editedNameDuringRecognitionGetsLatestKeywordSuggestion() = runTest {
        val deferred = CompletableDeferred<OcrTestResult>()
        val fake = FakeStore()
        val seen = mutableListOf<String>()
        val store = object : ScreenshotStore by fake {
            override suspend fun suggestClassification(merchant: String?, name: String): ResolvedCategory {
                seen += name
                return ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), listOf(CategoryRuleSet("FOOD", true,
                    matchField = CategoryMatchField.NAME, revision = 1, keywords = listOf(CategoryKeywordRule("food", "咖啡", "咖啡")))))
            }
        }
        val vm = model(store, recognize = { deferred.await() })
        vm.receive(uri); runCurrent()
        vm.edit(ScreenshotField.NAME, "咖啡")
        deferred.complete(result()); advanceUntilIdle()
        assertEquals("咖啡", seen.last())
        assertEquals(BuiltInCategoryKeys.FOOD, vm.state.value.categoryKey)
        assertEquals(ClassificationOrigin.KEYWORD, vm.state.value.classification!!.origin)
    }
    @Test fun editedNameDuringRecognitionShowsConflictUnlessCategoryWasExplicitlyChosen() = runTest {
        val deferred = CompletableDeferred<OcrTestResult>()
        val fake = FakeStore()
        val store = object : ScreenshotStore by fake {
            override suspend fun suggestClassification(merchant: String?, name: String): ResolvedCategory {
                val rules = listOf("FOOD" to "咖啡", "SHOPPING" to "咖啡机").map { (key, word) -> CategoryRuleSet(key, true,
                    matchField = CategoryMatchField.NAME, revision = 1, keywords = listOf(CategoryKeywordRule(key, word, word))) }
                return ExpenseCategoryResolver.resolve(ClassificationInput(merchant, name), rules)
            }
        }
        val vm = model(store, recognize = { deferred.await() })
        vm.receive(uri); runCurrent(); vm.edit(ScreenshotField.NAME, "咖啡机")
        deferred.complete(result()); advanceUntilIdle()
        assertEquals(ClassificationOrigin.CONFLICT, vm.state.value.classification!!.origin)
        assertEquals("OTHER", vm.state.value.categoryKey)
        vm.edit(ScreenshotField.CATEGORY, "OTHER"); vm.edit(ScreenshotField.NAME, "咖啡"); advanceUntilIdle()
        assertEquals("OTHER", vm.state.value.categoryKey)
        assertNull(vm.state.value.classification)
    }

    @Test fun interruptedRecognitionRestoresEditableDraftWithReselectMessage() = runTest {
        val deferred = CompletableDeferred<OcrTestResult>()
        val handle = SavedStateHandle()
        val vm = model(handle = handle, recognize = { deferred.await() })
        vm.receive(uri); runCurrent()
        vm.edit(ScreenshotField.AMOUNT, "6.00")
        val restored = model(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals("6.00", restored.state.value.amount)
        assertFalse(restored.state.value.recognizing)
        assertTrue(restored.state.value.error!!.contains("中断"))
        deferred.complete(result()); advanceUntilIdle()
    }

    @Test fun delayedCategoriesCannotReplaceLatestDraftOrTask() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore().apply { categoryGate = gate }
        val vm = model(store)
        runCurrent()
        vm.receive(uri); runCurrent()
        vm.edit(ScreenshotField.NAME, "保留这个名称")
        val task = vm.state.value.taskId
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(task, vm.state.value.taskId)
        assertEquals("保留这个名称", vm.state.value.name)
        assertEquals("15.43", vm.state.value.amount)
    }

    @Test fun oldProcessDraftMustReselectEvenAfterAnotherRecreation() = runTest {
        val handle = SavedStateHandle()
        bindScreenshotSession(handle, "process1:0")
        val vm = model(handle = handle)
        vm.receive(uri); advanceUntilIdle()
        bindScreenshotSession(handle, "process2:0")
        bindScreenshotSession(handle, "process2:0")
        val store = FakeStore(); val restored = model(store, handle)
        advanceUntilIdle(); restored.confirm(true); restored.save(); advanceUntilIdle()
        assertTrue(store.inputs.isEmpty())
        assertTrue(restored.state.value.error!!.contains("重新选图"))
        restored.receive(uri); restored.resolveReplacement(true); advanceUntilIdle()
        restored.confirm(true); restored.save(); advanceUntilIdle()
        assertEquals(1, store.inputs.size)
    }

    private class FakeStore : ScreenshotStore {
        val inputs = mutableListOf<ScreenshotExpenseInput>()
        val overrides = mutableListOf<Boolean>()
        var duplicate = false
        var stale = false
        var suggestedCategory: String? = null
        var categoryGate: CompletableDeferred<Unit>? = null
        override suspend fun loadCategories(): List<ExpenseCategory> {
            categoryGate?.await()
            return listOf(ExpenseCategory(BuiltInCategoryKeys.OTHER, "其他", 0, true, 0),
                ExpenseCategory(BuiltInCategoryKeys.FOOD, "餐饮", 0, true, 1))
        }
        override suspend fun suggestCategory(merchant: String): String? = suggestedCategory
        override suspend fun save(input: ScreenshotExpenseInput, allowDuplicate: Boolean): ScreenshotSaveResult {
            inputs += input; overrides += allowDuplicate
            return when {
                stale -> ScreenshotSaveResult.Stale
                duplicate && !allowDuplicate -> ScreenshotSaveResult.Duplicate(listOf(DuplicateExpense(7, input.amountCents, input.name, input.spentAt, "同图")))
                else -> ScreenshotSaveResult.Saved(1)
            }
        }
    }
}
