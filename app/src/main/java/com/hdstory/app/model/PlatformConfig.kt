package com.hdstory.app.model

enum class MediaTypeTab {
    PHOTO,
    VIDEO
}

enum class PhotoPlatform(val label: String, val width: Int, val height: Int, val quality: Int) {
    IG_WA_STORY("WhatsApp Status & Instagram Story (9:16 - 1080x1920)", 1080, 1920, 97),
    IG_FEED_PORTRAIT("Instagram Feed Portrait (4:5 - 1080x1350)", 1080, 1350, 97),
    IG_FEED_SQUARE("Instagram Feed Square (1:1 - 1080x1080)", 1080, 1080, 97),
    ORIGINAL_MAX_2048("Format Asli HD (Pertahankan Rasio - Max 2048px)", 0, 0, 98)
}

enum class VideoPlatform(val label: String) {
    INSTAGRAM_REELS_STORY("Instagram (Story & Reels 1080p, 30fps)"),
    WHATSAPP_STATUS("WhatsApp Status HD (<16MB Bypass Transcode)"),
    TIKTOK_HD("TikTok HD (1080x1920, High Bitrate)")
}

data class VideoConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
    val targetBitrate: Int,
    val maxFileSizeBytes: Long = Long.MAX_VALUE,
    val audioBitrate: Int = 192_000,
    val audioSampleRate: Int = 44_100,
    val iFrameIntervalSeconds: Int = 1  // GOP = 1 second = keyframe every 30 frames
)

data class ImageConfig(
    val platform: PhotoPlatform = PhotoPlatform.IG_WA_STORY,
    val width: Int = 1080,
    val height: Int = 1920,
    val quality: Int = 97,
    val applySharpen: Boolean = true
)

object PlatformPresets {
    fun getVideoConfig(platform: VideoPlatform, durationSeconds: Float = 15f): VideoConfig {
        return when (platform) {
            // Instagram internal re-encodes at 5-8 Mbps.
            // We output at 8 Mbps so after their re-encode, detail survives zoom.
            VideoPlatform.INSTAGRAM_REELS_STORY -> VideoConfig(
                width = 1080,
                height = 1920,
                frameRate = 30,
                targetBitrate = 8_000_000
            )

            // WhatsApp transcodes videos >16MB. We target <16MB total.
            // Higher bitrate cap (6 Mbps) vs old 3.8 Mbps for sharper output.
            VideoPlatform.WHATSAPP_STATUS -> {
                val maxAudioBytes = (192_000 / 8) * durationSeconds
                val targetTotalBytes = 15.5 * 1024 * 1024  // ~15.5MB target, under 16MB
                val availableVideoBytes = (targetTotalBytes - maxAudioBytes).coerceAtLeast(1_000_000.0)
                val calculatedBitrate = ((availableVideoBytes * 8) / durationSeconds).toInt()
                // Higher cap: 6 Mbps. Short videos (<10s) get near-max bitrate.
                val finalBitrate = calculatedBitrate.coerceIn(2_500_000, 6_000_000)

                VideoConfig(
                    width = 1080,
                    height = 1920,
                    frameRate = 30,
                    targetBitrate = finalBitrate,
                    maxFileSizeBytes = 16_000_000L
                )
            }

            // TikTok supports up to 15 Mbps. 12 Mbps gives excellent zoom quality.
            VideoPlatform.TIKTOK_HD -> VideoConfig(
                width = 1080,
                height = 1920,
                frameRate = 30,
                targetBitrate = 12_000_000
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
