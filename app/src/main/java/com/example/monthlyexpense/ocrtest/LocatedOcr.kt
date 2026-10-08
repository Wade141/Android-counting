package com.example.monthlyexpense.ocrtest

import java.util.Locale

internal data class OcrBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    companion object {
        fun fromPixels(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): OcrBox {
            require(width > 0 && height > 0)
            return OcrBox(left.toFloat() / width, top.toFloat() / height, right.toFloat() / width, bottom.toFloat() / height)
        }
    }
}

internal data class LocatedText(val text: String, val box: OcrBox?, val elements: List<LocatedText> = emptyList(),
    val sourceIds: Set<Int> = emptySet())

internal fun List<LocatedText>.coordinateReport(): String = buildString {
    appendLine("归一化坐标 [左, 上, 右, 下]，左上角为(0,0)，右下角为(1,1)。按上→下排序；缺失坐标不参与解析。")
    fun appendItem(prefix: String, item: LocatedText) {
        val box = item.box
        val coordinates = if (box == null) "无坐标" else String.format(Locale.ROOT,
            "[%.4f, %.4f, %.4f, %.4f]", box.left, box.top, box.right, box.bottom)
        appendLine("$prefix $coordinates ${item.text}")
    }
    sortedWith(compareBy({ it.box?.top ?: 1f }, { it.box?.left ?: 1f })).forEachIndexed { index, line ->
        appendItem("行${index + 1}", line)
        line.elements.forEach { appendItem("  元素", it) }
    }
}
