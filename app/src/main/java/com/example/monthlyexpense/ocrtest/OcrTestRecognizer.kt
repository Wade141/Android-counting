package com.example.monthlyexpense.ocrtest

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OcrTestResult(val text: String, val elapsedMs: Long, val width: Int, val height: Int,
    val lines: List<LocatedText> = emptyList(), val analysis: ScreenshotAnalysis = ScreenshotAnalysis(),
    val imageHash: String? = null)

internal fun copyScreenshotAndHash(input: InputStream, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= 20L * 1024 * 1024) { "图片超过 20 MiB，请裁剪后重试" }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class OcrTestRecognizer(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val context: android.content.Context
) {
    suspend fun recognize(uri: Uri): OcrTestResult = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val temporary = File.createTempFile("ocr-test-", ".image", cacheDir)
        try {
            val stream = resolver.openInputStream(uri) ?: error("无法打开所选图片，请重新选择")
            val imageHash = stream.use { input ->
                temporary.outputStream().use { output ->
                    copyScreenshotAndHash(input, output)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法解码，请选择 JPG 或 PNG 截图" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= 12_000_000L) {
                "图片超过 1200 万像素，请裁剪后重试"
            }
            val image = InputImage.fromFilePath(context, Uri.fromFile(temporary))
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                // Blocking only on IO; wait until native processing finishes before releasing resources.
                val text = Tasks.await(recognizer.process(image))
                // OCR coordinates refer to the upright image, not the source EXIF orientation.
                val rotated = image.rotationDegrees % 180 != 0
                val width = if (rotated) image.height else image.width
                val height = if (rotated) image.width else image.height
                fun box(rect: android.graphics.Rect?) = rect?.let {
                    OcrBox.fromPixels(it.left, it.top, it.right, it.bottom, width, height)
                }
                val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                    LocatedText(line.text, box(line.boundingBox), line.elements.map {
                        LocatedText(it.text, box(it.boundingBox))
                    })
                }
                OcrTestResult(text.text, SystemClock.elapsedRealtime() - started, width, height,
                    lines, ScreenshotAnalyzer.analyze(lines), imageHash)
            } finally {
                recognizer.close()
            }
        } finally {
            temporary.delete()
        }
    }
}
