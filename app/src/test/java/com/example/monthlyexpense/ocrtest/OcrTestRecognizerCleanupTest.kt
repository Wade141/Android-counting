package com.example.monthlyexpense.ocrtest

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrTestRecognizerCleanupTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun readFailureClosesInputAndRemovesPartialImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://screenshots/broken")
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("broken provider")
            override fun close() { closed = true }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        assertThrows(IOException::class.java) {
            runBlocking { OcrTestRecognizer(context.contentResolver, folder.root, context).recognize(uri) }
        }
        assertTrue(closed)
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun unavailableUriRemovesTemporaryImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThrows(Exception::class.java) {
            runBlocking {
                OcrTestRecognizer(context.contentResolver, folder.root, context)
                    .recognize(Uri.parse("content://missing/image"))
            }
        }
        assertEquals(0, folder.root.listFiles()!!.size)
    }
}
