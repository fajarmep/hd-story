package com.hdstory.app.model

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
    val audioBitrate: Int = 128_000, // 128 kbps
    val audioSampleRate: Int = 44_100
)

data class ImageConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val quality: Int = 92,
    val applySharpen: Boolean = true
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
                // WA Status hard cut-off is ~16MB. We target 14.2 MB max.
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
                targetBitrate = 8_500_000 // 8.5 Mbps TikTok high clarity
            )
        }
    }

    fun getImageConfig(platform: PlatformType, enableSharpen: Boolean = true): ImageConfig {
        return when (platform) {
            PlatformType.INSTAGRAM -> ImageConfig(width = 1080, height = 1920, quality = 92, applySharpen = enableSharpen)
            PlatformType.WHATSAPP -> ImageConfig(width = 1080, height = 1920, quality = 90, applySharpen = enableSharpen)
            PlatformType.TIKTOK -> ImageConfig(width = 1080, height = 1920, quality = 95, applySharpen = enableSharpen)
        }
    }
}
