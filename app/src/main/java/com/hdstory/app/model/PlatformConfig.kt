package com.hdstory.app.model

enum class MediaTypeTab {
    PHOTO,
    VIDEO
}

enum class PhotoTargetFormat(val label: String, val width: Int, val height: Int) {
    STORY_VERTICAL("9:16 Story / Status Fullscreen (1080x1920)", 1080, 1920),
    FEED_PORTRAIT("4:5 Feed Portrait IG (1080x1350)", 1080, 1350),
    FEED_SQUARE("1:1 Feed Square (1080x1080)", 1080, 1080),
    ORIGINAL_RES_HD("Pertahankan Rasio Asli (Max 2048px HD)", 0, 0)
}

enum class PlatformType {
    INSTAGRAM,
    WHATSAPP,
    TIKTOK
}

data class VideoConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
    val targetBitrate: Int, // in bps
    val maxFileSizeBytes: Long = Long.MAX_VALUE,
    val audioBitrate: Int = 128_000,
    val audioSampleRate: Int = 44_100
)

data class ImageConfig(
    val format: PhotoTargetFormat = PhotoTargetFormat.STORY_VERTICAL,
    val width: Int = 1080,
    val height: Int = 1920,
    val quality: Int = 92,
    val applySharpen: Boolean = true,
    val paddingBlur: Boolean = false
)

object PlatformPresets {
    fun getVideoConfig(platform: PlatformType, durationSeconds: Float = 15f): VideoConfig {
        return when (platform) {
            PlatformType.INSTAGRAM -> VideoConfig(
                width = 1080,
                height = 1920,
                frameRate = 30,
                targetBitrate = 4_500_000 // 4.5 Mbps IG sweet-spot
            )
            PlatformType.WHATSAPP -> {
                // WA Status hard limit ~16MB. We target 14.2 MB max.
                val maxAudioBytes = (128_000 / 8) * durationSeconds
                val targetTotalBytes = 14.2 * 1024 * 1024
                val availableVideoBytes = (targetTotalBytes - maxAudioBytes).coerceAtLeast(1_000_000.0)
                val calculatedBitrate = ((availableVideoBytes * 8) / durationSeconds).toInt()
                val finalBitrate = calculatedBitrate.coerceIn(1_800_000, 3_800_000)

                VideoConfig(
                    width = 1080,
                    height = 1920,
                    frameRate = 30,
                    targetBitrate = finalBitrate,
                    maxFileSizeBytes = 15_000_000L
                )
            }
            PlatformType.TIKTOK -> VideoConfig(
                width = 1080,
                height = 1920,
                frameRate = 30,
                targetBitrate = 8_500_000
            )
        }
    }

    fun getImageConfig(
        platform: PlatformType,
        format: PhotoTargetFormat,
        enableSharpen: Boolean = true
    ): ImageConfig {
        val quality = when (platform) {
            PlatformType.INSTAGRAM -> 92
            PlatformType.WHATSAPP -> 90
            PlatformType.TIKTOK -> 95
        }

        return ImageConfig(
            format = format,
            width = format.width,
            height = format.height,
            quality = quality,
            applySharpen = enableSharpen
        )
    }
}
