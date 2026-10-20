# Video information

Open **Player settings → Video information** from any playback source. The
scrollable subpage groups the selected video and audio streams and inherits the
player popup's dark palette, white values and light-grey labels in both app themes.
Long values wrap rather than being truncated; the same page works in landscape.

Video fields: source resolution, source frame rate, codec, stream bitrate, codec
profile and pixel format. Audio fields: codec, stream bitrate, sample rate and
channel count (when FFmpeg provides a known channel-layout mask). Audio-only and
video-only media leave the other group's values unknown. Missing, malformed,
non-positive or unavailable metadata is shown as **Unknown**.

`PlayerManager` extracts a `PlaybackMediaInfo` snapshot from IJK's cached track
metadata after preparation, audio/metadata events, video-size changes and track
selection. `PlayerState` carries it through `PlayerSettingsViewModel`; Compose
never reads the native player or opens another connection. Source changes reset
the state and stopping/error cleanup clears the snapshot. Pause, speed and zoom
do not change the source metadata or reload playback when opening this page.

Native stream indices are used rather than compact track-array positions. Bitrate
is parsed from the raw metadata string as a 64-bit integer: IJK 0.8.8's cached
stream `mBitrate` field was parsed through `getInt` and can overflow. FPS is the
positive `fps_num / fps_den` ratio, preserving rates such as 30000/1001. It is
separate from the measured output FPS in playback diagnostics and is not multiplied
by playback speed. Decimal kbps/Mbps use bits per second and powers of 1000.
Container total bitrate, HLS variant bandwidth and download speed are never used
as substitutes for a missing video/audio stream bitrate.

The Qt reference is `PlayerController`'s selected-track metadata and the video
information panel in `qml/Main.qml`, with runtime/source-rate distinctions in
`VIBEDOCS/PlayerRuntime.md`. Android also reports the selected audio stream's
metadata using the existing IJK pipeline.

## Verification

`PlaybackMediaInfoTest` checks fractional frame rates, 64-bit rates, gaps in native
stream indices, current audio selection, absent streams and invalid/missing values.
`PlayerSettingsSelectionTest` opens the production page in both orientations with
the generated AV1/AAC fixture, checks resolution/FPS/codecs/sample rate and the
native stream bitrates, preserves pause and verifies that stopping clears the
snapshot.

Verified on 2026-10-21: `assembleDebug`, `assembleDebugAndroidTest`, all 121 JVM
tests and `lint` pass (zero errors, three existing warnings). HUAWEI TAS-AL00 /
API 31 passes the extended `PlayerSettingsSelectionTest` in both orientations:
160×96, 24 fps, AV1/AAC, 5504 bps video and 32263 bps audio, and 48000 Hz audio.
Screenshots confirm readable labels/values and scrolling; the test also verifies
pause preservation, subpage return and clearing the snapshot on stop. It uses an
isolated application ID and a generated local fixture, without accessing configured
services or user media. The temporary USB stay-awake setting is restored afterward.

## References

- [Pinned IJK metadata keys and parsing](https://github.com/bilibili/ijkplayer/blob/k0.8.8/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaMeta.java)
- [Pinned IJK media-format adapter](https://github.com/bilibili/ijkplayer/blob/k0.8.8/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/misc/IjkMediaFormat.java)
- [Android MediaFormat units](https://developer.android.com/reference/android/media/MediaFormat)
