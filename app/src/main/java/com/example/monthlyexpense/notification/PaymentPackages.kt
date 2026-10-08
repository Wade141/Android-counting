package com.example.monthlyexpense.notification

object PaymentPackages {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"

    fun isSupported(packageName: String): Boolean =
        packageName == WECHAT || packageName == ALIPAY
}
