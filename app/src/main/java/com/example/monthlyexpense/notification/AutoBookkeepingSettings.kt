package com.example.monthlyexpense.notification

import android.content.Context
import com.example.monthlyexpense.notification.parser.PaymentSource

class AutoBookkeepingSettings(context: Context) {
    @Volatile internal var policyRead: (() -> Triple<Boolean, Boolean, Boolean>)? = null
    @Volatile internal var policyChange: ((Boolean?, Boolean?, Boolean?) -> Unit)? = null
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var isEnabled: Boolean
        get() = policyRead?.invoke()?.first ?: preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            policyChange?.let { it(value, null, null); return }
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var isWeChatEnabled: Boolean
        get() = policyRead?.invoke()?.second ?: preferences.getBoolean(KEY_WECHAT_ENABLED, true)
        set(value) {
            policyChange?.let { it(null, value, null); return }
            preferences.edit().putBoolean(KEY_WECHAT_ENABLED, value).apply()
        }

    var isAlipayEnabled: Boolean
        get() = policyRead?.invoke()?.third ?: preferences.getBoolean(KEY_ALIPAY_ENABLED, true)
        set(value) {
            policyChange?.let { it(null, null, value); return }
            preferences.edit().putBoolean(KEY_ALIPAY_ENABLED, value).apply()
        }

    fun isSourceEnabled(source: PaymentSource): Boolean = isEnabled && when (source) {
        PaymentSource.WECHAT -> isWeChatEnabled
        PaymentSource.ALIPAY -> isAlipayEnabled
    }

    fun restoreFromBackup(enabled: Boolean, weChatEnabled: Boolean, alipayEnabled: Boolean): Boolean =
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_WECHAT_ENABLED, weChatEnabled)
            .putBoolean(KEY_ALIPAY_ENABLED, alipayEnabled)
            .commit()

    companion object {
        const val PREFERENCES_NAME = "auto_bookkeeping"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WECHAT_ENABLED = "wechat_enabled"
        private const val KEY_ALIPAY_ENABLED = "alipay_enabled"
    }
}
