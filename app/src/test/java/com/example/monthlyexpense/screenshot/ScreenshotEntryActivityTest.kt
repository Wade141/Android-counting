package com.example.monthlyexpense.screenshot

import android.content.Intent
import android.net.Uri
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotEntryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun intent() = Intent(ApplicationProvider.getApplicationContext<Context>(), ScreenshotEntryActivity::class.java)
    @Test fun coldInvalidShareShowsErrorAndCannotCreateAnExpense() {
        ActivityScenario.launch<ScreenshotEntryActivity>(intent().setAction(Intent.ACTION_SEND).setType("image/png")).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ScreenshotEntryViewModel::class.java]
                assertNotNull(model.state.value.error)
                assertFalse(model.state.value.active)
                assertNull(model.state.value.savedId)
            }
        }
    }

    @Test fun warmShareAsksBeforeReplacingExistingManualCorrections() {
        ActivityScenario.launch<ScreenshotEntryActivity>(intent()).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ScreenshotEntryViewModel::class.java]
                model.edit(ScreenshotField.NAME, "正在修改的草稿")
                model.edit(ScreenshotField.AMOUNT, "18.50")
                val scenarioIntent = activity.intent
                val share = intent().setAction(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://test/next"))
                ScreenshotEntryActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply { isAccessible = true }.invoke(activity, share)
                assertTrue(model.state.value.replacePending)
                assertEquals("18.50", model.state.value.amount)
                model.resolveReplacement(false)
                assertEquals("正在修改的草稿", model.state.value.name)
                // ActivityScenario tracks lifecycle by its launch intent; onNewIntent correctly
                // changes the production intent, so restore the fixture identity before close().
                activity.intent = scenarioIntent
            }
        }
    }

    @Test fun rotationRetainsStructuredDraftWithoutReprocessingLaunchIntent() {
        ActivityScenario.launch<ScreenshotEntryActivity>(intent()).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ScreenshotEntryViewModel::class.java]
                model.edit(ScreenshotField.NAME, "旋转草稿")
                model.edit(ScreenshotField.AMOUNT, "8.88")
            }
            scenario.recreate()
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val restored = ViewModelProvider(activity)[ScreenshotEntryViewModel::class.java]
                assertEquals("旋转草稿", restored.state.value.name)
                assertEquals("8.88", restored.state.value.amount)
                assertFalse(restored.state.value.recognizing)
                assertFalse(restored.state.value.confirmed)
            }
        }
    }
}
