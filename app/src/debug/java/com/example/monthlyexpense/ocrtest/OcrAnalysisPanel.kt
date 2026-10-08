package com.example.monthlyexpense.ocrtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

/** Parent owns scrolling. Each field can wrap independently at accessibility font sizes. */
@Composable
internal fun OcrAnalysisPanel(result: ScreenshotAnalysis) {
    fun money(cents: Long) = BigDecimal.valueOf(cents,2).toPlainString()
    SelectionContainer {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("页面：${result.pageType}")
            Text("金额：${result.amountCents?.let(::money) ?: "未确定"}（${result.amount.quality.label}）")
            Text("商户：${result.merchant ?: "未确定"}（${result.merchantField.quality.label}）")
            Text("状态：${result.status.candidates.joinToString(" / ") { it.value }.ifEmpty { "未确定" }}（${result.status.quality.label}）")
            Text("币种：${result.currency.candidates.joinToString(" / ") { it.value }.ifEmpty { "未确定" }}（${result.currency.quality.label}）")
            Text("方向：${result.direction.candidates.joinToString(" / ") { it.value }.ifEmpty { "未确定" }}（${result.direction.quality.label}）")
            Text("需要核对：候选不是已验证账目；规则分数不是准确率。",color=MaterialTheme.colorScheme.error)
            result.amount.candidates.take(3).forEachIndexed { index,candidate ->
                Text("候选${index+1}：${money(candidate.value.cents)}；${candidate.score}分；币种：${candidate.value.currency ?: "未确定"}")
                Text("来源行：${candidate.sourceIds.sorted().joinToString()}；${if(result.amountCents==candidate.value.cents) "当前金额候选，仍需核对" else "未选定：证据不足、排名靠后或存在冲突"}")
                candidate.reasons.forEach { Text("• $it") }
            }
            result.merchantField.candidates.forEach { candidate ->
                Text("商户候选：${candidate.value}；来源行：${candidate.sourceIds.sorted().joinToString()}")
                candidate.reasons.forEach { Text("• $it") }
            }
            Text("判断依据：",style=MaterialTheme.typography.titleMedium)
            (result.evidence + result.merchantField.warnings).distinct().forEach { Text("• $it") }
            Text("仅供核对，不会保存账目。")
        }
    }
}
