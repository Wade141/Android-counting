package com.example.monthlyexpense.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.monthlyexpense.classification.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class CategoryKeywordViewModel(private val repository: CategoryRuleStore, private val epoch: () -> Long) : ViewModel() {
    private val mutable = MutableStateFlow(CategoryKeywordsState())
    val state = mutable.asStateFlow()
    private var request = 0L

    fun refresh() {
        val expectedEpoch = epoch()
        viewModelScope.launch {
            try {
                val rules = repository.load() ?: return@launch
                if (epoch() == expectedEpoch) mutable.update { it.copy(rules = rules) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "关键词规则加载失败，请重试") } }
        }
    }
    fun open(categoryKey: String, prefill: String? = null, sourceEpoch: Long? = null) {
        if (mutable.value.editor?.saving == true) return
        val token = ++request
        val expectedEpoch = sourceEpoch ?: epoch()
        if (expectedEpoch != epoch()) {
            mutable.update { it.copy(error = "账本已改变，旧记录的纠正入口已失效，请重新打开记录", loading = false) }
            return
        }
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val rules = repository.load()
                if (token != request) return@launch
                if (rules == null || epoch() != expectedEpoch) {
                    mutable.update { it.copy(loading = false, error = "账本已改变，请重新打开自动归类") }; return@launch
                }
                mutable.value = CategoryKeywordsState(rules, CategoryKeywordUiState(
                    rules.find { it.categoryKey == categoryKey } ?: CategoryRuleSet(categoryKey), expectedEpoch,
                    input = prefill.orEmpty(), inputMode = if (prefill == null) KeywordMatchMode.CONTAINS else KeywordMatchMode.EXACT))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == request) mutable.update { it.copy(loading = false, error = "关键词规则加载失败，请重试") } }
        }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun cancel() { if (mutable.value.editor?.saving != true) { request++; mutable.update { it.copy(editor = null, loading = false) } } }
    private fun edit(block: (CategoryKeywordUiState) -> CategoryKeywordUiState) {
        mutable.update { s -> s.editor?.takeUnless { it.saving }?.let { s.copy(editor = block(it)) } ?: s }
    }
    fun input(text: String) = edit { it.copy(input = text, error = null) }
    fun mode(mode: KeywordMatchMode) = edit { it.copy(inputMode = mode) }
    fun enabled(value: Boolean) = edit { it.copy(draft = it.draft.copy(enabled = value), preview = null, error = null) }
    fun field(value: CategoryMatchField) = edit { it.copy(draft = it.draft.copy(matchField = value), preview = null, error = null) }
    fun remove(id: String) = edit { it.copy(draft = it.draft.copy(keywords = it.draft.keywords.filterNot { word -> word.id == id }),
        editingId = if (it.editingId == id) null else it.editingId, preview = null) }
    fun editKeyword(id: String) = edit { s -> s.draft.keywords.find { it.id == id }?.let {
        s.copy(input = it.keyword, inputMode = it.mode, editingId = id, error = null)
    } ?: s }
    fun addKeyword() = edit { s ->
        val normalized = normalizeKeyword(s.input)
        val error = keywordValidationError(s.input) ?: when {
            s.editingId == null && s.draft.keywords.size >= 50 -> "每个分类最多 50 个关键词"
            s.draft.keywords.any { it.id != s.editingId && it.normalizedKeyword == normalized && it.mode == s.inputMode } -> "这个关键词和匹配方式已经存在"
            else -> null
        }
        if (error != null) s.copy(error = error) else {
            val word = CategoryKeywordRule(s.editingId ?: UUID.randomUUID().toString(), s.input, normalized, s.inputMode)
            s.copy(draft = s.draft.copy(keywords = s.draft.keywords.filterNot { it.id == word.id } + word), input = "", editingId = null, error = null, preview = null)
        }
    }
    fun testMerchant(text: String) = edit { it.copy(testMerchant = text, preview = null) }
    fun testName(text: String) = edit { it.copy(testName = text, preview = null) }
    fun preview() = edit { s -> s.copy(preview = CategoryRuleMatcher.match(ClassificationInput(s.testMerchant, s.testName),
        mutable.value.rules.filterNot { it.categoryKey == s.draft.categoryKey } + s.draft)) }
    fun save() {
        val editor = mutable.value.editor?.takeUnless { it.saving } ?: return
        if (editor.input.isNotEmpty()) { edit { it.copy(error = "输入框中还有未添加的关键词，请先点击＋或清空") }; return }
        edit { it.copy(saving = true, error = null, conflictingCategoryKey = null) }
        viewModelScope.launch {
            try {
                val result = repository.save(editor.draft, editor.epoch)
                when (result) {
                    is RuleSaveResult.Success -> mutable.update { it.copy(rules = it.rules.filterNot { r -> r.categoryKey == result.ruleSet.categoryKey } + result.ruleSet, editor = null) }
                    is RuleSaveResult.Invalid -> mutable.update { it.copy(editor = editor.copy(error = result.message, conflictingCategoryKey = result.conflictingCategoryKey)) }
                    RuleSaveResult.Stale -> mutable.update { it.copy(editor = editor.copy(error = "账本或规则已改变，请取消后重新打开；当前草稿尚未保存")) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(editor = editor.copy(error = "保存失败，请重试；草稿已保留")) } }
        }
    }
}
