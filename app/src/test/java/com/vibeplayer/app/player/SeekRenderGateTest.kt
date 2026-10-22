package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test

class SeekRenderGateTest {
    @Test fun demuxAndVideoAloneMustNotExposePlaybackWhileAudioIsLate() {
        val gate = SeekRenderGate(needsAudio = true, needsVideo = true)
        gate.demuxCompleted()
        assertFalse(gate.ready)
        gate.videoRendered()
        assertFalse(gate.ready)
        gate.audioRendered()
        assertTrue(gate.ready)
    }

    @Test fun audioAloneMustNotExposePlaybackWhileVideoIsLate() {
        val gate = SeekRenderGate(needsAudio = true, needsVideo = true)
        gate.demuxCompleted()
        gate.audioRendered()
        assertFalse(gate.ready)
        gate.videoRendered()
        assertTrue(gate.ready)
    }

    @Test fun oldOutputBeforeDemuxAcknowledgementCannotReleaseNewSeek() {
        val gate = SeekRenderGate(needsAudio = true, needsVideo = true)
        gate.audioRendered()
        gate.videoRendered()
        gate.demuxCompleted()
        assertFalse(gate.ready)
        gate.videoRendered()
        assertFalse(gate.ready)
        gate.audioRendered()
        assertTrue(gate.ready)
    }

    @Test fun silentVideoAndAudioOnlyDoNotWaitForAbsentStreams() {
        val silent = SeekRenderGate(needsAudio = false, needsVideo = true)
        silent.demuxCompleted()
        assertFalse(silent.ready)
        silent.videoRendered()
        assertTrue(silent.ready)
        val audio = SeekRenderGate(needsAudio = true, needsVideo = false)
        audio.demuxCompleted()
        audio.audioRendered()
        assertTrue(audio.ready)
        val empty = SeekRenderGate(needsAudio = false, needsVideo = false)
        assertFalse(empty.ready)
        empty.demuxCompleted()
        assertTrue(empty.ready)
    }

    @Test fun replacementStartsWithFreshOutputReadiness() {
        val first = SeekRenderGate(true, true)
        first.demuxCompleted()
        first.audioRendered()
        first.videoRendered()
        assertTrue(first.ready)
        val replacement = SeekRenderGate(true, true)
        replacement.demuxCompleted()
        replacement.videoRendered()
        assertFalse(replacement.ready)
    }
}
