package com.example.monthlyexpense.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.monthlyexpense.classification.*

@Composable
internal fun ClassificationReason(origin: ClassificationOrigin, hits: List<CategoryRuleHit>) {
    val text = when (origin) {
        ClassificationOrigin.CONFLICT -> "分类待确认 · 多个分类命中，请手动选择"
        ClassificationOrigin.KEYWORD -> hits.firstOrNull()?.let {
            "自动归类：${if (it.field == CategoryMatchField.MERCHANT) "商户" else "名称"}${if (it.mode == KeywordMatchMode.EXACT) "完全一致" else "包含"}“${it.keyword}”"
        }
        ClassificationOrigin.HISTORY -> "历史建议"
        else -> null
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (origin == ClassificationOrigin.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
