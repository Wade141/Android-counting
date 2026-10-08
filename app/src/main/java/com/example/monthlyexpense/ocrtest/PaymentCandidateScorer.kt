package com.example.monthlyexpense.ocrtest

/** Scores are rule weights, not calibrated probabilities. */
internal object PaymentCandidateScorer {
    const val PAID = 6
    const val SUCCESS = 3
    const val PROMINENT = 2
    const val MERCHANT = 2
    const val OUTSIDE_TRANSACTION = -6
    fun rank(input: List<FieldCandidate<MoneyValue>>, conflict: Boolean = false): ParsedField<MoneyValue> {
        val ranked = input.groupBy { it.value }.map { (_, group) ->
            val best=group.maxBy { it.score }
            val repeated=group.any { other -> other.sourceIds.isNotEmpty() && best.sourceIds.isNotEmpty() &&
                other.sourceIds.intersect(best.sourceIds).isEmpty() }
            best.copy(score=best.score+if(repeated) 2 else 0, sourceIds=group.flatMap { it.sourceIds }.toSet(),
                reasons=best.reasons + if(repeated) listOf("+2 独立位置的同额候选交叉核验；不作为第二笔付款") else emptyList())
        }.sortedWith(compareByDescending<FieldCandidate<MoneyValue>> { it.score }.thenBy { it.value.cents })
        val quality=when {
            conflict -> FieldQuality.CONFLICT
            ranked.isEmpty() -> FieldQuality.MISSING
            ranked.first().score >= 7 && (ranked.size==1 || ranked.first().score-ranked[1].score>=3) -> FieldQuality.SUPPORTED
            else -> FieldQuality.REVIEW
        }
        return ParsedField(ranked,quality,when(quality) {
            FieldQuality.CONFLICT -> listOf("明确金额或交易状态冲突，不选择支出金额。")
            FieldQuality.REVIEW -> listOf("证据不足或多个候选分数接近，请核对。")
            FieldQuality.MISSING -> listOf("未找到可用的人民币扣款金额候选。")
            else -> emptyList()
        })
    }
}
