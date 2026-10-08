package com.example.monthlyexpense.ocrtest

import java.math.BigDecimal

/** Diagnostic extraction only. Missing fields never authorize automatic bookkeeping. */
internal object ScreenshotAnalyzer {
    private val number=Regex("^([-−–—﹣+＋]?)\\s*([¥￥]?)\\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?)\\s*(人民币|元|CNY|RMB)?$",RegexOption.IGNORE_CASE)
    // The outlined success icon may be read as O, o or ©. Only allow one
    // known icon prefix; a success substring in arbitrary prose is not evidence.
    private val success=Regex("^[Oo©○✓✔]?(支付成功|交易成功)$")
    private val suspectedSuccess=Regex("^(交易戌功|支付戌功)$")
    private val unsafe=Regex("^(退款成功|已退款|全额退款|部分退款|退款中|已退回|交易关闭|支付失败|收款成功|收入|处理中|待支付|支付中)$")
    private val noise=Regex("账单|详情|返回|首页|完成|完戍|VPN|KB/s|支付|交易|付款|收款|优惠|汇率|汇奉|节省|领取|免费|去看看|进入|商品|机构|订单|标价|港币|人民币|储蓄|信用卡|coupon|更多|对账",RegexOption.IGNORE_CASE)
    private data class AmountLine(val line: LocatedText,val value: MoneyValue,val negative: Boolean,val paid: Boolean=false)
    private fun parse(line: LocatedText,paid: Boolean=false): AmountLine? {
        val match=number.matchEntire(line.text.trim()) ?: return null
        val sign=match.groupValues[1]
        if(sign in listOf("+","＋")) return null
        val cents=runCatching { BigDecimal(match.groupValues[3].replace(",","")).movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { it in 1..9_999_999_999L } ?: return null
        return AmountLine(line,MoneyValue(cents,if(match.groupValues[4].isNotEmpty()) "CNY" else null),sign.isNotEmpty(),paid)
    }
    fun analyze(input: List<LocatedText>): ScreenshotAnalysis {
        val lines=ScreenshotLayout.prepare(input)
        val fields=FieldAssociator.associate(lines)
        val detail=DetailScreenshotAnalyzer.accepts(lines,fields)
        val statusFields=fields.filter { it.role==FieldRole.STATUS }
        val statusLines=(statusFields.flatMap { it.values } + lines.filter {
            success.matches(ScreenshotLayout.compact(it.text)) || unsafe.matches(ScreenshotLayout.compact(it.text)) ||
                suspectedSuccess.matches(ScreenshotLayout.compact(it.text))
        }).distinctBy { ScreenshotLayout.compact(it.text) to it.sourceIds }
        val successLines=statusLines.filter { success.matches(ScreenshotLayout.compact(it.text)) }
        val unsafeLines=statusLines.filter { unsafe.matches(ScreenshotLayout.compact(it.text)) }
        val suspectedLines=statusLines.filter { suspectedSuccess.matches(ScreenshotLayout.compact(it.text)) }
        val statusValues=statusLines.map { FieldCandidate(it.text,0,it.sourceIds,listOf("交易状态文字")) }
        val statusQuality=when {
            (successLines.isNotEmpty() || suspectedLines.isNotEmpty()) && unsafeLines.isNotEmpty() -> FieldQuality.CONFLICT
            statusValues.isEmpty() -> FieldQuality.MISSING
            statusValues.map { if(success.matches(ScreenshotLayout.compact(it.value)) || suspectedSuccess.matches(ScreenshotLayout.compact(it.value))) "SUCCESS" else ScreenshotLayout.compact(it.value) }.distinct().size>1 -> FieldQuality.CONFLICT
            suspectedLines.isNotEmpty() -> FieldQuality.REVIEW
            successLines.isNotEmpty() || unsafeLines.isNotEmpty() -> FieldQuality.SUPPORTED
            else -> FieldQuality.REVIEW
        }
        val status=ParsedField(statusValues,statusQuality,when {
            statusValues.isEmpty() -> listOf("支付状态未显示或未识别；不推断支付成功。")
            suspectedLines.isNotEmpty() -> listOf("原始状态「${suspectedLines.joinToString { it.text }}」疑似成功，待确认；不视为已确认成功。")
            else -> emptyList()
        })
        if(!detail && successLines.isEmpty()) return ScreenshotAnalysis(evidence=listOf("未找到支付成功或账单详情证据，不从普通图片猜测付款。"),status=status)
        val owned=fields.flatMap { listOf(it.key)+it.values }.flatMap { it.sourceIds }.toSet()
        val free=lines.filter { it.sourceIds.intersect(owned).isEmpty() }
        val normalized=joinMinus(ScreenshotLayout.amounts(free))
        val raw=normalized.mapNotNull { parse(it) }.filter { !detail || it.negative }.toMutableList()
        fields.filter { it.role==FieldRole.PAID }.flatMap { it.values }.mapNotNullTo(raw) { parse(it,true) }
        val fieldMerchants=fields.filter { it.role==FieldRole.MERCHANT }.flatMap { it.values }
            .filter { it.text.any(Char::isLetter) }.map { FieldCandidate(it.text,8,it.sourceIds,listOf("商户名称字段（支持多行）")) }
        val method=fields.filter { it.label in listOf("付款方式","支付方式") }.minByOrNull { it.key.box!!.top }
        val detailStart=fields.filter { it.label in listOf("付款方式","支付方式","标价","原价","市场汇率","黄金会员汇率") }
            .minOfOrNull { it.key.box!!.top }
        val firstField=fields.minOfOrNull { it.key.box!!.top }
        val anchor=successLines.minByOrNull { it.box!!.top }
        fun merchantFor(main: AmountLine): LocatedText? {
            val b=main.line.box!!
            val eligible=free.filter { it !== main.line && it.text.any(Char::isLetter) && !noise.containsMatchIn(it.text) &&
                number.matchEntire(it.text)==null && it.box!!.height>=b.height*.18f }
            val above=eligible.filter { it.box!!.bottom<=b.top && (detail || anchor==null || it.box.top>=anchor.box!!.bottom) }
            val below=eligible.filter { it.box!!.top>=b.bottom && (method==null || it.box.bottom<=method.key.box!!.top) }
            val chosen=if(above.isNotEmpty()) above else if(!detail) below else emptyList()
            if(chosen.isEmpty()) return null
            val fromBottom=above.isNotEmpty()
            val text=ScreenshotLayout.merchantGroup(chosen,fromBottom) ?: return null
            val ordered=chosen.sortedBy { it.box!!.top }.let { if(fromBottom) it.reversed() else it }
            var merged=ordered.first()
            for(next in ordered.drop(1)) {
                if(!text.contains(next.text)) break
                merged=ScreenshotLayout.merge(merged,next,text)
            }
            return merged.copy(text=text)
        }
        val orders=fields.filter { it.role==FieldRole.ORDER }.flatMap { it.values }.mapNotNull { value ->
            val yuan=Regex("^([0-9]+(?:\\.[0-9]{1,2})?元)(?:[（(]|$)").find(ScreenshotLayout.compact(value.text))?.groupValues?.get(1)
            yuan?.let { parse(value.copy(text=it)) }
        }
        val yuanEvidence=(raw.filter { it.paid && it.value.currency=="CNY" }+orders)
        for(i in raw.indices) {
            val original=raw[i]
            if(original.value.currency==null && yuanEvidence.any { it.value.cents==original.value.cents })
                raw[i]=original.copy(value=original.value.copy(currency="CNY"))
        }
        val maxHeight=raw.maxOfOrNull { it.line.box!!.height } ?: 0f
        val scored=raw.map { candidate ->
            val b=candidate.line.box!!
            val reasons=mutableListOf<String>()
            var score=0
            fun add(points:Int,reason:String) {score+=points;reasons+="${if(points>=0) "+" else ""}$points $reason"}
            if(candidate.paid) add(PaymentCandidateScorer.PAID,"关联明确支付金额字段")
            val appropriate=detail || anchor?.let { b.top>=it.box!!.bottom }==true
            if(successLines.isNotEmpty() && appropriate) add(PaymentCandidateScorer.SUCCESS,"与成功状态的交易结构一致")
            if(b.height>=maxHeight*.75f) add(PaymentCandidateScorer.PROMINENT,"金额文字框相对突出")
            if(fieldMerchants.isNotEmpty() || merchantFor(candidate)!=null) add(PaymentCandidateScorer.MERCHANT,"存在关联商户候选")
            if(!candidate.paid && ((detail && firstField!=null && b.top>firstField) ||
                (!detail && detailStart!=null && b.top>detailStart)))
                add(PaymentCandidateScorer.OUTSIDE_TRANSACTION,"位于明细/支付方式之后，可能是附加内容")
            if(!appropriate) add(PaymentCandidateScorer.OUTSIDE_TRANSACTION,"不在成功状态后的付款结构中")
            FieldCandidate(candidate.value,score,candidate.line.sourceIds,reasons)
        }
        val explicit=raw.filter { it.paid }.map { it.value.cents }.distinct()
        val orphanFractions=(normalized + fields.filter { it.role==FieldRole.PAID }.flatMap { it.values })
            .filter { Regex("^\\.[0-9]{1,2}(?:元|人民币|CNY|RMB)?$",RegexOption.IGNORE_CASE).matches(ScreenshotLayout.compact(it.text)) }
        val fragmentConflict=orphanFractions.any { fragment -> raw.any {
            ScreenshotLayout.sameRow(fragment.box!!,it.line.box!!) &&
                fragment.sourceIds.intersect(it.line.sourceIds).isEmpty()
        } }
        val negative=raw.filter { it.negative }.map { it.value.cents }.distinct()
        val orderConflict=detail && negative.size==1 && orders.any { it.value.cents!=negative.single() }
        val blocked=unsafeLines.isNotEmpty() || statusQuality==FieldQuality.CONFLICT
        val paidConflict=explicit.size>1 || (negative.size==1 && explicit.any { it!=negative.single() })
        val conflict=paidConflict || orderConflict || blocked || fragmentConflict
        val amount=PaymentCandidateScorer.rank(scored,conflict)
        val top=amount.candidates.firstOrNull()
        val unambiguous=amount.candidates.size==1 || amount.quality==FieldQuality.SUPPORTED
        val selected=top?.takeIf { unambiguous && !conflict && it.score>=0 }
        val main=selected?.let { s -> raw.firstOrNull { it.value==s.value && it.line.sourceIds.intersect(s.sourceIds).isNotEmpty() } }
        val merchantReference=main ?: raw.filter { !it.paid }.maxByOrNull { it.line.box!!.height }
        val fallback=merchantReference?.let(::merchantFor)?.let { FieldCandidate(it.text,4,it.sourceIds,listOf("主金额邻接名称，仅为商户候选")) }
        val merchants=fieldMerchants.ifEmpty { listOfNotNull(fallback) }.distinctBy { ScreenshotLayout.compact(it.value) }
        val merchantQuality=when {
            merchants.isEmpty() -> FieldQuality.MISSING
            merchants.size>1 -> FieldQuality.CONFLICT
            fieldMerchants.isNotEmpty() -> FieldQuality.SUPPORTED
            else -> FieldQuality.REVIEW
        }
        val merchantField=ParsedField(merchants,merchantQuality,if(merchants.size>1) listOf("多个商户字段不一致，请核对。") else emptyList())
        val currencySources=selected?.let { s -> s.sourceIds + yuanEvidence.filter { it.value.cents==s.value.cents }.flatMap { it.line.sourceIds } } ?: emptySet()
        val currency=if(selected?.value?.currency=="CNY") ParsedField(listOf(FieldCandidate("人民币",0,currencySources,listOf("主金额或同额支付/订单字段带元、人民币、CNY标记"))),FieldQuality.SUPPORTED)
            else ParsedField<String>(warnings=listOf("不确定币种：人民币环境下也需核对；¥标记单独不证明币种。"))
        val direction: ParsedField<String> = when {
            blocked -> ParsedField(listOf(FieldCandidate("非可确认的成功支出",0,statusLines.flatMap { it.sourceIds }.toSet(),listOf("状态不允许作为普通支出"))),FieldQuality.CONFLICT)
            main!=null -> ParsedField(listOf(FieldCandidate(if(main.negative) "支出（负号）" else "支出候选",0,main.line.sourceIds,listOf("仅凭当前可见交易证据"))),
                if(successLines.isNotEmpty() && statusQuality==FieldQuality.SUPPORTED) FieldQuality.SUPPORTED else FieldQuality.REVIEW)
            else -> ParsedField()
        }
        val evidence=mutableListOf("采用字段关联与相对文字框关系，不限制主金额必须居中；规则分数不是准确率。")
        evidence+=status.warnings
        evidence+=amount.warnings
        evidence+=currency.warnings
        fields.filter { ScreenshotLayout.compact(it.key.text).startsWith(it.label).not() }.forEach {
            evidence+="字段标签「${it.key.text}」按受控别名关联「${it.label}」；保留原始文字。"
        }
        evidence+="商品、付款方式、原价、优惠等多行字段按归属排除；不做汇率换算。"
        if(orders.any { it.value.cents==selected?.value?.cents }) evidence+="订单中的元金额与主金额交叉核验一致；不提取外币原价。"
        if(orderConflict) evidence+="主金额与订单元金额不一致，暂停选择。"
        if(fragmentConflict) evidence+="金额同行存在无法安全拼接的小数片段，不能丢弃小数后选定整数金额。"
        top?.reasons?.let { evidence+=it }
        fields.filter { it.role==FieldRole.DATE }.flatMap { it.values }.forEach { evidence+="页面支付时间（仅供参考）：${it.text}；不改变系统记账日期。" }
        evidence+="部分字段缺失时保留其他候选；需人工核对，不会自动保存账目。"
        return ScreenshotAnalysis(selected?.value?.cents,merchants.singleOrNull()?.value,evidence,
            if(detail) "账单详情（可能不完整）" else "支付成功页",amount,merchantField,status,currency,direction)
    }
    private fun joinMinus(lines:List<LocatedText>):List<LocatedText> {
        val result=lines.toMutableList()
        for(sign in lines.filter { ScreenshotLayout.compact(it.text) in listOf("-","−","–","—","﹣") }) {
            val matches=result.filter { parse(it)!=null && !parse(it)!!.negative && ScreenshotLayout.sameRow(sign.box!!,it.box!!) &&
                it.box.left>=sign.box.right && it.box.left-sign.box.right<=(it.box.right-it.box.left)/it.text.length.coerceAtLeast(1)*1.5f }
            if(matches.size==1) {val value=matches.single();result.remove(sign);result.remove(value);result+=ScreenshotLayout.merge(sign,value,"-"+value.text)}
        }
        return result
    }
}
