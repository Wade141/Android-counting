package com.example.monthlyexpense.screenshot

import android.content.Intent
import android.net.Uri

internal sealed interface ScreenshotShareInput {
    data object Gallery : ScreenshotShareInput
    data class Image(val uri: Uri) : ScreenshotShareInput
    data class Invalid(val message: String) : ScreenshotShareInput
}

internal fun parseScreenshotShare(intent: Intent): ScreenshotShareInput {
    fun invalid() = ScreenshotShareInput.Invalid("请分享一张可读取的图片，或从相册选择截图")
    return try {
        if (intent.action == null) return ScreenshotShareInput.Gallery
        if (intent.action != Intent.ACTION_SEND) return invalid()
        val mime = intent.type ?: return invalid()
        if (!mime.matches(Regex("image/[A-Za-z0-9!#$&^_.+*\\-]+"))) return invalid()

        val uris = linkedSetOf<Uri>()
        if (intent.hasExtra(Intent.EXTRA_STREAM)) {
            // Inspect the raw value: typed getters can silently turn a hostile type into null.
            @Suppress("DEPRECATION")
            val stream = intent.extras?.get(Intent.EXTRA_STREAM)
            if (stream !is Uri) return invalid()
            uris += stream
        }
        intent.clipData?.let { clip ->
            if (clip.itemCount == 0) return invalid()
            for (index in 0 until clip.itemCount) {
                uris += clip.getItemAt(index).uri ?: return invalid()
                if (uris.size > 1) return invalid()
            }
        }
        val uri = uris.singleOrNull() ?: return invalid()
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return invalid()
        ScreenshotShareInput.Image(uri)
    } catch (_: RuntimeException) {
        // BadParcelableException, malformed Bundles and hostile Parcelable types are invalid input.
        invalid()
    }
}
