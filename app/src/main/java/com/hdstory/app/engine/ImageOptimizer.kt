package com.hdstory.app.engine

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hdstory.app.model.ImageConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageOptimizer {

    suspend fun optimizeImage(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        config: ImageConfig
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1. Decode bounds and EXIF orientation
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(inputUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            // Sample size to avoid OOM
            var inSampleSize = 1
            while (originalWidth / (inSampleSize * 2) >= config.width && originalHeight / (inSampleSize * 2) >= config.height) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var loadedBitmap = context.contentResolver.openInputStream(inputUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext Result.failure(Exception("Gagal membuka file gambar"))

            // Handle EXIF rotation
            context.contentResolver.openInputStream(inputUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                loadedBitmap = rotateBitmap(loadedBitmap, orientation)
            }

            // 2. Scale & Center-Crop to exact 1080x1920 (9:16)
            val targetWidth = config.width
            val targetHeight = config.height
            val scale = max(targetWidth.toFloat() / loadedBitmap.width, targetHeight.toFloat() / loadedBitmap.height)

            val scaledWidth = (loadedBitmap.width * scale).roundToInt()
            val scaledHeight = (loadedBitmap.height * scale).roundToInt()

            val scaledBitmap = Bitmap.createScaledBitmap(loadedBitmap, scaledWidth, scaledHeight, true)
            if (scaledBitmap != loadedBitmap) {
                loadedBitmap.recycle()
            }

            val cropX = ((scaledWidth - targetWidth) / 2).coerceAtLeast(0)
            val cropY = ((scaledHeight - targetHeight) / 2).coerceAtLeast(0)

            var finalBitmap = Bitmap.createBitmap(scaledBitmap, cropX, cropY, targetWidth, targetHeight)
            if (finalBitmap != scaledBitmap) {
                scaledBitmap.recycle()
            }

            // 3. Apply Smart Unsharp Mask / Sharpening if enabled
            if (config.applySharpen) {
                val sharpened = applySubtleSharpen(finalBitmap)
                if (sharpened != finalBitmap) {
                    finalBitmap.recycle()
                    finalBitmap = sharpened
                }
            }

            // 4. Save to JPEG with clean sRGB quantization
            FileOutputStream(outputFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, config.quality, out)
            }
            finalBitmap.recycle()

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    /**
     * Subtle unsharp convolution kernel to boost edge contrast.
     * Prevents social media bilinear downsampling from washing away edge clarity.
     */
    private fun applySubtleSharpen(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 3x3 Laplacian sharpening filter with weight 0.25 (mild, zero-halos)
        // [  0, -0.25,  0  ]
        // [ -0.25, 2.0, -0.25 ]
        // [  0, -0.25,  0  ]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val canvas = Canvas(output)
        canvas.drawBitmap(src, 0f, 0f, paint)

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(width * height)

        val weight = 0.25f

        for (y in 1 until height - 1) {
            val offset = y * width
            for (x in 1 until width - 1) {
                val idx = offset + x

                val cCenter = pixels[idx]
                val cTop = pixels[idx - width]
                val cBottom = pixels[idx + width]
                val cLeft = pixels[idx - 1]
                val cRight = pixels[idx + 1]

                val a = Color.alpha(cCenter)

                // R
                val rC = Color.red(cCenter)
                val rDiff = (4 * rC - Color.red(cTop) - Color.red(cBottom) - Color.red(cLeft) - Color.red(cRight)) * weight
                val rFinal = (rC + rDiff).toInt().coerceIn(0, 255)

                // G
                val gC = Color.green(cCenter)
                val gDiff = (4 * gC - Color.green(cTop) - Color.green(cBottom) - Color.green(cLeft) - Color.green(cRight)) * weight
                val gFinal = (gC + gDiff).toInt().coerceIn(0, 255)

                // B
                val bC = Color.blue(cCenter)
                val bDiff = (4 * bC - Color.blue(cTop) - Color.blue(cBottom) - Color.blue(cLeft) - Color.blue(cRight)) * weight
                val bFinal = (bC + bDiff).toInt().coerceIn(0, 255)

                outPixels[idx] = Color.argb(a, rFinal, gFinal, bFinal)
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }
}
