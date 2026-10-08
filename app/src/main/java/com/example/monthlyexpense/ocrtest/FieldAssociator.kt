package com.example.monthlyexpense.ocrtest

import kotlin.math.abs
import kotlin.math.max

internal enum class FieldRole { PAID, ORDER, MERCHANT, STATUS, DATE, IGNORE }
internal data class AssociatedField(val label: String, val role: FieldRole, val key: LocatedText,
    val values: List<LocatedText>)

/** Associates local text, not screen regions. Multiple possible values remain alternatives. */
internal object FieldAssociator {
    // Observed OCR label variants only. Never rewrite merchant names or numeric values.
    private val aliases=mapOf("订单全额" to "订单金额", "付散方式" to "付款方式")
    private val labels = mapOf(
        "实际支付" to FieldRole.PAID, "实付金额" to FieldRole.PAID, "支付金额" to FieldRole.PAID,
        "实付" to FieldRole.PAID, "订单金额" to FieldRole.ORDER, "订单金颜" to FieldRole.ORDER,
        "订单全额" to FieldRole.ORDER, "付散方式" to FieldRole.IGNORE,
        "商户全称" to FieldRole.MERCHANT, "商家名称" to FieldRole.MERCHANT,
        "当前状态" to FieldRole.STATUS, "交易状态" to FieldRole.STATUS,
        "支付时间" to FieldRole.DATE, "交易时间" to FieldRole.DATE,
        "商品说明" to FieldRole.IGNORE, "商品" to FieldRole.IGNORE, "收单机构" to FieldRole.IGNORE,
        "付款方式" to FieldRole.IGNORE, "支付方式" to FieldRole.IGNORE,
        "交易单号" to FieldRole.IGNORE, "商户单号" to FieldRole.IGNORE, "订单号" to FieldRole.IGNORE,
        "标价" to FieldRole.IGNORE, "原价" to FieldRole.IGNORE, "市场汇率" to FieldRole.IGNORE,
        "超优汇率" to FieldRole.IGNORE, "黄金会员汇率" to FieldRole.IGNORE,
        "优惠" to FieldRole.IGNORE, "支付奖励" to FieldRole.IGNORE, "外语凭证" to FieldRole.IGNORE
    ).entries.sortedByDescending { it.key.length }

    private fun label(line: LocatedText) = labels.firstOrNull {
        ScreenshotLayout.compact(line.text).startsWith(it.key)
    }

    fun associate(lines: List<LocatedText>): List<AssociatedField> {
        val keys = lines.mapNotNull { line -> label(line)?.let { line to it } }
        return keys.map { (key, entry) ->
            val pattern = entry.key.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            val inline = key.text.replaceFirst(Regex("^$pattern\\s*[:：]?\\s*"), "").trim()
            val kb = key.box!!
            val end = keys.map { it.first }.filter { it !== key && it.box!!.top >= kb.bottom }
                .minOfOrNull { it.box!!.top } ?: Float.POSITIVE_INFINITY
            val available = lines.filter { it !== key && label(it) == null && it.box!!.top < end }
            val right = available.filter { it.box!!.left >= kb.right && ScreenshotLayout.sameRow(kb,it.box) }
            val below = available.filter { it.box!!.top >= kb.bottom &&
                it.box.top-kb.bottom <= max(kb.height,it.box.height)*3f &&
                (ScreenshotLayout.aligned(kb,it.box) || it.box.left>=kb.right || abs(it.box.left-kb.left) <= (kb.right-kb.left)*.3f) }
            val seeds = if (inline.isNotEmpty()) listOf(key.copy(text=inline)) else right.ifEmpty {
                val first = below.minByOrNull { it.box!!.top }
                if (first == null) emptyList() else below.filter { ScreenshotLayout.sameRow(first.box!!,it.box!!) }
            }
            val valueSeeds=if(entry.value in listOf(FieldRole.PAID,FieldRole.ORDER)) ScreenshotLayout.amounts(seeds) else seeds
            val values = valueSeeds.map { seed ->
                var combined = seed
                var previous = seed
                for (line in available.sortedBy { it.box!!.top }) {
                    if (entry.value==FieldRole.STATUS &&
                        Regex("^(支付成功|交易成功|退款成功|已退款|全额退款|部分退款|退款中|已退回|交易关闭|支付失败|收款成功|收入|处理中|待支付|支付中)$")
                            .matches(ScreenshotLayout.compact(combined.text))) break
                    if (line.sourceIds.any { it in combined.sourceIds }) continue
                    val p=previous.box!!; val b=line.box!!
                    if (b.top < p.bottom || b.bottom > end) continue
                    if (b.top-p.bottom > max(p.height,b.height)*2.2f) break
                    if (!ScreenshotLayout.aligned(p,b)) continue
                    if (entry.value==FieldRole.MERCHANT &&
                        Regex("^[-−–—﹣+¥￥]?[0-9][0-9,.]*(?:元|人民币|CNY|RMB)?$",RegexOption.IGNORE_CASE).matches(ScreenshotLayout.compact(line.text))) break
                    if (entry.value in listOf(FieldRole.ORDER,FieldRole.PAID) &&
                        !Regex("^[0-9.,()（）¥￥元人民币港币CNYRMB\\s=]+$",RegexOption.IGNORE_CASE).matches(line.text)) break
                    // Do not absorb a new complete payment amount as a continuation of a field.
                    if (Regex("^[-−¥￥]").containsMatchIn(line.text) && entry.value != FieldRole.PAID) break
                    val text = ScreenshotLayout.merchantGroup(listOf(combined,line),false)
                        ?: combined.text
                    combined = ScreenshotLayout.merge(combined,line,
                        if (text == combined.text) combined.text + " " + line.text else text)
                    previous=line
                }
                combined
            }
            AssociatedField(aliases[entry.key] ?: entry.key,entry.value,key,values)
        }
    }
}
