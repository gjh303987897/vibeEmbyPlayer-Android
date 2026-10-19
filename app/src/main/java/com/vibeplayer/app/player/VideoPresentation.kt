package com.vibeplayer.app.player

import kotlin.math.max
import kotlin.math.min

enum class VideoScaleMode { FIT, CROP, ORIGINAL }

const val MIN_VIDEO_ZOOM = .5f
const val MAX_VIDEO_ZOOM = 4f

fun normalizeVideoZoom(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM) else 1f

data class VideoPresentation(val surfaceWidth: Int, val surfaceHeight: Int, val scale: Float)

/** Pixel geometry, including sample aspect ratio; display density must not affect original size. */
fun videoPresentation(
    viewportWidth: Int, viewportHeight: Int, videoWidth: Int, videoHeight: Int,
    sarNum: Int = 1, sarDen: Int = 1,
    mode: VideoScaleMode = VideoScaleMode.FIT, zoom: Float = 1f
): VideoPresentation {
    if (viewportWidth <= 0 || viewportHeight <= 0 || videoWidth <= 0 || videoHeight <= 0)
        return VideoPresentation(viewportWidth.coerceAtLeast(1), viewportHeight.coerceAtLeast(1), 1f)
    val sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
    val aspect = videoWidth.toDouble() / videoHeight * sar
    val fitHeight = min(viewportHeight.toDouble(), viewportWidth / aspect)
    val width = (fitHeight * aspect).toInt().coerceIn(1, viewportWidth)
    val height = fitHeight.toInt().coerceIn(1, viewportHeight)
    val baseScale = when (mode) {
        VideoScaleMode.FIT -> 1.0
        VideoScaleMode.CROP -> max(viewportWidth.toDouble() / width, viewportHeight.toDouble() / height)
        VideoScaleMode.ORIGINAL -> videoHeight.toDouble() / height
    }
    // Transform the existing surface, avoiding larger buffers or decoder/Surface recreation.
    return VideoPresentation(width, height, (baseScale * normalizeVideoZoom(zoom)).toFloat())
}
