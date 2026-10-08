package com.example.monthlyexpense.ocrtest

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.monthlyexpense.ui.menu.FeatureMenuItem

@Composable
internal fun debugOcrMenuItem(): FeatureMenuItem? {
    val context = LocalContext.current
    return FeatureMenuItem("OCR 识别测试") {
        context.startActivity(Intent(context, OcrTestActivity::class.java))
    }
}
