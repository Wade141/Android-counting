package com.example.monthlyexpense.screenshot

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.monthlyexpense.ui.menu.FeatureMenuItem

@Composable
internal fun screenshotMenuItem(enabled: Boolean): FeatureMenuItem {
    val context = LocalContext.current
    return FeatureMenuItem("截图记账（相册）", enabled = enabled) {
        context.startActivity(Intent(context, ScreenshotEntryActivity::class.java))
    }
}
