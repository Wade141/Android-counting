package com.example.monthlyexpense.notification.rules

import com.example.monthlyexpense.notification.RawNotification

/** Best-effort redaction. The caller must show an editable preview before explicitly exporting. */
object NotificationSampleTools {
    fun sanitize(raw: RawNotification): RawNotification {
        fun text(value: String) = value
            .replace(Regex("((?:交易单号|交易号|支付单号|订单号)[：:]\\s*)[A-Za-z0-9]+"), "$1已隐藏编号")
            .replace(Regex("(?:账号|账户|手机号|手机号码)[：:]?\\s*[0-9*]+"), "账号已隐藏")
            .replace(Regex("(?<![0-9])[0-9]{3}\\*{3,}[0-9]{2,}"), "已隐藏账号")
            .replace(Regex("(?<![0-9])[0-9]{7,}(?![0-9])"), "已隐藏编号")
            .replace(Regex("((?:向|在)\\s*)[^，,。\\n:：]{1,40}?(?=(?:平台商户)?(?:付款|支付|消费|有一笔|转账))"), "$1示例商户")
            .replace(Regex("商户[：:][^，,。\\n]{1,40}"), "商户：示例商户")
            .replace(Regex("给[^，,。\\n]{1,40}"), "给示例收款方")
        return raw.copy(title = text(raw.title), text = text(raw.text), bigText = text(raw.bigText),
            textLines = raw.textLines.map(::text), subText = text(raw.subText),
            notificationKey = "sample", channelId = null, groupKey = null, postTime = 1_790_000_000_000)
    }
}
