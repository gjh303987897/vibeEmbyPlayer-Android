package com.vibeplayer.app.player

import java.util.Locale

/** Decoder path reported for the selected IJK audio stream. */
enum class AudioDecodeMode {
    HARDWARE,
    SOFTWARE,
    UNSUPPORTED
}

data class AudioDecodeInfo(
    val mode: AudioDecodeMode,
    val mimeType: String,
    val codecLabel: String
)


fun audioCodecLabel(codecName: String): String = when (codecName.lowercase(Locale.US)) {
    "ac3" -> "Dolby Digital (AC-3)"
    "eac3", "e-ac-3" -> "Dolby Digital Plus (E-AC-3)"
    "truehd" -> "Dolby TrueHD"
    "dca", "dts" -> "DTS"
    "dts-hd" -> "DTS-HD Master Audio"
    "aac" -> "AAC"
    "mp3", "mp2" -> "MP3"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "alac" -> "ALAC"
    else -> codecName.uppercase(Locale.US)
}

/** Decoder evidence for the selected stream; a missing decoder must remain visible. */
internal fun decideAudioDecode(
    codecName: String?,
    hardwareDecoder: Boolean,
    decoderAvailable: Boolean = true
): AudioDecodeInfo? {
    val normalized = codecName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return AudioDecodeInfo(
        mode = when {
            !decoderAvailable -> AudioDecodeMode.UNSUPPORTED
            hardwareDecoder -> AudioDecodeMode.HARDWARE
            else -> AudioDecodeMode.SOFTWARE
        },
        mimeType = normalized,
        codecLabel = audioCodecLabel(normalized)
    )
}
