package com.example.monthlyexpense.screenshot

import com.example.monthlyexpense.classification.*
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.ocrtest.OcrTestResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ScreenshotField { AMOUNT, NAME, NOTE, CATEGORY, DATE }

internal data class ScreenshotEntryState(
    val taskId: String = "", val imageHash: String? = null,
    val amount: String = "", val name: String = "", val note: String = "",
    val merchant: String? = null, val categoryExplicit: Boolean = false, val classification: ResolvedCategory? = null,
    val categoryKey: String = BuiltInCategoryKeys.OTHER, val date: String = "",
    val confirmed: Boolean = false, val recognizing: Boolean = false, val saving: Boolean = false,
    val active: Boolean = false, val replacePending: Boolean = false,
    val error: String? = null, val evidence: List<String> = emptyList(),
    val categories: List<ExpenseCategory> = emptyList(),
    val duplicates: List<DuplicateExpense> = emptyList(), val savedId: Long? = null
)

internal class ScreenshotEntryViewModel(
    private val store: ScreenshotStore,
    private val saved: SavedStateHandle,
    private val recognize: suspend (Uri) -> OcrTestResult,
    private val today: () -> LocalDate = LocalDate::now,
    private val now: () -> ZonedDateTime = ZonedDateTime::now
) : ViewModel() {
    private val mutable = MutableStateFlow(restore())
    val state = mutable.asStateFlow()
    private var recognition: Job? = null
    private var suggestionJob: Job? = null
    private var pendingUri: Uri? = null
    private val edited = mutableSetOf<ScreenshotField>()
    private var duplicateInput: ScreenshotExpenseInput? = null

    init {
        viewModelScope.launch {
            try {
                val categories = store.loadCategories()
                publish(mutable.value.copy(categories = categories))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("分类加载失败，请关闭此页后重试") }
        }
    }

    fun receive(uri: Uri) {
        if (mutable.value.saving) { fail("正在保存，请完成后重新分享图片"); return }
        if (uri.scheme != "content") { fail("请选择或分享一张可读取的图片"); return }
        if (mutable.value.active && mutable.value.savedId == null) {
            pendingUri = uri
            publish(mutable.value.copy(replacePending = true))
        } else start(uri)
    }

    fun resolveReplacement(replace: Boolean) {
        val uri = pendingUri
        pendingUri = null
        publish(mutable.value.copy(replacePending = false))
        if (replace && uri != null && !mutable.value.saving) start(uri)
    }

    private fun start(uri: Uri) {
        recognition?.cancel()
        suggestionJob?.cancel()
        saved["requiresNewImage"] = false
        edited.clear(); duplicateInput = null
        val task = UUID.randomUUID().toString()
        publish(ScreenshotEntryState(taskId = task, date = today().toString(), active = true,
            recognizing = true, categories = mutable.value.categories))
        recognition = viewModelScope.launch {
            try {
                val result = recognize(uri)
                if (mutable.value.taskId != task) return@launch
                val analysis = result.analysis
                var next = mutable.value.copy(imageHash = result.imageHash, merchant = analysis.merchant,
                    evidence = (analysis.amount.warnings + analysis.merchantField.warnings +
                        analysis.status.warnings + analysis.currency.warnings + analysis.direction.warnings + listOf(
                        "状态候选：${analysis.status.candidates.joinToString { it.value }.ifBlank { "未确定" }}；币种候选：${analysis.currency.candidates.joinToString { it.value }.ifBlank { "未确定" }}。",
                        "截图只提供候选；请核对金额、名称和人民币支出。",
                        "日期默认导入当天；时分秒采用确认保存时的系统时间；补记历史账单请修改日期。") + analysis.evidence).distinct())
                if (ScreenshotField.AMOUNT !in edited) next = next.copy(
                    amount = analysis.amountCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty())
                if (ScreenshotField.NAME !in edited) next = next.copy(name = analysis.merchant.orEmpty())
                publish(next.copy(recognizing = false))
                refreshSuggestion()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mutable.value.taskId == task) publish(mutable.value.copy(recognizing = false,
                    error = "图片读取或识别失败；可重新选图，也可核对原图后手动填写。"))
            }
        }
    }

    fun edit(field: ScreenshotField, value: String) {
        val s = mutable.value
        if (s.saving || s.savedId != null) return
        edited += field; duplicateInput = null
        val next = when (field) {
            ScreenshotField.AMOUNT -> s.copy(amount = value.take(30))
            ScreenshotField.NAME -> s.copy(name = value.take(200))
            ScreenshotField.NOTE -> s.copy(note = value.take(400))
            ScreenshotField.CATEGORY -> s.copy(categoryKey = value, categoryExplicit = true, classification = null)
            ScreenshotField.DATE -> s.copy(date = value.take(20))
        }
        publish(next.copy(active = true, duplicates = emptyList(), error = null))
        if (field == ScreenshotField.NAME) refreshSuggestion()
    }

    private fun refreshSuggestion() {
        suggestionJob?.cancel()
        val input = mutable.value
        if (input.categoryExplicit || input.saving || input.savedId != null) return
        publish(input.copy(categoryKey = BuiltInCategoryKeys.OTHER, classification = null))
        suggestionJob = viewModelScope.launch {
            try {
                val suggestion = store.suggestClassification(input.merchant, input.name)
                val current = mutable.value
                if (current.taskId == input.taskId && current.name == input.name && current.merchant == input.merchant &&
                    !current.categoryExplicit && !current.saving && current.savedId == null && current.categories.any { it.key == suggestion.categoryKey })
                    publish(current.copy(categoryKey = suggestion.categoryKey, classification = suggestion))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional feedback must not invalidate OCR; saving resolves the latest rules again. */ }
        }
    }

    fun confirm(value: Boolean) {
        if (mutable.value.saving || mutable.value.savedId != null) return
        duplicateInput = null
        publish(mutable.value.copy(confirmed = value, duplicates = emptyList(), error = null))
    }

    fun dismissDuplicate() {
        duplicateInput = null
        publish(mutable.value.copy(duplicates = emptyList()))
    }

    fun fail(message: String) { publish(mutable.value.copy(error = message)) }

    fun save(allowDuplicate: Boolean = false) {
        val s = mutable.value
        if (s.saving || s.recognizing || s.savedId != null || s.replacePending) return
        if (saved.get<Boolean>("requiresNewImage") == true) {
            fail("旧会话草稿不能直接保存，请重新选图并核对账本"); return
        }
        if (!s.confirmed) { fail("请先核对并确认这是人民币成功支出"); return }
        val cents = try {
            require(Regex("^[0-9]+(?:\\.[0-9]{1,2})?$").matches(s.amount.trim()))
            BigDecimal(s.amount.trim()).movePointRight(2).longValueExact()
        } catch (_: Exception) { null }
        val date = try { LocalDate.parse(s.date).takeIf { it.toString() == s.date } }
            catch (_: Exception) { null }
        // Preserve the chosen date, but use the system time at confirmation, not midnight.
        // A duplicate confirmation refers to the same input shown in its warning.
        val spentAt = try {
            if (allowDuplicate && duplicateInput != null && s.duplicates.isNotEmpty()) duplicateInput!!.spentAt
            else now().let { current ->
                date?.atTime(current.toLocalTime())?.atZone(current.zone)?.toInstant()?.toEpochMilli()
            }
        }
            catch (_: Exception) { null }
        if (cents == null || cents !in 1..MoneyLimits.MAX_CENTS) { fail("请输入有效金额，最多两位小数"); return }
        if (s.name.trim().length !in 1..40 || s.note.trim().length > 100) {
            fail("名称必填且最多40字，备注最多100字"); return
        }
        if (spentAt == null || date!!.year !in 1970..9999) { fail("请输入有效日期 YYYY-MM-DD"); return }
        if (s.categories.none { it.key == s.categoryKey }) { fail("请选择有效分类"); return }
        val taskId = s.taskId.ifBlank { UUID.randomUUID().toString() }
        val input = ScreenshotExpenseInput(taskId, s.imageHash, cents, s.name.trim(), s.note.trim(), s.categoryKey, spentAt, s.merchant, s.categoryExplicit)
        // An explicit override is valid only for the exact draft displayed in the warning.
        val override = allowDuplicate && duplicateInput == input && s.duplicates.isNotEmpty()
        publish(s.copy(taskId = taskId, saving = true, error = null, duplicates = emptyList()))
        viewModelScope.launch {
            try {
                when (val result = store.save(input, override)) {
                    is ScreenshotSaveResult.Saved -> publish(mutable.value.copy(saving = false, savedId = result.id))
                    is ScreenshotSaveResult.AlreadySaved -> publish(mutable.value.copy(saving = false, savedId = result.id,
                        error = "这个任务此前已保存，未新增账目；请到账本核对原记录。"))
                    is ScreenshotSaveResult.Duplicate -> {
                        duplicateInput = input
                        publish(mutable.value.copy(saving = false, duplicates = result.matches))
                    }
                    is ScreenshotSaveResult.Invalid -> publish(mutable.value.copy(saving = false, error = result.message))
                    ScreenshotSaveResult.Stale -> publish(mutable.value.copy(saving = false,
                        error = "账本已恢复或正在恢复，请关闭此页并重新导入后保存"))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(mutable.value.copy(saving = false,
                error = "保存未能确认，请重试；同一任务不会重复入账")) }
        }
    }

    private fun restore() = ScreenshotEntryState(
        taskId = saved["task"] ?: "", imageHash = saved["hash"], amount = saved["amount"] ?: "",
        merchant = saved["merchant"], categoryExplicit = saved["categoryExplicit"] ?: false,
        name = saved["name"] ?: "", note = saved["note"] ?: "", categoryKey = saved["category"] ?: BuiltInCategoryKeys.OTHER,
        date = saved["date"] ?: today().toString(), confirmed = saved["confirmed"] ?: false,
        active = saved["active"] ?: false, savedId = saved["savedId"],
        error = when {
            saved.get<Boolean>("requiresNewImage") == true -> "会话或账本已改变，旧草稿仅供参考，请重新选图后保存"
            saved.get<Boolean>("recognizing") == true -> "上次识别被中断，请重新选图或手动填写"
            else -> null
        }
    )

    private fun publish(s: ScreenshotEntryState) {
        mutable.value = s
        saved["task"] = s.taskId; saved["hash"] = s.imageHash
        saved["amount"] = s.amount; saved["name"] = s.name; saved["note"] = s.note
        saved["merchant"] = s.merchant; saved["categoryExplicit"] = s.categoryExplicit
        saved["category"] = s.categoryKey; saved["date"] = s.date; saved["confirmed"] = s.confirmed
        saved["active"] = s.active; saved["savedId"] = s.savedId; saved["recognizing"] = s.recognizing
    }
}
