package com.hdstory.app.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
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

        val handler = Handler(Looper.getMainLooper())
        val progressHolder = ProgressHolder()

        // 1. Scale-to-fit with crop to target 9:16 resolution
        val presentationEffect = Presentation.createForWidthAndHeight(
            config.width,
            config.height,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(listOf(), listOf(presentationEffect)))
            .build()

        // 2. H.264 High Profile L4.1 with high bitrate
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
                    handler.removeCallbacksAndMessages(null)
                    if (continuation.isActive) {
                        onProgress(100)
                        continuation.resume(Result.success(outputFile))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacksAndMessages(null)
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(exportException))
                    }
                }
            })
            .build()

        // 4. Poll progress every 500ms
        val progressPoller = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress.coerceIn(0, 99))
                }
                handler.postDelayed(this, 500)
            }
        }

        // 5. Start encoding
        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
            handler.postDelayed(progressPoller, 500)
        } catch (e: Exception) {
            handler.removeCallbacksAndMessages(null)
            if (continuation.isActive) {
                continuation.resume(Result.failure(e))
            }
        }

        continuation.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            try {
                transformer.cancel()
            } catch (_: Exception) {}
        }
    }
}
