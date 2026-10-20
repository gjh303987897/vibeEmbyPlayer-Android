package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test

class PictureInPicturePolicyTest {
    private val playing = PipPlaybackEligibility(true, true, true, true, true, true)

    @Test fun onlyTheActivePreparedVideoPlayerCanEnter() {
        assertEquals(PipEntryBlock.NONE, playing.block)
        for (blocked in listOf(playing.copy(playerVisible = false), playing.copy(hasVideo = false),
            playing.copy(prepared = false), playing.copy(playing = false), playing.copy(failed = true),
            playing.copy(sleepExpired = true))) {
            assertEquals(PipEntryBlock.NOT_PLAYING, blocked.block)
            assertFalse(blocked.autoEnter(true, false))
        }
    }

    @Test fun unsupportedPermissionAndPrivatePlaybackHaveExplicitEntryReasons() {
        assertEquals(PipEntryBlock.UNSUPPORTED, playing.copy(supported = false).block)
        assertEquals(PipEntryBlock.PERMISSION, playing.copy(permissionAllowed = false).block)
        assertEquals(PipEntryBlock.PRIVATE_VIDEO, playing.copy(privatePlayback = true).block)
        assertFalse(playing.copy(privatePlayback = true).autoEnter(true, false))
    }

    @Test fun disablingAutomaticEntryOrOpeningAModalDoesNotDisableExplicitEntry() {
        assertTrue(playing.autoEnter(true, false))
        assertFalse(playing.autoEnter(false, false))
        assertFalse(playing.autoEnter(true, true))
        assertEquals(PipEntryBlock.NONE, playing.block)
    }

    @Test fun ordinaryLandscapePortraitAndAnamorphicRatiosRemainAccurate() {
        assertRatio(16.0 / 9, pipAspectRatio(1920, 1080))
        assertRatio(9.0 / 16, pipAspectRatio(1080, 1920))
        assertRatio(4.0 / 3, pipAspectRatio(720, 576, 16, 15))
        assertEquals(pipAspectRatio(720, 576), pipAspectRatio(720, 576, 0, -1))
    }

    @Test fun extremeAspectRatiosClampExactlyToTheInclusivePlatformLimits() {
        assertEquals(PipAspectRatio(239, 100), pipAspectRatio(7680, 1080))
        assertEquals(PipAspectRatio(100, 239), pipAspectRatio(1080, 7680))
        assertEquals(PipAspectRatio(239, 100), pipAspectRatio(Int.MAX_VALUE, 1, Int.MAX_VALUE, 1))
        assertEquals(PipAspectRatio(100, 239), pipAspectRatio(1, Int.MAX_VALUE, 1, Int.MAX_VALUE))
        for (size in listOf(239 to 100, 100 to 239, 240 to 100, 100 to 240,
            41_841_005 to 100_000_000, 238_999_999 to 100_000_000)) {
            val ratio = pipAspectRatio(size.first, size.second)
            assertTrue(ratio.width.toDouble() / ratio.height in (100.0 / 239)..(239.0 / 100))
        }
    }

    @Test fun unknownSizeUsesASafeRatioWhilePolicyStillRejectsAudioOnlyEntry() {
        assertEquals(PipAspectRatio(16, 9), pipAspectRatio(0, 0))
        assertEquals(PipAspectRatio(16, 9), pipAspectRatio(-1, 1080))
        assertEquals(PipEntryBlock.NOT_PLAYING, playing.copy(hasVideo = false).block)
    }

    @Test fun chromeHidesDuringEntryAndPipButReturnsAfterFailedEntryOrExpansion() {
        val active = PlayerPipState(supported = true, entryBlock = PipEntryBlock.NONE)
        assertTrue(active.canEnter)
        assertFalse(active.hideChrome)
        assertTrue(active.copy(entering = true).hideChrome)
        assertFalse(active.copy(entering = true).canEnter)
        assertTrue(active.copy(inPictureInPicture = true).hideChrome)
        assertFalse(active.copy(inPictureInPicture = true).canEnter)
        assertFalse(active.copy(entering = false, inPictureInPicture = false).hideChrome)
    }

    private fun assertRatio(expected: Double, actual: PipAspectRatio) {
        assertEquals(expected, actual.width.toDouble() / actual.height, .000001)
    }
}
