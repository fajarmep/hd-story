package com.hdstory.app.engine

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hdstory.app.model.ImageConfig
import com.hdstory.app.model.PhotoPlatform
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
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(inputUri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }

            val originalWidth = boundsOptions.outWidth
            val originalHeight = boundsOptions.outHeight

            val targetTargetMax = if (config.platform == PhotoPlatform.ORIGINAL_MAX_2048) 2048 else max(config.width, config.height)

            var inSampleSize = 1
            while (originalWidth / (inSampleSize * 2) >= targetTargetMax && originalHeight / (inSampleSize * 2) >= targetTargetMax) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var loadedBitmap = context.contentResolver.openInputStream(inputUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext Result.failure(Exception("Gagal membuka file gambar"))

            // Handle EXIF orientation rotation
            context.contentResolver.openInputStream(inputUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                loadedBitmap = rotateBitmap(loadedBitmap, orientation)
            }

            val processedBitmap: Bitmap

            if (config.platform == PhotoPlatform.ORIGINAL_MAX_2048) {
                // Keep original aspect ratio, scale longest edge to 2048px (WhatsApp HD sweet spot)
                val maxDim = max(loadedBitmap.width, loadedBitmap.height)
                if (maxDim > 2048) {
                    val scale = 2048f / maxDim
                    val nw = (loadedBitmap.width * scale).roundToInt()
                    val nh = (loadedBitmap.height * scale).roundToInt()
                    processedBitmap = Bitmap.createScaledBitmap(loadedBitmap, nw, nh, true)
                    if (processedBitmap != loadedBitmap) loadedBitmap.recycle()
                } else {
                    processedBitmap = loadedBitmap
                }
            } else {
                // Fixed target resolution (e.g. 1080x1920, 1080x1350, 1080x1080)
                val targetW = config.width
                val targetH = config.height
                val scale = max(targetW.toFloat() / loadedBitmap.width, targetH.toFloat() / loadedBitmap.height)

                val scaledW = (loadedBitmap.width * scale).roundToInt()
                val scaledH = (loadedBitmap.height * scale).roundToInt()

                val scaled = Bitmap.createScaledBitmap(loadedBitmap, scaledW, scaledH, true)
                if (scaled != loadedBitmap) loadedBitmap.recycle()

                val cropX = ((scaledW - targetW) / 2).coerceAtLeast(0)
                val cropY = ((scaledH - targetH) / 2).coerceAtLeast(0)

                processedBitmap = Bitmap.createBitmap(scaled, cropX, cropY, targetW, targetH)
                if (processedBitmap != scaled) scaled.recycle()
            }

            // 3. Smart Edge Sharpening (Crucial for Instagram/WhatsApp photo preservation)
            var finalBitmap = processedBitmap
            if (config.applySharpen) {
                val sharpened = applySubtleSharpen(finalBitmap)
                if (sharpened != finalBitmap) {
                    finalBitmap.recycle()
                    finalBitmap = sharpened
                }
            }

            // 4. Save to baseline JPEG with clean 4:2:0 / 4:4:4 quantization matrix
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
     * Laplacian 3x3 unsharp convolution kernel with 0.28 weight.
     * Prevents social media downsampling and JPEG re-quantization from washing away edges.
     */
    private fun applySubtleSharpen(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(width * height)

        val weight = 0.28f

        System.arraycopy(pixels, 0, outPixels, 0, width)
        System.arraycopy(pixels, (height - 1) * width, outPixels, (height - 1) * width, width)

        for (y in 1 until height - 1) {
            val offset = y * width
            outPixels[offset] = pixels[offset]
            outPixels[offset + width - 1] = pixels[offset + width - 1]

            for (x in 1 until width - 1) {
                val idx = offset + x
                val cCenter = pixels[idx]
                val cTop = pixels[idx - width]
                val cBottom = pixels[idx + width]
                val cLeft = pixels[idx - 1]
                val cRight = pixels[idx + 1]

                val a = Color.alpha(cCenter)

                val rC = Color.red(cCenter)
                val rDiff = (4 * rC - Color.red(cTop) - Color.red(cBottom) - Color.red(cLeft) - Color.red(cRight)) * weight
                val rFinal = (rC + rDiff).toInt().coerceIn(0, 255)

                val gC = Color.green(cCenter)
                val gDiff = (4 * gC - Color.green(cTop) - Color.green(cBottom) - Color.green(cLeft) - Color.green(cRight)) * weight
                val gFinal = (gC + gDiff).toInt().coerceIn(0, 255)

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
