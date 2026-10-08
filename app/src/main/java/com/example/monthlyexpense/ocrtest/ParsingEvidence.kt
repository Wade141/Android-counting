package com.example.monthlyexpense.ocrtest

internal enum class FieldQuality(val label: String) {
    SUPPORTED("证据较充分"), REVIEW("待确认"), MISSING("缺失"), CONFLICT("冲突")
}
internal data class FieldCandidate<T>(val value: T, val score: Int, val sourceIds: Set<Int>, val reasons: List<String>)
internal data class ParsedField<T>(val candidates: List<FieldCandidate<T>> = emptyList(),
    val quality: FieldQuality = FieldQuality.MISSING, val warnings: List<String> = emptyList())
internal data class MoneyValue(val cents: Long, val currency: String? = null)
internal data class ScreenshotAnalysis(
    val amountCents: Long? = null, val merchant: String? = null, val evidence: List<String> = emptyList(),
    val pageType: String = "未确定", val amount: ParsedField<MoneyValue> = ParsedField(),
    val merchantField: ParsedField<String> = ParsedField(), val status: ParsedField<String> = ParsedField(),
    val currency: ParsedField<String> = ParsedField(), val direction: ParsedField<String> = ParsedField()
)
