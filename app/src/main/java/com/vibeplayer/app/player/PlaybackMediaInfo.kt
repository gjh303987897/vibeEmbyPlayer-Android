package com.vibeplayer.app.player

import tv.danmaku.ijk.media.player.IjkMediaMeta
import tv.danmaku.ijk.media.player.misc.IMediaFormat
import tv.danmaku.ijk.media.player.misc.ITrackInfo
import tv.danmaku.ijk.media.player.misc.IjkMediaFormat

/** Source metadata for the selected streams, independent of speed, zoom and network traffic. */
data class PlaybackMediaInfo(val video: VideoStreamInfo? = null, val audio: AudioStreamInfo? = null)

data class VideoStreamInfo(
    val codec: String?,
    val profile: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val bitrate: Long?,
    val pixelFormat: String?
)

data class AudioStreamInfo(
    val codec: String?,
    val bitrate: Long?,
    val sampleRateHz: Int?,
    val channelCount: Int?
)

internal fun readPlaybackMediaInfo(
    tracks: List<ITrackInfo>, selectedVideo: Int, selectedAudio: Int
): PlaybackMediaInfo {
    fun selected(type: Int, selectedIndex: Int): IMediaFormat? {
        if (selectedIndex < 0) return null
        return tracks.withIndex().firstOrNull { (index, track) ->
            val nativeIndex = (track.format as? IjkMediaFormat)?.mMediaFormat?.mIndex ?: index
            track.trackType == type && nativeIndex == selectedIndex
        }?.value?.format
    }
    val video = selected(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO, selectedVideo)?.let { format ->
        val numerator = format.positiveInt(IjkMediaMeta.IJKM_KEY_FPS_NUM)
        val denominator = format.positiveInt(IjkMediaMeta.IJKM_KEY_FPS_DEN)
        VideoStreamInfo(
            codec = format.text(IjkMediaMeta.IJKM_KEY_CODEC_NAME),
            profile = format.text(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE),
            width = format.positiveInt(IjkMediaMeta.IJKM_KEY_WIDTH),
            height = format.positiveInt(IjkMediaMeta.IJKM_KEY_HEIGHT),
            frameRate = if (numerator != null && denominator != null) numerator.toDouble() / denominator else null,
            // IJK 0.8.8's parsed mBitrate uses getInt; read the raw string to preserve 64-bit rates.
            bitrate = format.positiveLong(IjkMediaMeta.IJKM_KEY_BITRATE),
            pixelFormat = format.text(IjkMediaMeta.IJKM_KEY_CODEC_PIXEL_FORMAT)
        )
    }
    val audio = selected(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, selectedAudio)?.let { format ->
        val layout = format.positiveLong(IjkMediaMeta.IJKM_KEY_CHANNEL_LAYOUT)
        AudioStreamInfo(
            codec = format.text(IjkMediaMeta.IJKM_KEY_CODEC_NAME),
            bitrate = format.positiveLong(IjkMediaMeta.IJKM_KEY_BITRATE),
            sampleRateHz = format.positiveInt(IjkMediaMeta.IJKM_KEY_SAMPLE_RATE),
            channelCount = layout?.let(java.lang.Long::bitCount)
        )
    }
    return PlaybackMediaInfo(video, audio)
}

private fun IMediaFormat.text(key: String): String? = getString(key)?.trim()?.takeIf { it.isNotEmpty() }
private fun IMediaFormat.positiveLong(key: String): Long? = text(key)?.toLongOrNull()?.takeIf { it > 0 }
private fun IMediaFormat.positiveInt(key: String): Int? = text(key)?.toIntOrNull()?.takeIf { it > 0 }
