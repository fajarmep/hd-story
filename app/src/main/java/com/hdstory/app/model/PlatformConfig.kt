package com.hdstory.app.model

enum class MediaTypeTab {
    PHOTO,
    VIDEO
}

enum class PhotoPlatform(val label: String, val width: Int, val height: Int, val quality: Int) {
    IG_WA_STORY("WhatsApp Status & Instagram Story (9:16 - 1080x1920)", 1080, 1920, 92),
    IG_FEED_PORTRAIT("Instagram Feed Portrait (4:5 - 1080x1350)", 1080, 1350, 92),
    IG_FEED_SQUARE("Instagram Feed Square (1:1 - 1080x1080)", 1080, 1080, 92),
    ORIGINAL_MAX_2048("Format Asli HD (Pertahankan Rasio - Max 2048px)", 0, 0, 94)
}

enum class VideoPlatform(val label: String) {
    INSTAGRAM_REELS_STORY("Instagram (Story & Reels 1080p, 30fps)"),
    WHATSAPP_STATUS("WhatsApp Status HD (<15MB Bypass Transcode)"),
    TIKTOK_HD("TikTok HD (1080x1920, 8.5M High Motion)")
}

data class VideoConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
    val targetBitrate: Int,
    val maxFileSizeBytes: Long = Long.MAX_VALUE,
    val audioBitrate: Int = 128_000,
    val audioSampleRate: Int = 44_100
)

data class ImageConfig(
    val platform: PhotoPlatform = PhotoPlatform.IG_WA_STORY,
    val width: Int = 1080,
    val height: Int = 1920,
    val quality: Int = 92,
    val applySharpen: Boolean = true
)

object PlatformPresets {
    fun getVideoConfig(platform: VideoPlatform, durationSeconds: Float = 15f): VideoConfig {
        return when (platform) {
            VideoPlatform.INSTAGRAM_REELS_STORY -> VideoConfig(
                width = 1080,
                height = 1920,
                frameRate = 30,
                targetBitrate = 4_500_000 // 4.5 Mbps IG sweet-spot
            )
            VideoPlatform.WHATSAPP_STATUS -> {
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
            VideoPlatform.TIKTOK_HD -> VideoConfig(
                width = 1080,
                height = 1920,
                frameRate = 30,
                targetBitrate = 8_500_000
            )
        }
    }

    fun getImageConfig(
        platform: PhotoPlatform,
        enableSharpen: Boolean = true
    ): ImageConfig {
        return ImageConfig(
            platform = platform,
            width = platform.width,
            height = platform.height,
            quality = platform.quality,
            applySharpen = enableSharpen
        )
    }
}
