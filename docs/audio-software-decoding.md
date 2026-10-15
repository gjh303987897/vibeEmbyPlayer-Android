# IJKPlayer audio decoding

## Playback path

Emby, Jellyfin, WebDAV, local SAF, IPTV and link playback share one `PlayerManager`. Emby/Jellyfin
URLs retain `static=true`; no server-side audio-transcoding fallback is used.

IJK's MediaCodec video path, auto rotation, resolution changes and timed-text decoding are enabled
explicitly. Audio decoding uses IJK's FFmpeg path. `PlayerManager` reads the public `MediaInfo`
audio-decoder evidence and selected stream metadata for the `AudioDecodeNotice` shown by each player
page. It does not infer successful decoding solely from a codec name.

Resetting stock IJK recreates its native player and drops the video Surface. The shared player
rebinds its existing `SurfaceHolder` after every reset. `IjkPlayerView` fits the video using its
sample aspect ratio instead of stretching the picture.

## Native dependencies

The upstream 0.8.8 Maven ABI AARs are **minimal builds**. Their embedded FFmpeg configuration disables
AC-3, E-AC-3, DTS (`dca`), TrueHD, subtitle decoders and Matroska demuxing. They also contain no TLS
backend. Packaging those binaries successfully does not establish parity with the old player.

The shipped native dependencies must include the required LGPL decoders/demuxers and the AES
`crypto` protocol used by HLS. DASH additionally needs FFmpeg's libxml2 demuxer dependency. See
`THIRD_PARTY_LICENSES.md` and the native build recipe for pinned sources and redistribution notices.

## Network, subtitles and system controls

Remote HTTP/HTTPS playback uses `PlaybackHttpProxy`: the existing `OkHttpClientFactory` performs
certificate-chain and hostname verification, authentication, redirects and range requests. The
proxy streams responses over loopback HTTP to IJK and rewrites HLS resources and DASH base/resource
URLs. The saved per-server self-signed option chooses the same independently configured OkHttp
client used for API requests; no `tls_verify=0` native option is used. Local SAF descriptors and the
verified encrypted-HLS loopback server remain separate input paths.

IJK subtitle tracks use `selectTrack()` / `deselectTrack()`. All five player screens expose subtitle
selection. External Emby/Jellyfin SRT/WebVTT subtitles use authenticated OkHttp and the Compose text
overlay; the position ticker updates only external cues. Embedded text and bitmap cues are scheduled
against IJK's playback master clock, including expiration when no further video frame arrives.

The distributed `ijkplayer-bitmap-subtitles.patch` and native support files add PGS/DVD/DVB bitmap
snapshots and the `NativeBitmapSubtitles.read()` JNI bridge without changing the upstream Java AAR.
Snapshots own their palette/index storage; JNI creates premultiplied RGBA bitmaps only when the
revision changes. Multiple regions retain their original canvas coordinates. `IjkPlayerView` draws
them within the actual video rectangle, including sample aspect ratio and letterboxing, without
allocating during drawing. Text and bitmap subtitles are mutually exclusive. Turning subtitles
off, switching tracks, seeking, stopping or resetting clears the previous cue; native seek/serial
gates prevent queued frames from restoring an old image. JNI constructors and native names are
retained by release shrinking rules. ASS text parsing accepts FFmpeg's decoded event layout,
handles escaped line breaks and bounds writes to the output buffer.

`PlaybackAudioFocus` requests Android audio focus before `PlayerManager` starts IJK. Native
`start-on-prepared` is disabled so it cannot bypass focus. Delayed/transient focus gain can resume
an interrupted session; manual pause/stop, permanent loss and headset disconnect cancel resumption.
Volume changes preserve any ducking multiplier. All focus callbacks run on the main thread.

Android Framework `MediaSession` handles lock-screen and Bluetooth play/pause/seek/stop commands
and reports preparation, buffering and errors. `PlaybackService` publishes a session-linked media
notification with explicit play/pause PendingIntents. Metadata and notifications update only when
their relevant fields change. A system play command is idempotent; media keys use the framework
dispatcher.

## Verification

```text
./gradlew :app:assembleDebug :app:lint :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Coding verification passed all four tasks above and `:app:minifyReleaseWithR8`: 29 JVM tests,
0 lint errors and 56 lint warnings. The packaged native-library digests, ELF alignment and APK
alignment checks also passed.

The Debug APK must contain `libijkffmpeg.so`, `libijkplayer.so`, and `libijksdl.so` for every shipped
ABI. The AC-3 and DTS device regressions require a selected matching stream, `avcodec` decoder,
native decoded-start and audio-rendering-start events, no playback error and advancing position.

Host build verification uses all four Gradle tasks above. `PlaybackHttpProxyTest` uses OkHttp
MockWebServer, matching the application's OkHttp version, to exercise range seeking, authenticated
HLS redirects with CDN credential isolation, and inherited DASH segment templates without
JDK-only HTTP server APIs. Release R8 verification additionally checks that the JNI entry point
and both subtitle model constructors retain their names and descriptors.
The upstream Java AAR has no consumer rules, so the application explicitly retains IJK's Java
package as well: native J4A bindings resolve fields and event/codec callbacks by name. Tink's
Error Prone annotation dependency is included so release shrinking resolves its class annotations.

The packaged APK is checked for all 12 IJK libraries across armeabi-v7a, arm64-v8a, x86 and x86_64.
Their SHA-256 digests must match the rebuilt AAR contents; every ELF LOAD segment must use at least
16 KB alignment, and Android `zipalign -c -P 16 4` must pass.

Host smoke verification exercised a live HTTPS server with a self-signed localhost certificate:
strict TLS rejection, explicit trust, authenticated redirect, HLS key/segment/query rewriting,
HTTP Range and HEAD, unknown-route rejection, and DASH inherited-template/base routing. Host
`ffprobe` demuxed the existing AC-3 fixture through that live HTTPS proxy. This proves the network
adapter, not Android native decoding or audible output.

Host subtitle smoke checks compile the actual palette conversion and ASS parsing code. They cover
transparent/premultiplied colors, positive and negative strides, output padding, invalid indices,
malformed ASS events, escaped line breaks, drawing overrides and the fixed output buffer boundary.
The old ASS parser fails on a normal FFmpeg decoded event; the corrected parser passes. Applying
the NDK and subtitle patches to fresh pinned sources reproduces the cached native sources exactly.

Android device execution is optional for this coding deliverable. The existing
`./gradlew :app:connectedDebugAndroidTest` remains available for later playback checks; it was not
run as part of this delivery. Building its APK does not assert audible output or device rendering.

## Official references

- IJK Java API: <https://github.com/bilibili/ijkplayer/blob/k0.8.8/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java>
- IJK player options: <https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay_options.h>
- Android media buttons: <https://developer.android.com/media/legacy/media-buttons>
- Android network security: <https://developer.android.com/privacy-and-security/security-config>
- Android bitmap NDK API: <https://developer.android.com/ndk/reference/group/bitmap>
- Android audio focus: <https://developer.android.com/media/optimize/audio-focus>
