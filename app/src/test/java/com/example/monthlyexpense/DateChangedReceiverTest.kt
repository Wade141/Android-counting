package com.example.monthlyexpense

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DateChangedReceiverTest {
    @Test fun calendarChangeInvalidatesBothHomeDomainsWithoutOpeningDatabaseOnReceiverThread() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val changes = (context as MonthlyExpenseApplication).container.dataChanges
        val before = changes.homeRefreshes.value
        val existed = context.getDatabasePath("expenses.db").exists()
        DateChangedReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(before.ledger + 1, changes.homeRefreshes.value.ledger)
        assertEquals(before.settings + 1, changes.homeRefreshes.value.settings)
        assertEquals(existed, context.getDatabasePath("expenses.db").exists())
        val after = changes.revisions.value
        DateChangedReceiver().onReceive(context, Intent("unrelated"))
        assertEquals(after, changes.revisions.value)
    }
}
