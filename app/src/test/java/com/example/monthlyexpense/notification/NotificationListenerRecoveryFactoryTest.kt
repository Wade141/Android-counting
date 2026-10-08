package com.example.monthlyexpense.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationListenerRecoveryFactoryTest {
    @Test
    fun activityAndServiceShareOneProcessRecoveryCoordinator() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertSame(
            NotificationListenerRecovery.forContext(context),
            NotificationListenerRecovery.forContext(context)
        )
    }
}
