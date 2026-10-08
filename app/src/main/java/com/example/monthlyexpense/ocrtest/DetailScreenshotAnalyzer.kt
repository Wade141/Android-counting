package com.example.monthlyexpense.ocrtest

/** A detail page may be only partially visible at large font sizes. */
internal object DetailScreenshotAnalyzer {
    private val title=Regex("^[く〈<‹←]?(账单详情|全部账单)$")
    private val negative=Regex("^[-−–—﹣][¥￥]?[0-9]+(?:\\.[0-9]{1,2})?$")
    private val date=Regex("^[0-9]{4}[-年/][0-9]{1,2}[-月/][0-9]{1,2}(?:日|\\s|$)")

    fun accepts(lines: List<LocatedText>, fields: List<AssociatedField> = FieldAssociator.associate(lines)): Boolean {
        if(lines.any {
            val text=ScreenshotLayout.compact(it.text)
            title.matches(text) || text.startsWith("当前状态") || text.startsWith("交易状态")
        }) return true
        // Title may be unreadable. Require independent detail fields below one signed amount.
        val amount=lines.singleOrNull { it.box!=null && negative.matches(ScreenshotLayout.compact(it.text)) } ?: return false
        val below=fields.filter { it.key.box!!.top>=amount.box!!.bottom }
        return below.any { it.role==FieldRole.DATE && it.values.any { value -> date.containsMatchIn(value.text) } } &&
            below.any { it.role in listOf(FieldRole.ORDER,FieldRole.PAID) && it.values.any { value ->
                Regex("^[0-9]+(?:\\.[0-9]{1,2})?(?:元|人民币|CNY|RMB)",RegexOption.IGNORE_CASE)
                    .containsMatchIn(ScreenshotLayout.compact(value.text))
            } }
    }
}
