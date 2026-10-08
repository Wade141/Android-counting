package com.example.monthlyexpense.ui

import com.example.monthlyexpense.ui.background.backgroundCrop
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundCropTest {
    @Test fun landscapePhotoFillsPortraitScreenAndClampsEdges() {
        val center = backgroundCrop(2000, 1000, 400, 800, .5f, .5f)
        assertEquals(1600, center.width)
        assertEquals(800, center.height)
        assertEquals(-600, center.left)
        assertEquals(0, center.top)
        assertEquals(0, backgroundCrop(2000, 1000, 400, 800, -1f, .5f).left)
        assertEquals(-1200, backgroundCrop(2000, 1000, 400, 800, 2f, .5f).left)
    }
    @Test fun tallPhotoCropsVerticallyWithoutStretching() {
        val crop = backgroundCrop(1000, 3000, 400, 800, .5f, 1f)
        assertEquals(400, crop.width)
        assertEquals(1200, crop.height)
        assertEquals(0, crop.left)
        assertEquals(-400, crop.top)
    }
}
