package com.example.monthlyexpense.screenshot

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotManifestTest {
    @Test fun imageShareResolvesToProductionEntryWithoutNetworkOrGalleryPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("image/png").setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(1, matches.size)
        assertEquals("com.example.monthlyexpense.screenshot.ScreenshotEntryActivity", matches.single().activityInfo.name)
        assertTrue(matches.single().activityInfo.exported)
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertFalse(permissions.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }
}
