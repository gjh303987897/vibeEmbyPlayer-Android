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

## HTTP IPTV and WebDAV playback failures

User-configured media sources can use HTTP, including IPTV channels and LAN WebDAV servers.
The old network-security configuration allowed cleartext only for localhost and two emulator
addresses. OkHttp therefore rejected real HTTP upstream requests, and the loopback proxy hid the
cause behind HTTP 502 / `IJK_ERROR_-10000_0`. The application now opts into HTTP for arbitrary
user-configured hosts. HTTPS certificate and hostname verification remain enabled. The associated
`InsecureBaseConfiguration` lint warning is intentional; a static hostname allowlist cannot cover
user-imported IPTV playlists and their CDN hosts.

Switching sources exposed another failure: IJK's global DNS cache keys entries by hostname but
keeps the complete socket address, including the port. Every new playback proxy listens on a new
port, so a later source could connect to the preceding, already-closed proxy and fail with
`Connection refused` / `Hit dns cache but connect fail`. `PlayerManager` sets the supported
`dns_cache_clear=1` format option when opening HTTP sources to discard that obsolete entry.
This also covers the encrypted-HLS loopback input. See the pinned
[IJK TCP implementation](https://raw.githubusercontent.com/bilibili/FFmpeg/2902e33f6e59ed77ddc1b372586c1ab8db1fb096/libavformat/tcp.c).

Ordinary WebDAV playback now uses the same URL builder as listing/download requests. Decoded
filenames containing `#`, `?`, literal `%`, spaces or non-ASCII characters are encoded as path
segments rather than being interpreted as URL fragments or query parameters. The proxy also
extracts its request path with OkHttp's `HttpUrl`, matching the upstream URL builder. Using the
stricter `java.net.URI` constructor rejected otherwise valid filenames containing brackets, such
as `Perfect.Crown.S01E05[EZTVx.to].mkv`, before playback preparation reached IJK. A regression
reproduces that exception before the fix and checks range requests after the fix. Proxy transport
failures retain a sanitized category for localized authentication, missing-file, timeout and network
messages. Downstream socket cancellation during seeking is not recorded as an upstream connection
failure. Native error numbers remain in diagnostics instead of appearing as the explanation.

The regression inputs deliberately use `127.0.0.2`, outside the former localhost allowlist.
`PlayerManagerPlaybackTest` covers two consecutive HLS master/child/TS sessions, followed by
local and HTTP MP4/MKV sessions with AC-3/DTS and bracketed HTTP paths, replacing Surfaces and
checking rendered pixels.
`video_sample_hls.ts` remuxes `video_sample_ac3.mp4` with `ffmpeg -map 0 -c copy -f mpegts`.
JVM regressions cover WebDAV filename encoding and retained HTTP authentication/timeout errors.

The 2026-10-16 follow-up passes `assembleDebug`, `lint`, `testDebugUnitTest` and
`assembleDebugAndroidTest`: 35 JVM tests, 0 lint errors and 3 lint warnings (including the HTTP
configuration warning described above). The isolated verification app passes all four
instrumentation tests on the affected HUAWEI TAS-AL00, with result `OK (4 tests)` after the
bracketed-filename fix and test APK update. These fixtures
verify HTTP transport, source switching and rendered video; they do not establish that every
user-configured IPTV channel or WebDAV object is reachable or decodable.

Manual verification in the isolated app also played two multi-gigabyte MKV files from the
user-specified WebDAV directory, both with `[EZTVx.to]` in their filenames. The first produced
visible video and advancing playback, and seeking to approximately 12 minutes completed with
audio/video seek-rendering-start events. The next file played for over 40 seconds after switching.
The first CCTV1 entry in the imported IPTV playlist returned upstream HTTP 404 and showed the
localized missing-media explanation. The second CCTV1 entry rendered HEVC video and advanced for
over 45 seconds, with both native audio/video rendering-start events. The earlier CBOR array
message was not reproduced by these ordinary MKV files; their confirmed initialization failure
was the strict URI parser described above.

## Video and audio configuration

IJK's MediaCodec video path, auto rotation, resolution changes and timed-text decoding are enabled
explicitly. Audio decoding uses IJK's FFmpeg path. `PlayerManager` reads the public `MediaInfo`
audio-decoder evidence and selected stream metadata for the `AudioDecodeNotice` shown by each player
page. It does not infer successful decoding solely from a codec name.

The 2026-10-19 WebDAV investigation also identified a separate video-decoder gap: a 3840×2160
AV1 (`av01`) MP4 opened AAC audio and embedded subtitles, but the shipped FFmpeg 3.4 build had
no AV1 decoder and reported native video decoder `UNKNOWN`. IJK considered the session prepared
because its audio stream opened, producing sound and subtitles over a black video Surface.
The native recipe now statically links pinned dav1d 1.5.4 and backports FFmpeg's dav1d wrapper,
decoder registration and MOV `av1C` handling to the existing IJK/FFmpeg ABI. The wrapper preserves
packet timestamps, reference-counted pictures, drain behavior and sequence configuration after
flush/seek. AV1 uses client software decoding when IJK cannot select a hardware decoder, including
when the saved preference is hardware. Network routing and original-file playback remain unchanged.
AV1 regressions use a synthetic AV1/AAC/mov_text fixture
and require an active video decoder, advancing playback, captions and actual Surface pixels.

On the affected HUAWEI TAS-AL00 (Android 12), the user configured the `test` WebDAV service
directly in the independent verification app. Opening the authorized Queencard MP4 through
the normal browse/player UI produced visible 3840×2160 video, AAC audio-rendering events and
visible English captions, including after seeking. No server credentials were copied to the host.
A native on-device synthetic check also decoded colour frames before and after forward/backward
seeks with the rebuilt `libijkffmpeg.so`. This establishes decoding and actual UI rendering;
it does not promise real-time 4K software decoding on every device.

The 2026-10-19 device regressions passed: AV1/AAC/embedded captions and coloured Surface
pixels with both decoder preferences, AV1-to-H.264 source changes, software fallback followed
by hardware preparation (including superseded loading requests), delayed initial Surface,
paused Surface restoration with retained subtitles, and WebDAV TSSL seek cancellation.
The synthetic subtitle assertion trims FFmpeg's trailing CR/LF; the fixture contains fifteen
seconds of colour bars and a continuous caption. `assembleDebug`, `assembleDebugAndroidTest`,
all 95 JVM tests and `lint` passed. All twelve APK IJK library digests match the four rebuilt
ABI archives; ELF LOAD alignment is at least 16 KB and `zipalign -c -P 16 4` passes.

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
