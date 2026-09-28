package com.vibeplayer.app.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which decoder ends up rendering the selected audio track.
 *
 * Emby/Jellyfin/WebDAV streams are always requested with `static=true`, i.e. the
 * original file is sent untouched. That is the right thing for battery, server load
 * and multi-user scenarios, but it means a track the phone cannot decode by itself
 * (AC-3 on a device without a Dolby licence, DTS / DTS-HD without a DTS licence,
 * TrueHD almost anywhere) simply produces *picture without sound*: Media3 drops an
 * audio track it has no renderer for and reports no error at all.
 *
 * The Media3 FFmpeg decoder extension (`androidx.media3.decoder.ffmpeg`) adds an
 * on-device software renderer, so those tracks keep playing through a direct stream
 * and are decoded on the phone. This enum is what the UI reports back to the user.
 */
enum class AudioDecodeMode {
    /** The platform's own MediaCodec decoder handles the track - nothing to mention. */
    HARDWARE,

    /**
     * The platform cannot decode the track, but the bundled FFmpeg renderer can:
     * audio is being decoded in software on the device.
     */
    SOFTWARE,

    /**
     * Neither MediaCodec nor FFmpeg can decode the track, so playback is silent.
     * Happens when the FFmpeg native library was not packaged into this build.
     */
    UNSUPPORTED,
}

/** What the player decided about [mimeType]'s decoder, with a human-readable [codecLabel]. */
data class AudioDecodeInfo(
    val mode: AudioDecodeMode,
    val mimeType: String,
    val codecLabel: String
)

/**
 * Decoder availability for the audio formats this app can meet.
 *
 * Everything is memoised: the MediaCodec registry is scanned once per process and the
 * FFmpeg native query is cached per MIME type, so calling [decide] from a track
 * callback is cheap.
 */
@OptIn(UnstableApi::class)
object AudioDecodeSupport {

    private const val TAG = "AudioDecode"

    /** Media3's FFmpeg decoder entry point. Present as soon as the extension is packaged. */
    private const val FFMPEG_LIBRARY_CLASS = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    /** Mime types advertised by the device's own decoders, lower-cased. */
    val hardwareAudioMimeTypes: Set<String> by lazy(::scanHardwareAudioMimeTypes)

    /**
     * Whether the FFmpeg renderer is usable in *this* build, i.e. its Java half is on the
     * classpath and `libffmpegJNI.so` actually loaded. `FfmpegLibrary.isAvailable()` caches
     * the load attempt, so this is a cheap call after the first one.
     */
    val softDecoderAvailable: Boolean by lazy {
        runCatching { callFfmpeg("isAvailable") as? Boolean == true }.getOrDefault(false)
            .also {
                if (it) {
                    Log.i(TAG, "FFmpeg audio decoder available (version ${ffmpegVersion()})")
                } else {
                    Log.i(TAG, "FFmpeg audio decoder not usable in this build")
                }
            }
    }

    /** FFmpeg's version string, or null when the library is unavailable. For logs only. */
    fun ffmpegVersion(): String? =
        runCatching { callFfmpeg("getVersion") as? String }.getOrNull()

    /** Whether the FFmpeg renderer accepts [mimeType] (a container MIME type). */
    fun softDecoderSupports(mimeType: String): Boolean {
        if (!softDecoderAvailable) return false
        return ffmpegSupportCache.getOrPut(mimeType) {
            runCatching { callFfmpeg("supportsFormat", mimeType) as? Boolean == true }
                .getOrDefault(false)
        }
    }

    /**
     * Decides how [mimeType] will be rendered. [rendererSupportsTrack] is Media3's own verdict
     * for the track (`Tracks.Group.isTrackSupported`): it is the only authoritative signal, so
     * it wins. Returns null for absent formats and for video/text MIME types, which are not
     * this object's business.
     */
    fun decide(mimeType: String?, rendererSupportsTrack: Boolean): AudioDecodeInfo? =
        decideAudioDecode(
            mimeType = mimeType,
            rendererSupportsTrack = rendererSupportsTrack,
            hardwareMimeTypes = hardwareAudioMimeTypes,
            softDecoderAvailable = softDecoderAvailable,
            softDecoderSupports = ::softDecoderSupports
        )

