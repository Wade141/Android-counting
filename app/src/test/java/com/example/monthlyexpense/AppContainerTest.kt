package com.example.monthlyexpense

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    @Test
    fun applicationCreatesOneStableContainerAndDependencyGraph() {
        val application =
            ApplicationProvider.getApplicationContext<MonthlyExpenseApplication>()

        assertSame(application.container, application.container)
        assertSame(application.container.database, application.container.database)
        assertSame(application.container.expenseRepository, application.container.expenseRepository)
        assertSame(application.container.backupManager, application.container.backupManager)
    }

    @Test
    fun containerSuppliesTheExpenseViewModelFactoryFromItsDependencies() {
        val application =
            ApplicationProvider.getApplicationContext<MonthlyExpenseApplication>()

        assertNotNull(application.container.expenseViewModelFactory())
    }
}
