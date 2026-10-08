package com.example.monthlyexpense.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class BackgroundImage(val bitmap: Bitmap, val x: Float = .5f, val y: Float = .5f)
internal data class BackgroundCrop(val width: Int, val height: Int, val left: Int, val top: Int)

internal fun backgroundCrop(iw: Int, ih: Int, vw: Int, vh: Int, x: Float, y: Float): BackgroundCrop {
    require(iw > 0 && ih > 0 && vw > 0 && vh > 0)
    val scale = max(vw.toDouble() / iw, vh.toDouble() / ih)
    val width = ceil(iw * scale).toInt()
    val height = ceil(ih * scale).toInt()
    return BackgroundCrop(width, height,
        -((width - vw) * x.coerceIn(0f, 1f)).roundToInt(),
        -((height - vh) * y.coerceIn(0f, 1f)).roundToInt())
}

internal class BackgroundStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "personal-background.bin"))

    fun load(): BackgroundImage? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return DataInputStream(file.openRead()).use { input ->
            check(input.readInt() == 1)
            val x = input.readFloat()
            val y = input.readFloat()
            check(x.isFinite() && y.isFinite())
            val bitmap = BitmapFactory.decodeStream(input) ?: error("Invalid background")
            BackgroundImage(bitmap, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
    }

    fun save(image: BackgroundImage) {
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(1)
            output.writeFloat(image.x)
            output.writeFloat(image.y)
            check(image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    fun reset() {
        file.delete()
        check(!file.baseFile.exists())
    }

    fun importImage(uri: Uri): BackgroundImage {
        val resolver = context.contentResolver
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val scale = minOf(1f, 2048f / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            check(options.outWidth > 0 && options.outHeight > 0)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (max(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("Invalid image")
            val orientation = runCatching {
                resolver.openInputStream(uri).use {
                    ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        return BackgroundImage(bitmap)
    }
}
