package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test

class VideoPresentationTest {
    @Test fun fitKeepsTheFullPictureOnWideAndPortraitScreens() {
        assertEquals(VideoPresentation(1920, 1080, 1f), videoPresentation(2340, 1080, 1920, 1080))
        val portrait = videoPresentation(1080, 2340, 1920, 1080)
        assertEquals(1080, portrait.surfaceWidth)
        assertEquals(607, portrait.surfaceHeight)
        assertEquals(1f, portrait.scale, 0f)
    }

    @Test fun cropCoversBothAxesWithoutStretching() {
        for ((width, height) in listOf(2340 to 1080, 1080 to 2340)) {
            val fit = videoPresentation(width, height, 1920, 1080)
            val crop = videoPresentation(width, height, 1920, 1080, mode = VideoScaleMode.CROP)
            assertEquals(fit.surfaceWidth, crop.surfaceWidth)
            assertEquals(fit.surfaceHeight, crop.surfaceHeight)
            assertTrue(crop.surfaceWidth * crop.scale >= width - .01f)
            assertTrue(crop.surfaceHeight * crop.scale >= height - .01f)
            assertEquals(16f / 9f, crop.surfaceWidth.toFloat() / crop.surfaceHeight, .005f)
        }
    }

    @Test fun originalSizeUsesVideoPixelsAndSampleAspectRatioInsteadOfViewportSize() {
        val original = videoPresentation(1920, 1080, 720, 576, 16, 15, VideoScaleMode.ORIGINAL)
        assertEquals(768f, original.surfaceWidth * original.scale, .01f)
        assertEquals(576f, original.surfaceHeight * original.scale, .01f)
        val portrait = videoPresentation(1080, 2340, 1920, 1080, mode = VideoScaleMode.ORIGINAL)
        assertEquals(1920f, portrait.surfaceWidth * portrait.scale, 2f)
        assertEquals(1080f, portrait.surfaceHeight * portrait.scale, .01f)
    }

    @Test fun zoomMultipliesTheChosenModeWithoutGrowingTheSurfaceBuffer() {
        for (mode in VideoScaleMode.entries) {
            val base = videoPresentation(2340, 1080, 1920, 1080, mode = mode)
            val zoomed = videoPresentation(2340, 1080, 1920, 1080, mode = mode, zoom = 2f)
            assertEquals(base.surfaceWidth, zoomed.surfaceWidth)
            assertEquals(base.surfaceHeight, zoomed.surfaceHeight)
            assertEquals(base.scale * 2, zoomed.scale, .0001f)
        }
    }

    @Test fun invalidMetadataAndZoomCannotProduceEmptyOrNonFiniteSurfaces() {
        assertEquals(VideoPresentation(1080, 1920, 1f), videoPresentation(1080, 1920, 0, 0))
        assertEquals(VideoPresentation(1, 1, 1f), videoPresentation(0, -1, 1920, 1080))
        assertEquals(videoPresentation(1920, 1080, 720, 576), videoPresentation(1920, 1080, 720, 576, 0, -1))
        assertEquals(1f, normalizeVideoZoom(Float.NaN), 0f)
        assertEquals(1f, normalizeVideoZoom(Float.POSITIVE_INFINITY), 0f)
        assertEquals(.5f, normalizeVideoZoom(-100f), 0f)
        assertEquals(4f, normalizeVideoZoom(100f), 0f)
    }
}
