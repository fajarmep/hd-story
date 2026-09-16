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
import kotlin.math.min
import kotlin.math.roundToInt

object ImageOptimizer {

    suspend fun optimizeImage(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        config: ImageConfig
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1. Decode bounds only
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(inputUri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }

            val originalWidth = boundsOptions.outWidth
            val originalHeight = boundsOptions.outHeight

            if (originalWidth <= 0 || originalHeight <= 0) {
                return@withContext Result.failure(Exception("Gagal membaca dimensi gambar"))
            }

            // 2. Calculate inSampleSize — CONSERVATIVE: only downsample if source is
            // more than 2x the target to avoid upscale-after-subsample blur.
            // For 1080x1920 target, only subsample if source > 2160x3840.
            val targetMax = if (config.platform == PhotoPlatform.ORIGINAL_MAX_2048) {
                2048
            } else {
                max(config.width, config.height)
            }

            var inSampleSize = 1
            val threshold = targetMax * 2  // Only subsample when source > 2x target
            while (originalWidth / (inSampleSize * 2) >= threshold &&
                   originalHeight / (inSampleSize * 2) >= threshold) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var loadedBitmap = context.contentResolver.openInputStream(inputUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext Result.failure(Exception("Gagal membuka file gambar"))

            // 3. Handle EXIF orientation
            context.contentResolver.openInputStream(inputUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                loadedBitmap = rotateBitmap(loadedBitmap, orientation)
            }

            // 4. High-quality scale and crop
            val processedBitmap: Bitmap

            if (config.platform == PhotoPlatform.ORIGINAL_MAX_2048) {
                val maxDim = max(loadedBitmap.width, loadedBitmap.height)
                if (maxDim > 2048) {
                    processedBitmap = highQualityScale(
                        loadedBitmap,
                        (loadedBitmap.width * 2048f / maxDim).roundToInt(),
                        (loadedBitmap.height * 2048f / maxDim).roundToInt()
                    )
                    if (processedBitmap != loadedBitmap) loadedBitmap.recycle()
                } else {
                    processedBitmap = loadedBitmap
                }
            } else {
                val targetW = config.width
                val targetH = config.height

                // Scale so the SMALLER dimension matches target (fill, not fit)
                val scale = max(
                    targetW.toFloat() / loadedBitmap.width,
                    targetH.toFloat() / loadedBitmap.height
                )

                val scaledW = (loadedBitmap.width * scale).roundToInt()
                val scaledH = (loadedBitmap.height * scale).roundToInt()

                val scaled = highQualityScale(loadedBitmap, scaledW, scaledH)
                if (scaled != loadedBitmap) loadedBitmap.recycle()

                // Center-crop to exact target
                val cropX = ((scaledW - targetW) / 2).coerceAtLeast(0)
                val cropY = ((scaledH - targetH) / 2).coerceAtLeast(0)

                processedBitmap = Bitmap.createBitmap(scaled, cropX, cropY, targetW, targetH)
                if (processedBitmap != scaled) scaled.recycle()
            }

            // 5. Sharpen AFTER crop — bitmap now <=target resolution, safe
            var finalBitmap = processedBitmap
            if (config.applySharpen) {
                val sharpened = applySubtleSharpen(finalBitmap)
                if (sharpened != finalBitmap) {
                    finalBitmap.recycle()
                    finalBitmap = sharpened
                }
            }

            // 6. Save as high-quality baseline JPEG sRGB
            FileOutputStream(outputFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, config.quality, out)
            }
            finalBitmap.recycle()

            Result.success(outputFile)
        } catch (e: OutOfMemoryError) {
            System.gc()
            Result.failure(Exception("Gambar terlalu besar. Coba resolusi lebih kecil."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * High-quality downscale using Canvas + FILTER_BITMAP_FLAG + Paint.ANTI_ALIAS.
     * This uses bicubic-like filtering instead of the basic bilinear in createScaledBitmap.
     * For large downscales (>2x), does multi-step halving to preserve detail.
     */
    private fun highQualityScale(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW == src.width && targetH == src.height) return src

        // Multi-step halving for large downscales — each step max 2x reduction
        var current = src
        var curW = src.width
        var curH = src.height

        while (curW / 2 >= targetW && curH / 2 >= targetH) {
            val halfW = curW / 2
            val halfH = curH / 2
            val half = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(half)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }
            canvas.drawBitmap(current, null, RectF(0f, 0f, halfW.toFloat(), halfH.toFloat()), paint)
            if (current != src) current.recycle()
            current = half
            curW = halfW
            curH = halfH
        }

        // Final step to exact target
        if (curW != targetW || curH != targetH) {
            val final = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(final)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }
            canvas.drawBitmap(current, null, RectF(0f, 0f, targetW.toFloat(), targetH.toFloat()), paint)
            if (current != src) current.recycle()
            return final
        }

        return current
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
     * Laplacian 3x3 unsharp convolution, weight 0.28.
     * Applied AFTER crop to target — max ~1080x1920 pixel buffer, safe for all devices.
     */
    private fun applySubtleSharpen(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        // Guard: skip if bitmap exceeds 16MP
        if (width.toLong() * height > 16_000_000L) return src

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(width * height)

        val weight = 0.28f

        // Copy border pixels unchanged
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
