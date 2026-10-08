package com.example.monthlyexpense.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.notification.parser.PaymentSource
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoBookkeepingSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(
            AutoBookkeepingSettings.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun defaultsRequireExplicitMasterOptIn() {
        val settings = AutoBookkeepingSettings(context)

        assertFalse(settings.isEnabled)
        assertTrue(settings.isWeChatEnabled)
        assertTrue(settings.isAlipayEnabled)
        assertFalse(settings.isSourceEnabled(PaymentSource.WECHAT))
        assertFalse(settings.isSourceEnabled(PaymentSource.ALIPAY))
    }

    @Test
    fun persistsMasterAndProviderSwitches() {
        AutoBookkeepingSettings(context).apply {
            isEnabled = true
            isWeChatEnabled = false
            isAlipayEnabled = true
        }

        val restored = AutoBookkeepingSettings(context)
        assertTrue(restored.isEnabled)
        assertFalse(restored.isWeChatEnabled)
        assertTrue(restored.isAlipayEnabled)
        assertFalse(restored.isSourceEnabled(PaymentSource.WECHAT))
        assertTrue(restored.isSourceEnabled(PaymentSource.ALIPAY))
    }
}
