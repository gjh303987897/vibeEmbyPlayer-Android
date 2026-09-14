package com.vibeplayer.app.data.remote

import android.media.MediaCodecList
import android.util.Log
import com.vibeplayer.app.data.remote.dto.MediaStreamDto

/**
 * How a media server should deliver the audio of one playback.
 *
 * The player asks for a *static* stream whenever possible: the server simply
 * hands back the original file, which is the cheapest stream for the server and
 * gives the client the widest container and subtitle support. That only holds
 * while the device can actually decode the audio track.
 */
internal enum class AudioStreamPlan {
    /** The device decodes the original audio: request the file untouched. */
    DIRECT,

    /** The device cannot decode it, so the server must re-encode the audio. */
    TRANSCODE_AUDIO
}

/**
 * Reports which audio codecs this device can decode.
 *
 * AC-3, E-AC-3 (including E-AC-3 JOC), the DTS family, Dolby TrueHD/AC-4/MAT and
 * MPEG-H are licensed extras: plenty of phones ship without them even though the
 * framework declares the MIME types. Serving the original file in that situation
 * makes Media3 drop the audio track silently - the title plays with no sound and
 * no error is ever raised, which is the "some movies have no audio" report.
 *
 * Codec names never agree between the two sides that matter here: the server
 * reports `ac-3`, `truehd` or `dca`, the framework advertises `audio/ac3`,
 * `audio/vnd.dolby.mlp` and `audio/vnd.dts`. Both are folded into the small
 * canonical space below before they are compared, so no side needs a guess.
 */
internal object AudioPlaybackCapability {

    private const val TAG = "AudioCapability"

    /** Decoders this device offers, as canonical keys. Built on first use because
     * assembling a [MediaCodecList] walks the entire codec table. */
    private val supported: Set<String> by lazy {
        buildSet {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                    if (info.isEncoder) return@forEach
                    info.supportedTypes.forEach { mime ->
                        if (mime.startsWith(AUDIO_PREFIX)) {
                            canonicalMime(mime.removePrefix(AUDIO_PREFIX))?.let(::add)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "decoder query failed, assuming direct playback", it) }
        }
    }

    /**
     * Canonical codec of the audio track the player will actually select, or null
     * when the source advertises no audio to judge.
     *
     * Track order decides it - [playerAudioStreamIndex] resolves the same track
     * the player picks - so a file whose *first* audio track is DTS counts as
     * undecodable even when a later track would have been AAC.
     */
    fun playableAudioCodec(streams: List<MediaStreamDto>): String? =
        audioStreams(streams).getOrNull(playerAudioStreamIndex(streams))
            .let { stream -> canonicalServerCodec(stream?.Codec) }

    /** The plan for [streams]: static playback, or server-side audio transcoding. */
    fun planFor(streams: List<MediaStreamDto>): AudioStreamPlan {
        val codec = playableAudioCodec(streams) ?: return AudioStreamPlan.DIRECT
        // An empty set means the decoder query blew up; keep the old behaviour
        // rather than transcoding the whole library because of a logging hiccup.
        if (supported.isEmpty()) return AudioStreamPlan.DIRECT
        return if (codec in supported) AudioStreamPlan.DIRECT else AudioStreamPlan.TRANSCODE_AUDIO
    }

    /**
     * `audio/vnd.dts.hd` -> `dts_hd`. Unknown audio MIME types fall through as
     * their normalised suffix so a newly advertised codec is still comparable.
     */
    internal fun canonicalMime(subtype: String): String? {
        val key = subtype.trim().lowercase().replace('-', '_')
        if (key.isEmpty()) return null
        MIME_ALIASES[key]?.let { return it }
        // AAC object types arrive as audio/mp4a.40.02 and friends.
        if (key.startsWith("mp4a")) return AAC
        // Vendor prefixes carry no codec meaning: vnd_dolby_mlp -> mlp -> truehd.
        val stripped = key.removePrefix("vnd_").removePrefix("dolby_").removePrefix("olecom_")
        MIME_ALIASES[stripped]?.let { return it }
        if (stripped.startsWith("pcm")) return PCM
        return stripped.takeIf { it.isNotEmpty() }
    }

    /**
     * Server-side codec name (`ac-3`, `E-AC-3`, `DCA`, `truehd`) -> canonical key.
     *
     * Returns null when nothing usable was advertised: a missing or blank codec
     * means the server told us nothing, and in that case the previous static
     * behaviour is kept instead of speculatively transcoding.
     */
    internal fun canonicalServerCodec(raw: String?): String? {
        val key = raw?.trim()?.lowercase()?.replace('-', '_')?.replace('.', '_') ?: return null
        if (key.isEmpty() || key in NON_CODEC_LABELS) return null
        SERVER_ALIASES[key]?.let { return it }
        if (key.startsWith("mp4a") || key.startsWith("aac")) return AAC
        if (key.startsWith("pcm_") || key.startsWith("pcm")) return PCM
        if (key.startsWith("dts")) return key // dts, dts_hd, dts_uhd
        return key
    }

    /** Audio streams in declaration order, which is the order the player walks. */
    internal fun audioStreams(streams: List<MediaStreamDto>): List<MediaStreamDto> =
        streams.filter { it.Type.equals("Audio", ignoreCase = true) }

    /**
     * Index of the audio track the player will use inside [audioStreams]: the
     * default track when one is flagged, otherwise the first.
     */
    internal fun playerAudioStreamIndex(streams: List<MediaStreamDto>): Int {
        val audio = audioStreams(streams)
        val default = audio.indexOfFirst { it.IsDefault == true }
        return if (default >= 0) default else 0
    }

    private const val AUDIO_PREFIX = "audio/"
    private const val AAC = "aac"
    private const val PCM = "pcm"

    /** Values that appear in a codec field without naming an audio codec. */
    private val NON_CODEC_LABELS = setOf("mp4", "raw", "unknown", "none", "n_a")

    /** Framework MIME subtype -> canonical key. */
    private val MIME_ALIASES = mapOf(
        "mp4a_latm" to AAC,
        "ac3" to "ac3",
        "eac3" to "eac3",
        "eac3_joc" to "eac3",
        "ac4" to "ac4",
        "mlp" to "truehd",
        "mat" to "mat",
        "dts" to "dts",
        "dts_hd" to "dts_hd",
        "dts_uhd" to "dts_uhd",
        "mpeg" to "mpeg",
        "flac" to "flac",
        "alac" to "alac",
        "opus" to "opus",
        "vorbis" to "vorbis",
        "amr_nb" to "amr_nb",
        "amr_wb" to "amr_wb",
        "g711_alaw" to "g711",
        "g711_mlaw" to "g711",
        "msgsm" to "gsm"
    )

    /** Server/FFmpeg codec spelling -> canonical key. */
    private val SERVER_ALIASES = mapOf(
        "aac_latm" to AAC,
        "aac_ld" to AAC,
        "ac_3" to "ac3",
        "eac_3" to "eac3",
        "eac_3_joc" to "eac3",
        "eac3_joc" to "eac3",
        "ac_4" to "ac4",
        "true_hd" to "truehd",
        "mlp" to "truehd",
        "dca" to "dts",
        "dtshd" to "dts_hd",
        "dts_uhd" to "dts_uhd",
        "dtc" to "dts",
        "mp3" to "mpeg",
        "mp2" to "mpeg",
        "mp1" to "mpeg",
        "mp3adu" to "mpeg",
        "mp3_on_4" to "mpeg",
        "qclp" to "qclp",
        "wmav1" to "wma",
        "wmav2" to "wma",
        "wmapro" to "wma"
    )
}
