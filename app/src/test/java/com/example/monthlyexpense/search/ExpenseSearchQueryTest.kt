package com.example.monthlyexpense.search

import com.example.monthlyexpense.MoneyLimits
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ExpenseSearchQueryTest {
    private val today = LocalDate.of(2026, 9, 30)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun query(word: String = "", filters: ExpenseSearchFilters = ExpenseSearchFilters()) =
        (validateSearch(word, filters, today, zone) as SearchValidationResult.Valid).query

    @Test fun emptyAndChineseQueriesRemainUseful() {
        assertEquals("茶", query(" 茶 ").keyword)
        assertEquals("奶 茶", query(" 奶 茶 ").keyword)
        assertNull(query().fromInclusiveMillis)
        assertNull(query().minAmountCents)
        assertEquals("", query("  ").keyword)
        assertEquals("😀".repeat(100), query("😀".repeat(100)).keyword)
        assertTrue(validateSearch("😀".repeat(101), ExpenseSearchFilters(), today, zone) is SearchValidationResult.Invalid)
    }
    @Test fun moneyIsExactAndInvalidRangesCannotBroadenSearch() {
        assertEquals(2001L, query(filters = ExpenseSearchFilters(minAmountText = "20.01")).minAmountCents)
        assertEquals(0L, query(filters = ExpenseSearchFilters(minAmountText = "0")).minAmountCents)
        assertEquals(MoneyLimits.MAX_CENTS, query(filters = ExpenseSearchFilters(maxAmountText = "99999999.99")).maxAmountCents)
        for (bad in listOf("1.001", "-1", "1e3", "1,000", "100000000", "NaN"))
            assertTrue(bad, validateSearch("", ExpenseSearchFilters(minAmountText = bad), today, zone) is SearchValidationResult.Invalid)
        assertTrue(validateSearch("", ExpenseSearchFilters(minAmountText = "20", maxAmountText = "10"), today, zone) is SearchValidationResult.Invalid)
    }
    @Test fun dateRangesIncludeEndDayAndRespectDaylightSaving() {
        val month = query(filters = ExpenseSearchFilters(datePreset = SearchDatePreset.THIS_MONTH))
        assertEquals(LocalDate.of(2026,9,1).atStartOfDay(zone).toInstant().toEpochMilli(), month.fromInclusiveMillis)
        assertEquals(LocalDate.of(2026,10,1).atStartOfDay(zone).toInstant().toEpochMilli(), month.untilExclusiveMillis)
        val last = query(filters = ExpenseSearchFilters(datePreset = SearchDatePreset.LAST_MONTH))
        assertEquals(LocalDate.of(2026,8,1).atStartOfDay(zone).toInstant().toEpochMilli(), last.fromInclusiveMillis)
        val dst = (validateSearch("", ExpenseSearchFilters(SearchDatePreset.CUSTOM,
            LocalDate.of(2026,3,8), LocalDate.of(2026,3,8)), today, ZoneId.of("America/New_York")) as SearchValidationResult.Valid).query
        assertEquals(23 * 3600_000L, dst.untilExclusiveMillis!! - dst.fromInclusiveMillis!!)
        val leap = query(filters = ExpenseSearchFilters(SearchDatePreset.CUSTOM, LocalDate.of(2024,2,29), LocalDate.of(2024,2,29)))
        assertEquals(24 * 3600_000L, leap.untilExclusiveMillis!! - leap.fromInclusiveMillis!!)
    }
    @Test fun incompleteFutureAndReversedDatesAreRejected() {
        for (filters in listOf(
            ExpenseSearchFilters(SearchDatePreset.CUSTOM),
            ExpenseSearchFilters(SearchDatePreset.CUSTOM, today, today.plusDays(1)),
            ExpenseSearchFilters(SearchDatePreset.CUSTOM, today, today.minusDays(1)),
            ExpenseSearchFilters(categoryKeys = (1..101).map(Int::toString).toSet())
        )) assertTrue(validateSearch("", filters, today, zone) is SearchValidationResult.Invalid)
    }
}
