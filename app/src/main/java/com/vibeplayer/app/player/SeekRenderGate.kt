package com.vibeplayer.app.player

/** A demuxer seek acknowledgement is earlier than output from the new audio/video queues. */
internal class SeekRenderGate(needsAudio: Boolean, needsVideo: Boolean) {
    private var demuxComplete = false
    private var audioReady = !needsAudio
    private var videoReady = !needsVideo
    val ready: Boolean get() = demuxComplete && audioReady && videoReady

    fun demuxCompleted() { demuxComplete = true }
    fun audioRendered() { if (demuxComplete) audioReady = true }
    fun videoRendered() { if (demuxComplete) videoReady = true }
}
