package com.example.monthlyexpense.search

import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.MoneyLimits
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class SearchDatePreset(val label: String) {
    ALL("全部日期"), TODAY("今天"), THIS_MONTH("本月"), LAST_MONTH("上月"), CUSTOM("自定义日期")
}

data class ExpenseSearchFilters(
    val datePreset: SearchDatePreset = SearchDatePreset.ALL,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val categoryKeys: Set<String> = emptySet(),
    val minAmountText: String = "",
    val maxAmountText: String = "",
    val sources: Set<ExpenseSource> = emptySet()
)

data class ExpenseSearchQuery(
    val keyword: String,
    val fromInclusiveMillis: Long?,
    val untilExclusiveMillis: Long?,
    val categoryKeys: Set<String>,
    val minAmountCents: Long?,
    val maxAmountCents: Long?,
    val sources: Set<ExpenseSource>
)
data class ExpenseSearchCursor(val spentAt: Long, val id: Long)
data class ExpenseSearchSummary(val count: Long, val totalCents: Long)
data class ExpenseSearchPage(
    val items: List<ExpenseRecord>,
    val nextCursor: ExpenseSearchCursor?,
    val hasMore: Boolean,
    val summary: ExpenseSearchSummary?
)
sealed interface SearchValidationResult {
    data class Valid(val query: ExpenseSearchQuery) : SearchValidationResult
    data class Invalid(val field: String, val message: String) : SearchValidationResult
}

fun validateSearch(keyword: String, filters: ExpenseSearchFilters, today: LocalDate, zoneId: ZoneId): SearchValidationResult {
    fun invalid(field: String, message: String) = SearchValidationResult.Invalid(field, message)
    val word = keyword.trim()
    if (word.codePointCount(0, word.length) > 100) return invalid("keyword", "关键词最多 100 个字")
    if (filters.categoryKeys.size > 100) return invalid("category", "最多选择 100 个分类")
    fun amount(raw: String): Long? {
        val value = raw.trim()
        if (!Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(value)) return null
        return runCatching { BigDecimal(value).movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { it in 0..MoneyLimits.MAX_CENTS }
    }
    val min = amount(filters.minAmountText)
    val max = amount(filters.maxAmountText)
    if (filters.minAmountText.isNotBlank() && min == null) return invalid("amount", "最低金额需为有效金额，最多两位小数")
    if (filters.maxAmountText.isNotBlank() && max == null) return invalid("amount", "最高金额需为有效金额，最多两位小数")
    if (min != null && max != null && min > max) return invalid("amount", "最低金额不能大于最高金额")
    val month = YearMonth.from(today)
    val dates = when (filters.datePreset) {
        SearchDatePreset.ALL -> null
        SearchDatePreset.TODAY -> today to today
        SearchDatePreset.THIS_MONTH -> month.atDay(1) to month.atEndOfMonth()
        SearchDatePreset.LAST_MONTH -> month.minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        SearchDatePreset.CUSTOM -> {
            val start = filters.startDate ?: return invalid("date", "请选择开始日期")
            val end = filters.endDate ?: return invalid("date", "请选择结束日期")
            if (start > end) return invalid("date", "开始日期不能晚于结束日期")
            if (end > today) return invalid("date", "结束日期不能晚于今天")
            start to end
        }
    }
    val bounds = try {
        dates?.let { it.first.atStartOfDay(zoneId).toInstant().toEpochMilli() to
            it.second.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() }
    } catch (_: RuntimeException) { return invalid("date", "日期超出支持范围") }
    return SearchValidationResult.Valid(ExpenseSearchQuery(word, bounds?.first, bounds?.second,
        filters.categoryKeys.toSet(), min, max, filters.sources.toSet()))
}
