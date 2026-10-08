package com.example.monthlyexpense

import com.example.monthlyexpense.classification.*

data class ExpenseCategory(
    val key: String,
    val name: String,
    val colorArgb: Long,
    val builtIn: Boolean,
    val sortOrder: Int
)

object BuiltInCategoryKeys {
    const val FOOD = "FOOD"
    const val ENTERTAINMENT = "ENTERTAINMENT"
    const val TRAVEL = "TRAVEL"
    const val SHOPPING = "SHOPPING"
    const val BILLS = "BILLS"
    const val OTHER = "OTHER"
}

object MoneyLimits {
    /** Highest supported single expense or budget: ¥99,999,999.99. */
    const val MAX_CENTS = 9_999_999_999L
}

enum class ExpenseSource {
    MANUAL,
    WECHAT_AUTO,
    ALIPAY_AUTO,
    SCREENSHOT_OCR
}

data class ExpenseRecord(
    val id: Long,
    val amountCents: Long,
    val category: ExpenseCategory,
    val name: String,
    val note: String,
    val spentAt: Long,
    val source: ExpenseSource,
    val merchant: String?,
    val classificationOrigin: ClassificationOrigin = ClassificationOrigin.LEGACY,
    val classificationHits: List<CategoryRuleHit> = emptyList(),
    val isSpecial: Boolean = false
)
