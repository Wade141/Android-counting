package com.example.monthlyexpense.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.ui.forms.ClockTimeDialog
import java.time.LocalTime
import kotlin.math.sin
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClockTimeDialogTest {
    @get:Rule val rule = createComposeRule()

    private fun dragHand(radiusRatio: Float, fromDegrees: Int, toDegrees: Int) {
        rule.onNodeWithTag("linked-clock").performScrollTo().performTouchInput {
            fun point(degrees: Int): Offset {
                val radians = Math.toRadians(degrees.toDouble())
                val radius = width / 2f * radiusRatio
                return center + Offset(sin(radians).toFloat() * radius, -cos(radians).toFloat() * radius)
            }
            down(point(fromDegrees))
            val step = if (toDegrees > fromDegrees) 3 else -3
            var angle = fromDegrees
            while (angle != toDegrees) {
                angle = if (step > 0) minOf(angle + step, toDegrees) else maxOf(angle + step, toDegrees)
                moveTo(point(angle), 16)
            }
            up()
        }
    }

    @Test fun typingThenDraggingMinuteHandUpdatesBothDirectionsAcrossMidnight() {
        var saved: LocalTime? = null
        rule.setContent { ClockTimeDialog(LocalTime.NOON, {}, { saved = it }) }
        rule.onNodeWithTag("clock-time-input").performTextReplacement("23:59")
        rule.onNodeWithTag("linked-clock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "23:59"))
        dragHand(.74f, 354, 366)
        rule.onNodeWithTag("clock-time-input").assertTextContains("00:01")
        dragHand(.74f, 6, -6)
        rule.onNodeWithTag("clock-time-input").assertTextContains("23:59")
        rule.onNodeWithText("确定").performClick()
        assertEquals(LocalTime.of(23, 59), saved)
    }

    @Test fun hourHandMovesMinutesProportionallyAndMinuteRevolutionAddsHour() {
        rule.setContent { ClockTimeDialog(LocalTime.of(3, 0), {}, {}) }
        dragHand(.48f, 90, 105)
        rule.onNodeWithTag("clock-time-input").assertTextContains("03:30")
        dragHand(.74f, 180, 540)
        rule.onNodeWithTag("clock-time-input").assertTextContains("04:30")
    }

    @Test fun invalidTimeCannotBeConfirmedAndCancelDoesNotSubmit() {
        var saved: LocalTime? = null
        var cancelled = false
        rule.setContent { ClockTimeDialog(LocalTime.of(18, 5), { cancelled = true }, { saved = it }) }
        rule.onNodeWithTag("clock-time-input").performTextReplacement("24:60")
        rule.onNodeWithText("确定").assertIsNotEnabled()
        rule.onNodeWithTag("linked-clock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "18:05"))
        rule.onNodeWithText("取消").performClick()
        assertEquals(true, cancelled)
        assertEquals(null, saved)
    }

    @Test fun draggingExposedMinuteShaftWhenHandsOverlapSelectsMinuteHand() {
        rule.setContent { ClockTimeDialog(LocalTime.MIDNIGHT, {}, {}) }
        dragHand(.55f, 0, 30)
        rule.onNodeWithTag("clock-time-input").assertTextContains("00:05")
    }
}
