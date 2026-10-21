package com.example.monthlyexpense.categories

import com.example.monthlyexpense.CategoryEditorTarget
import com.example.monthlyexpense.CategoryInput
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.categories.api.CategoryOperationState
import com.example.monthlyexpense.categories.api.CategoryWritePort
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.presentation.ForegroundMutationRunner
import com.example.monthlyexpense.presentation.contracts.*
import com.example.monthlyexpense.refresh.RefreshScope

/** Category editor/delete targets, result interpretation and one shared save/delete generation.
 * UI-thread commands and cancellation completion use the host's shared admission lock. */
internal class CategoryManagementController(
    private val state: SliceState<CategoryOperationState>,
    private val writes: CategoryWritePort,
    private val categories: ReadState<List<ExpenseCategory>>,
    private val displayedEpoch: ReadState<Long>,
    private val historyLoaded: ReadState<Boolean>,
    private val runner: ForegroundMutationRunner,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val admissionLock: Any,
) {
    private var generation = 0L
    private val savingSlot = CategorySlot(deleting = false)
    private val deletingSlot = CategorySlot(deleting = true)

    fun showCategoryEditor(target: CategoryEditorTarget) = synchronized(admissionLock) {
        state.update { it.copy(management = it.management.copy(editor = target)) }
    }
    fun requestCategoryDelete(categoryKey: String) = synchronized(admissionLock) {
        state.update { it.copy(management = it.management.copy(deleteTargetKey = categoryKey)) }
    }
    fun dismissCategoryEditor() = synchronized(admissionLock) {
        state.update { it.copy(management = it.management.copy(editor = null)) }
    }
    fun dismissCategoryDelete() = synchronized(admissionLock) {
        state.update { it.copy(management = it.management.copy(deleteTargetKey = null)) }
    }

    fun addCategory(input: CategoryInput) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket(displayedEpoch.value) ?: return@synchronized
        val name = input.name?.trim().orEmpty()
        runner.launch(ticket, savingSlot, { null }, { writes.addCustomCategory(name, input.colorArgb) },
            { MutationResult(it is CategoryMutationResult.Success, mutationMessage(it)) },
            { closeEditor(CategoryEditorTarget.New) }, refreshScope(), domains, UiMessage("分类保存失败，请重试"))
    }

    fun updateCategory(categoryKey: String, input: CategoryInput) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket(displayedEpoch.value) ?: return@synchronized
        val category = categories.value.firstOrNull { it.key == categoryKey }
        val name = if (category?.builtIn == true) null else input.name?.trim()
        val target = CategoryEditorTarget.Existing(categoryKey)
        runner.launch(ticket, savingSlot, { null }, { writes.updateCategory(categoryKey, name, input.colorArgb) },
            { MutationResult(it is CategoryMutationResult.Success, mutationMessage(it)) },
            { closeEditor(target) }, refreshScope(), domains, UiMessage("分类保存失败，请重试"))
    }

    fun deleteCategory(categoryKey: String) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket(displayedEpoch.value) ?: return@synchronized
        val name = categories.value.firstOrNull { it.key == categoryKey }?.name.orEmpty()
        runner.launch(ticket, deletingSlot, { null }, { writes.deleteCustomCategory(categoryKey) },
            { MutationResult(it == CategoryDeleteResult.Deleted, deleteMessage(it, name)) },
            { state.update { current ->
                if (current.management.deleteTargetKey == categoryKey)
                    current.copy(management = current.management.copy(deleteTargetKey = null)) else current
            } }, refreshScope(), domains, UiMessage("分类删除失败，请重试"))
    }

    /** Invalidate handles synchronously without publishing; host performs one complete restore reset. */
    fun invalidateForRestore() = synchronized(admissionLock) { generation++; Unit }

    private fun closeEditor(target: CategoryEditorTarget) = state.update {
        if (it.management.editor == target) it.copy(management = it.management.copy(editor = null)) else it
    }
    private fun refreshScope() = if (historyLoaded.value) RefreshScope.ALL else RefreshScope.HOME
    private val domains = setOf(DataDomain.LEDGER, DataDomain.SETTINGS)

    private inner class CategorySlot(private val deleting: Boolean) : MutationSlot {
        override fun admit(): Long? = synchronized(admissionLock) {
            if (state.value.saving || state.value.deleting) return@synchronized null
            val admitted = ++generation
            state.update { if (deleting) it.copy(deleting = true) else it.copy(saving = true) }
            admitted
        }
        override fun isCurrent(generation: Long) = synchronized(admissionLock) { this@CategoryManagementController.generation == generation }
        override fun finish(generation: Long) = synchronized(admissionLock) {
            if (this@CategoryManagementController.generation == generation)
                state.update { if (deleting) it.copy(deleting = false) else it.copy(saving = false) }
        }
    }
}

private fun mutationMessage(result: CategoryMutationResult): UiMessage? = when (result) {
    is CategoryMutationResult.Success -> null
    CategoryMutationResult.DuplicateName -> UiMessage("分类名称已存在")
    CategoryMutationResult.InvalidName -> UiMessage("分类名称应为 1 至 20 个字符")
    CategoryMutationResult.BuiltInNameLocked -> UiMessage("内置分类名称不能修改")
    CategoryMutationResult.NotFound -> UiMessage("分类不存在")
}

private fun deleteMessage(result: CategoryDeleteResult, name: String): UiMessage? = when (result) {
    CategoryDeleteResult.Deleted -> UiMessage("分类“$name”已删除")
    is CategoryDeleteResult.InUse -> UiMessage("该分类仍有 ${result.expenseCount} 条消费记录，请先编辑这些记录并更改分类")
    CategoryDeleteResult.BuiltInLocked -> UiMessage("内置分类不能删除")
    CategoryDeleteResult.NotFound -> UiMessage("分类不存在")
}
