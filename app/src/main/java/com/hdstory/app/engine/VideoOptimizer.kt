package com.hdstory.app.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import com.hdstory.app.model.VideoConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

@UnstableApi
object VideoOptimizer {

    suspend fun optimizeVideo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        config: VideoConfig,
        onProgress: (Int) -> Unit
    ): Result<File> = suspendCancellableCoroutine { continuation ->

        if (outputFile.exists()) {
            outputFile.delete()
        }

        // 1. Setup Video Effects (Force 1080x1920 9:16 Scale-to-fit with crop)
        val presentationEffect = Presentation.createForWidthAndHeight(
            config.width,
            config.height,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(listOf(), listOf(presentationEffect)))
            .build()

        // 2. Setup Encoder Settings for Social Media High Profile
        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(config.targetBitrate)
            .setEncodingProfileLevel(
                android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel41
            )
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .build()

        // 3. Build Transformer
        val transformer = Transformer.Builder(context.applicationContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) {
                        continuation.resume(Result.success(outputFile))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(exportException))
                    }
                }
            })
            .build()

        // 4. Start Transform
        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(Result.failure(e))
            }
        }

        continuation.invokeOnCancellation {
            try {
                transformer.cancel()
            } catch (_: Exception) {}
        }
    }
}
