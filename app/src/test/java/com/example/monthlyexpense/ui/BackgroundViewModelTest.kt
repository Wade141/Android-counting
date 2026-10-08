package com.example.monthlyexpense.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.MainDispatcherRule
import com.example.monthlyexpense.ui.background.BackgroundStore
import com.example.monthlyexpense.ui.background.BackgroundViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class BackgroundViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun selectionArrivingDuringInitializationIsImportedAndOnlyAppliedOnConfirm() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        BackgroundStore(application).reset()
        val source = File(application.cacheDir, "background-import-test.png")
        source.outputStream().use { Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val model = BackgroundViewModel(application)
        try {
            assertTrue(model.state.value.busy)
            model.select(Uri.fromFile(source))
            val imported = model.state.first { it.draft != null || it.error != null }
            assertNull(imported.error)
            assertEquals(40, imported.draft!!.bitmap.width)
            assertNull(BackgroundStore(application).load())
            model.move(.2f, .8f)
            model.apply()
            val saved = model.state.first { !it.busy && !it.editorOpen }
            assertEquals(.2f, saved.saved!!.x)
            assertEquals(.8f, BackgroundStore(application).load()!!.y)
            model.open()
            model.reset()
            model.state.first { !it.busy && !it.editorOpen }
            assertNull(BackgroundStore(application).load())
        } finally {
            model.viewModelScope.cancel()
            source.delete()
            BackgroundStore(application).reset()
        }
    }
}
