package com.example.monthlyexpense.notification.rules

/** Restricted vocabulary only; imported packages cannot execute regex or override safety gates. */
data class PaymentRules(
    val version: String,
    val successPhrases: List<String>,
    val paidLabels: List<String>,
    val weChatTitles: List<String>,
    val alipayTitles: List<String>
) {
    companion object {
        val builtIn = PaymentRules(
            "2026.09.23.1",
            listOf("已付款", "已支付", "已扣款", "已续费", "付款成功", "支付成功", "消费成功",
                "扣款成功", "扣费成功", "代扣成功", "续费成功", "付款完成", "支付完成", "消费完成",
                "扣款完成", "成功付款", "成功支付", "成功扣款", "成功扣费", "成功代扣", "成功续费",
                "成功转账", "已转账", "自动扣款", "自动扣费", "免密支付成功", "免密付款成功"),
            listOf("实付", "实际支付", "实际付款", "付款金额", "支付金额", "消费金额", "扣款金额", "扣费金额"),
            listOf("微信支付"), listOf("支付宝", "支付宝支付", "交易提醒", "支付成功通知")
        )
    }
}
