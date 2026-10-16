# IJKPlayer audio decoding

## Playback path

Emby, Jellyfin, WebDAV, local SAF, IPTV and link playback share one `PlayerManager`. Emby/Jellyfin
URLs retain `static=true`; no server-side audio-transcoding fallback is used.

## Android 11+ HTTP startup crash

The affected Android 12 ARM64 device recorded `SIGSEGV` / `SIGBUS` on `ff_read`, with
`av_application_will_http_open()` immediately below an invalid callback address. IJK stores its
`AVApplicationContext` and I/O manager pointers in decimal `AV_OPT_TYPE_INT64` dictionary options.
The old FFmpeg option parser evaluated those strings as `double`, which has only 53 bits of
integer precision. Android's tagged heap pointers need all 64 bits, so this changed the address
before the first HTTP callback. Local-file audio tests never entered that path.

`third_party/ijkplayer/ffmpeg-int64-options.patch` parses signed decimal integer literals with
`strtoll()` and writes the exact integer through FFmpeg's existing range checks. Expressions and
other option types keep their existing parser. Overflow is rejected. All four full native AARs
are rebuilt, and the patch/test hashes are recorded in `native-build.json`.

The device regression `third_party/ijkplayer/tests/int64_options_test.c` links the FFmpeg library
extracted from the shipped AAR. It covers dictionary transport, direct integer evaluation,
tagged pointer values, values beyond 53 bits, signed limits, overflow and bounded options. The
old ARM64 library changes pointer bits; the rebuilt library passes on the same device.

After rebuilding native dependencies, run it on a connected Android device with:

```powershell
node scripts/test-ijkplayer-int64.mjs --ndk "$env:ANDROID_NDK_HOME"
```

Android's [tagged-pointer documentation](https://source.android.com/docs/security/test/tagged-pointers)
explicitly describes pointer-to-double conversion as an invalid use. Keep heap pointer tagging
enabled; disabling it would conceal the parser defect.

## Video and audio configuration

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

The 2026-10-16 startup-crash fix passes all four tasks above: 29 JVM tests, 0 lint errors and
2 lint warnings. The packaged native-library digests, ELF alignment and APK alignment checks also
pass. The migration's earlier `:app:minifyReleaseWithR8` verification is documented below.

The Debug APK must contain `libijkffmpeg.so`, `libijkplayer.so`, and `libijksdl.so` for every shipped
ABI. The AC-3 and DTS device regressions require a selected matching stream, `avcodec` decoder,
native decoded-start and audio-rendering-start events, no playback error and advancing position.

`PlayerManagerPlaybackTest` additionally exercises the application singleton and its foreground
service with a real `IjkPlayerView` Surface. The original `decode_sample_*` resources contain only
audio and cannot establish that video initialization works. The `video_sample_*` resources add
four seconds of generated 320x180 H.264 Baseline colour bars and loop the original AC-3/DTS audio
without re-encoding it. This regression checks an unreadable source followed by repeated
prepare/reset/stop and Surface replacement, local files followed by HTTP playback through
`PlaybackHttpProxy`, advancing playback, a selected video/audio decoder, and actual coloured pixels
copied from the Surface with Android `PixelCopy`.

Run this video regression on the affected device with:

```text
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vibeplayer.app.player.PlayerManagerPlaybackTest
```

The affected HUAWEI TAS-AL00 (Android 12 / API 31, ARM64, native heap pointer tagging enabled)
passes all three instrumentation tests: AC-3 audio, DTS audio, and the local/HTTP video regression.
The native integer-option regression also passes in both ARM64 and ARMv7 modes on this device.
The old ARM64 library fails the same integer-option regression by changing the pointer bits.

Device validation used a temporary `.regression` Debug application ID suffix and the label
`VibePlayer 修复验证`. This isolates test data from the installed application. The Gradle device
runner could not resolve its uncached UTP host plugin in offline mode, so the built APKs were
installed with ADB and executed directly using the official instrumentation command:

```text
adb shell am instrument -w -r com.vibeplayer.app.regression.test/androidx.test.runner.AndroidJUnitRunner
```

The result was `OK (3 tests)`. The suffix and label override are local verification settings and
are not part of the application build configuration.

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

`./gradlew :app:connectedDebugAndroidTest` remains the normal device regression command when its
host dependencies are available. Building its APK alone does not establish audible output or
device rendering; use the device execution and pixel/decoder assertions above.

## Official references

- IJK Java API: <https://github.com/bilibili/ijkplayer/blob/k0.8.8/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java>
- IJK player options: <https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay_options.h>
- Android media buttons: <https://developer.android.com/media/legacy/media-buttons>
- Android network security: <https://developer.android.com/privacy-and-security/security-config>
- Android bitmap NDK API: <https://developer.android.com/ndk/reference/group/bitmap>
- Android audio focus: <https://developer.android.com/media/optimize/audio-focus>
