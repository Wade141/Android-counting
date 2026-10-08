package com.example.monthlyexpense.ocrtest

import kotlin.math.max
import kotlin.math.min
import java.text.Normalizer

/** All distances are relative to neighboring text, not a device's pixel dimensions. */
internal object ScreenshotLayout {
    private val labels = listOf("支付成功", "付款方式", "市场汇率", "标价", "境外专属优惠", "支付失败", "退款成功", "收款成功",
        "账单详情", "交易成功", "当前状态", "支付时间", "商户全称", "订单金额")
    fun compact(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")

    fun prepare(input: List<LocatedText>): List<LocatedText> {
        val lines = input.sortedWith(compareBy({ it.box?.top ?: 1f }, { it.box?.left ?: 1f })).mapIndexed { index, line ->
            line.copy(sourceIds = line.sourceIds.ifEmpty { setOf(index + 1) })
        }.filter { it.box != null && it.box.height > 0 && it.box.right > it.box.left &&
            listOf(it.box.left,it.box.top,it.box.right,it.box.bottom).all(Float::isFinite) }.map {
            it.copy(text = Normalizer.normalize(it.text, Normalizer.Form.NFKC).trim())
        }.groupBy { it.text to it.box }.values.map { copies ->
            copies.first().copy(sourceIds = copies.flatMap { it.sourceIds }.toSet())
        }.toMutableList()
        // Known labels only: do not concatenate arbitrary adjacent numeric or merchant rows.
        for (label in labels) {
            val first = lines.firstOrNull { compact(it.text).isNotEmpty() && label.startsWith(compact(it.text)) && compact(it.text) != label }
                ?: continue
            val second = lines.firstOrNull { it !== first && compact(first.text) + compact(it.text) == label && adjacent(first, it) }
                ?: continue
            lines.remove(first)
            lines.remove(second)
            lines.add(merge(first, second, label))
        }
        return lines.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left }))
    }

    private fun adjacent(a: LocatedText, b: LocatedText): Boolean {
        val x = a.box!!; val y = b.box!!
        if (sameRow(x, y)) return y.left >= x.right - glyphWidth(a) * .3f && y.left - x.right <= glyphWidth(a) * 2
        return y.top >= x.bottom - min(x.height, y.height) * .2f &&
            y.top - x.bottom <= max(x.height, y.height) * 1.2f && aligned(x, y)
    }

    fun sameRow(a: OcrBox, b: OcrBox) =
        min(a.bottom, b.bottom) - max(a.top, b.top) >= min(a.height, b.height) * .5f

    fun aligned(a: OcrBox, b: OcrBox) =
        min(a.right, b.right) - max(a.left, b.left) >= min(a.right - a.left, b.right - b.left) * .45f

    private fun glyphWidth(line: LocatedText) = (line.box!!.right - line.box.left) / compact(line.text).length.coerceAtLeast(1)

    fun merge(a: LocatedText, b: LocatedText, text: String) = LocatedText(text,
        OcrBox(min(a.box!!.left, b.box!!.left), min(a.box.top, b.box.top),
            max(a.box.right, b.box.right), max(a.box.bottom, b.box.bottom)), sourceIds = a.sourceIds + b.sourceIds)

    fun amounts(region: List<LocatedText>): List<LocatedText> {
        val rows = region.toMutableList()
        // A single OCR line may contain a split decimal. Never turn "30 89" into "3089".
        for (i in rows.indices) rows[i] = rows[i].copy(text = rows[i].text
            .replace(Regex("(?<=\\d)\\s*\\.\\s*(?=\\d)"), "."))
        val integer = Regex("^(?:¥\\s*)?[0-9]+$")
        val fraction = Regex("^\\.[0-9]{1,2}(?:元|人民币|CNY|RMB)?$", RegexOption.IGNORE_CASE)
        for (part in rows.toList()) {
            if (!fraction.matches(part.text)) continue
            val matches = rows.filter { it !== part && integer.matches(it.text) && sameRow(it.box!!, part.box!!) &&
                part.box.left >= it.box.right && part.box.left - it.box.right <= max(glyphWidth(it), glyphWidth(part)) * 1.2f }
            if (matches.size != 1) continue
            val base = matches.single()
            rows.remove(base); rows.remove(part)
            rows.add(merge(base, part, base.text + part.text))
        }
        for (symbol in rows.toList().filter { compact(it.text) == "¥" }) {
            val matches = rows.filter { it !== symbol && Regex("^[0-9][0-9,.]*$").matches(it.text) &&
                sameRow(symbol.box!!, it.box!!) && it.box.left >= symbol.box.right &&
                it.box.left - symbol.box.right <= max(glyphWidth(symbol), glyphWidth(it)) * 1.2f }
            if (matches.size != 1) continue
            val amount = matches.single()
            rows.remove(symbol); rows.remove(amount)
            rows.add(merge(symbol, amount, "¥" + amount.text))
        }
        return rows
    }

    fun merchantGroup(lines: List<LocatedText>, fromBottom: Boolean): String? {
        if (lines.isEmpty()) return null
        val ordered = lines.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left }))
            .let { if (fromBottom) it.reversed() else it }
        val group = mutableListOf(ordered.first())
        for (line in ordered.drop(1)) {
            val previous = group.last().box!!
            val box = line.box!!
            val gap = if (fromBottom) previous.top - box.bottom else box.top - previous.bottom
            if (gap > max(box.height, previous.height) * 1.3f || !aligned(box, previous)) break
            group.add(line)
        }
        val text = group.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left })).map { it.text }
        return text.reduce { a, b ->
            val chinese = a.lastOrNull()?.let { it in '\u3400'..'\u9fff' } == true &&
                b.firstOrNull()?.let { it in '\u3400'..'\u9fff' } == true
            a + (if (chinese) "" else " ") + b
        }
    }
}
