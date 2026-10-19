# Third-party components and licenses

The playback backend uses the upstream IJKPlayer 0.8.8 Java API with native libraries rebuilt
from pinned source. Application dependencies are the Java AAR and the four `*-0.8.8-full.aar`
archives under `third_party/ijkplayer/`.

## Native playback components

| Component | Pinned source | License |
| --- | --- | --- |
| IJKPlayer | [`cced91e3`](https://github.com/bilibili/ijkplayer/tree/cced91e3ae3730f5c63f3605b00d25eafcf5b97b), tag `k0.8.8` | LGPL-2.1-or-later; Java/JNI files also carry Apache-2.0 notices |
| FFmpeg | [`2902e33f`](https://github.com/Bilibili/FFmpeg/tree/2902e33f6e59ed77ddc1b372586c1ab8db1fb096), IJK's FFmpeg 3.4 fork | LGPL-2.1-or-later; GPL/nonfree components disabled |
| dav1d | [`54706fc6`](https://code.videolan.org/videolan/dav1d/-/tree/54706fc6bc0cdecab7e9593974a4039cc038fca7), version 1.5.4 | BSD-2-Clause |
| libxml2 | [`02d57771`](https://github.com/GNOME/libxml2/tree/02d577715e1951cf114ca4e9adaf5605156c4f4c), version 2.12.10 | MIT |
| libyuv | [`a0c7dd3e`](https://github.com/Bilibili/libyuv/tree/a0c7dd3e4b095649881f39152655437b8cde1589) | BSD-3-Clause |
| SoundTouch | [`6bf39cd3`](https://github.com/Bilibili/soundtouch/tree/6bf39cd3bf6b0c156267d12446b0d6bdcfcd53c2) | LGPL-2.1-or-later |

The official ABI AARs disable several required decoders, subtitle formats and Matroska demuxing.
They are retained only as packaging inputs; Gradle ships the `-full.aar` derivatives, which replace
all three native libraries (`libijkffmpeg.so`, `libijkplayer.so`, `libijksdl.so`). The upstream Java
AAR is unmodified. Native libraries are linked for 16 KB page alignment with NDK r26b.

The source patches adapt the old native sources to current Clang/NDK, build the required formats,
and expose each stream's `disposition` and `title` to preserve subtitle defaults and track labels.
An additional subtitle patch and distributed support sources implement synchronized bitmap subtitle
snapshots, the application JNI bridge, premultiplied palette conversion and safe ASS text parsing.
DASH uses libxml2. Remote HTTPS uses the application's OkHttp loopback transport rather than a
native FFmpeg TLS backend. Audio remains client-decoded; Emby/Jellyfin streams retain `static=true`.

## Source, build and redistribution files

- `scripts/build-ijkplayer-native.mjs`: fetches exact source revisions and rebuilds all four ABIs.
- `third_party/ijkplayer/CMakeLists.txt`: builds the IJK player, SDL and supporting native sources.
- `third_party/ijkplayer/ffmpeg-ndk26.patch` and `ijkplayer-ndk26.patch`: distributed source changes.
- `third_party/ijkplayer/ffmpeg-int64-options.patch`: lossless integer option parsing for Android tagged native pointers.
- `third_party/ijkplayer/ffmpeg-dav1d.patch` and `libdav1d.c`: AV1 decoder registration, MOV configuration-box parsing and the FFmpeg n4.2 dav1d wrapper adapted to FFmpeg 3.4 / dav1d 1.5.x. The wrapper retains its LGPL notices; the MOV reader is backported from FFmpeg n4.4.
- `third_party/ijkplayer/tests/int64_options_test.c` and `scripts/test-ijkplayer-int64.mjs`: on-device regression against the packaged FFmpeg library.
- `third_party/ijkplayer/ijkplayer-bitmap-subtitles.patch`: playback-clock subtitle scheduling and JNI integration.
- `third_party/ijkplayer/bitmap_subtitles.{c,h}`, `bitmap_subtitle_pixels.{c,h}`: distributed subtitle bridge and pixel conversion sources.
- `third_party/ijkplayer/native-build.json`: source revisions, patch hashes, configuration and output hashes.
- `third_party/ijkplayer/ffmpeg-<abi>-config.h`: retained FFmpeg feature configuration for each ABI.
- `third_party/ijkplayer/NOTICE`, `COPYING.LGPLv2.1`, `COPYING.libxml2`, `COPYING.libyuv`,
  `COPYING.soundtouch`, `COPYING.dav1d`, `NOTICE.NDK.toolchain`, `NOTICE.cpufeatures`: upstream licensing notices.
- `docs/windows-android-build-environment.md`: prerequisites and build commands.

The complete corresponding sources can be obtained using the pinned repositories and distributed
patches. Retain the source recipe, source changes and license notices with native redistributions;
include the corresponding source and relinking materials required by the applicable LGPL terms.

## Other dependencies

Official AndroidX/Jetpack, Kotlin, OkHttp, Coil, Room, Hilt and related dependencies retain their
upstream licenses and notices. Android Framework `MediaSession` provides system playback controls.
Media3 ExoPlayer and the Media3 FFmpeg extension are no longer application dependencies.

## Patent notice

Codec support does not imply patent licensing. Dolby and DTS technologies may be patent-protected.
The application is intended for media content the user is legally authorized to play.
