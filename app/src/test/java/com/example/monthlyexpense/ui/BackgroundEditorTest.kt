package com.example.monthlyexpense.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.ui.background.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundEditorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun draggingClampsAtEdgeAndCancelPreservesSavedCrop() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val state = mutableStateOf(BackgroundState(
            saved = BackgroundImage(bitmap, .25f), draft = BackgroundImage(bitmap), editorOpen = true, busy = false))
        rule.setContent {
            BackgroundEditor(state.value, {}, {},
                onCancelPreview = { state.value = state.value.copy(draft = null) },
                onMove = { x, y -> state.value = state.value.copy(draft = state.value.draft!!.copy(x = x, y = y)) },
                onApply = { error("Cancel must not apply") }, onReset = {})
        }
        rule.onNodeWithContentDescription("拖动图片调整背景取景").performTouchInput {
            down(center)
            moveBy(Offset(-2000f, 100f))
            up()
        }
        rule.runOnIdle {
            assertEquals(1f, state.value.draft!!.x)
            assertEquals(.5f, state.value.draft!!.y)
            assertEquals(.25f, state.value.saved!!.x)
        }
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertNull(state.value.draft); assertEquals(.25f, state.value.saved!!.x) }
        rule.onNodeWithText("从相册选择").assertIsDisplayed()
    }

    @Test fun savingDisablesActionsAndOptionsRouteChooseAndReset() {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val state = mutableStateOf(BackgroundState(saved = BackgroundImage(bitmap), editorOpen = true, busy = true))
        var chooses = 0
        var resets = 0
        rule.setContent {
            BackgroundEditor(state.value, { chooses++ }, {}, {}, { _, _ -> }, {}, { resets++ })
        }
        rule.onNodeWithText("从相册选择").assertIsNotEnabled()
        rule.onNodeWithText("恢复默认背景").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(busy = false) }
        rule.onNodeWithText("从相册选择").performClick()
        rule.onNodeWithText("恢复默认背景").performClick()
        rule.runOnIdle { assertEquals(1, chooses); assertEquals(1, resets) }
    }
}