    private fun scanHardwareAudioMimeTypes(): Set<String> = runCatching {
        val found = HashSet<String>()
        for (info in deviceCodecInfos()) {
            if (!info.supportsDecoding()) continue
            // A raw/passthrough decoder says "yes" to every container format and would
            // hide the very cases we want to report, so ignore it.
            if (info.name.startsWith("OMX.google.raw.decoder") ||
                info.name.startsWith("c2.android.raw.decoder")
            ) {
                continue
            }
            for (type in info.supportedTypes) {
                if (MimeTypes.isAudio(type)) found += type.lowercase(Locale.US)
            }
        }
        Log.i(TAG, "Device audio decoders: ${found.sorted().joinToString()}")
        found
    }.getOrDefault(emptySet())

    /**
     * The *visible* registry only - flags `0` / the pre-Q static accessors. Licensed codecs
     * that ship in the system image but are switched off for the device must not count,
     * because MediaCodec cannot open them either (that is exactly the AC-3/DTS case).
     */
    @Suppress("DEPRECATION")
    private fun deviceCodecInfos(): List<MediaCodecInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecList(0).codecInfos.toList()
        } else {
            (0 until MediaCodecList.getCodecCount())
                .mapNotNull { MediaCodecList.getCodecInfoAt(it) }
                .toList()
        }

    /**
     * Whether this component can decode. `MediaCodecInfo.isDecoder()` is not public API, so
     * "not an encoder" is the usable signal; the name check keeps the rare component that is
     * registered as both from being dismissed.
     */
    private fun MediaCodecInfo.supportsDecoding(): Boolean =
        !isEncoder || name.contains("decoder", ignoreCase = true)

    private fun callFfmpeg(method: String, argument: Any? = null): Any? {
        val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
        return if (argument == null) {
            clazz.getMethod(method).invoke(null)
        } else {
            clazz.getMethod(method, String::class.java).invoke(null, argument)
        }
    }

    private val ffmpegSupportCache = ConcurrentHashMap<String, Boolean>()
}

/**
 * Pure decision used by [AudioDecodeSupport.decide]; separated out so it can be unit tested
 * without a device, a MediaCodec registry or a native library.
 */
@OptIn(UnstableApi::class)
internal fun decideAudioDecode(
    mimeType: String?,
    rendererSupportsTrack: Boolean,
    hardwareMimeTypes: Set<String>,
    softDecoderAvailable: Boolean,
    softDecoderSupports: (String) -> Boolean
): AudioDecodeInfo? {
    if (mimeType == null || !MimeTypes.isAudio(mimeType)) return null
    val label = audioCodecLabel(mimeType)
    val hardware = hardwareMimeTypes.contains(mimeType.lowercase(Locale.US))
    val software = !hardware && softDecoderAvailable && softDecoderSupports(mimeType)
    return when {
        // Media3 has no renderer at all: this is the silent-video case worth explaining.
        !rendererSupportsTrack -> AudioDecodeInfo(AudioDecodeMode.UNSUPPORTED, mimeType, label)
        hardware -> AudioDecodeInfo(AudioDecodeMode.HARDWARE, mimeType, label)
        software -> AudioDecodeInfo(AudioDecodeMode.SOFTWARE, mimeType, label)
        // Some renderer we did not predict handles it - do not nag about a non-problem.
        else -> null
    }
}

/** Marketing name for an audio MIME type, so a hint reads "DTS-HD Master Audio", not "audio/vnd.dts.hd". */
@OptIn(UnstableApi::class)
fun audioCodecLabel(mimeType: String): String = when (mimeType) {
    MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus (E-AC-3)"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC-3 JOC)"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD Master Audio"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_DTS_X -> "DTS:X"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_AAC -> "AAC"
    else -> mimeType.substringAfter('/').substringBefore(';').uppercase(Locale.US)
}

/** Keep the class loaded from the app process so the first decision does not pay for it. */
@OptIn(UnstableApi::class)
internal fun warmUpAudioDecodeSupport() {
    runCatching {
        AudioDecodeSupport.hardwareAudioMimeTypes
        AudioDecodeSupport.softDecoderAvailable
    }
}
