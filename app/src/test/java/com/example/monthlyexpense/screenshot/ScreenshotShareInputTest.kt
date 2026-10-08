package com.example.monthlyexpense.screenshot

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotShareInputTest {
    private val uri = Uri.parse("content://screenshots/one")
    private fun send() = Intent(Intent.ACTION_SEND).setType("image/png")
    private fun clip(value: Uri = uri) = ClipData.newRawUri("screenshot", value)
    private fun invalid(intent: Intent) {
        val result = parseScreenshotShare(intent)
        assertTrue(result is ScreenshotShareInput.Invalid)
        assertTrue((result as ScreenshotShareInput.Invalid).message.isNotBlank())
    }

    @Test fun nullActionOpensGallery() {
        assertEquals(ScreenshotShareInput.Gallery, parseScreenshotShare(Intent()))
    }

    @Test fun acceptsStreamClipOrMatchingBoth() {
        listOf(
            send().putExtra(Intent.EXTRA_STREAM, uri),
            send().apply { clipData = clip() },
            send().putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = clip() },
            send().setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
        ).forEach { assertEquals(ScreenshotShareInput.Image(uri), parseScreenshotShare(it)) }
    }

    @Test fun repeatedSameUriIsOneImage() {
        val intent = send().apply { clipData = clip().apply { addItem(ClipData.Item(uri)) } }
        assertEquals(ScreenshotShareInput.Image(uri), parseScreenshotShare(intent))
    }

    @Test fun rejectsMultipleImagesAndConflictingSources() {
        val other = Uri.parse("content://screenshots/two")
        invalid(send().putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = clip(other) })
        invalid(send().apply { clipData = clip().apply { addItem(ClipData.Item(other)) } })
        invalid(send().setAction(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, uri))
        invalid(send().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri)))
    }

    @Test fun rejectsMissingWrongOrMalformedMimeAndUnsupportedActions() {
        listOf(null, "text/plain", "application/octet-stream", "image/", "image/png/other")
            .forEach { invalid(send().setType(it).putExtra(Intent.EXTRA_STREAM, uri)) }
        listOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN, "untrusted.action")
            .forEach { invalid(send().setAction(it).putExtra(Intent.EXTRA_STREAM, uri)) }
        invalid(send())
    }

    @Test fun rejectsNonContentAndAuthoritylessUris() {
        listOf("file:///tmp/image.png", "https://host/image.png", "http://host/image.png", "content:/image", "image.png")
            .forEach { value ->
                invalid(send().putExtra(Intent.EXTRA_STREAM, Uri.parse(value)))
                invalid(send().apply { clipData = clip(Uri.parse(value)) })
            }
    }

    @Test fun maliciousExtraTypesAndNonUriClipItemsDoNotCrashOrFallBack() {
        invalid(send().putExtra(Intent.EXTRA_STREAM, "content://screenshots/one"))
        invalid(send().putExtra(Intent.EXTRA_STREAM, 42).apply { clipData = clip() })
        invalid(send().putExtra(Intent.EXTRA_STREAM, Intent()))
        invalid(send().putExtra(Intent.EXTRA_STREAM, null as Uri?).apply { clipData = clip() })
        invalid(send().apply { clipData = ClipData.newPlainText("image", "content://screenshots/one") })
        invalid(send().apply { clipData = clip().apply { addItem(ClipData.Item("extra text")) } })
    }
}
