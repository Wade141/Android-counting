package com.example.monthlyexpense.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ui.background.BackgroundImage
import com.example.monthlyexpense.ui.background.BackgroundStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundStoreTest {
    @Test fun imageAndCropSurviveReloadAndResetRemovesSavedBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = BackgroundStore(context)
        store.reset()
        assertNull(store.load())
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        store.save(BackgroundImage(bitmap, .25f, .75f))
        val reloaded = BackgroundStore(context).load()!!
        assertEquals(200, reloaded.bitmap.width)
        assertEquals(100, reloaded.bitmap.height)
        assertEquals(.25f, reloaded.x)
        assertEquals(.75f, reloaded.y)
        store.reset()
        assertNull(BackgroundStore(context).load())
    }
}
