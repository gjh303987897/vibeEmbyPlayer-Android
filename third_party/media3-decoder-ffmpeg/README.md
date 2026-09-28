# media3-decoder-ffmpeg (vendored)

Vendored copy of the **official** Media3 FFmpeg decoder extension
(`androidx/media` → `libraries/decoder_ffmpeg`), taken at upstream tag **`1.5.0`** — the same
version this app resolves `androidx.media3:*` at. Upstream publishes this module **only as
source**: it is deliberately absent from Google's Maven repository
(<https://github.com/google/ExoPlayer/issues/2781>), so it is checked in here instead of being
fetched as an AAR. All files keep their Apache-2.0 headers; see [`LICENSE`](LICENSE).

What it buys us: `FfmpegAudioRenderer` decodes audio with FFmpeg **on the device**, so AC-3,
E-AC-3, DTS / DTS-HD and TrueHD streams play with sound while still being streamed
`static=true` straight from the server (no transcoding, no server load).

## Layout

```
src/main/java/androidx/media3/decoder/ffmpeg/   upstream Java, verbatim
src/main/jni/CMakeLists.txt                     upstream JNI build, verbatim
src/main/jni/ffmpeg_jni.cc                      upstream JNI glue, verbatim
src/main/jni/build_ffmpeg.sh                    upstream FFmpeg build, verbatim
src/main/jniLibs/<abi>/libffmpegJNI.so          produced by scripts/build-ffmpeg-decoder.sh
src/main/jni/ffmpeg/                            OPTIONAL FFmpeg source + build tree (git-ignored)
consumer-proguard-rules.txt                     upstream rules, verbatim
consumer-rules-vibe.pro                         local keep rules for the reflective entry points
```

Package and class names are **not** renamed: `androidx.media3.exoplayer.DefaultRenderersFactory`
looks the renderer up by reflection under exactly that name when
`EXTENSION_RENDERER_MODE_ON`/`_PREFER` is set (see `audioRenderersFactory()` in
`app/src/main/java/com/vibeplayer/app/player/PlayerManager.kt`; the reflective contract itself is
pinned by `app/src/test/.../FfmpegExtensionWiringTest.kt`).

## Local deviations from upstream

1. `build.gradle.kts` replaces upstream `build.gradle` (Groovy + media3's internal
   `common_library_config.gradle`) with this repo's Gradle DSL, and the native build is gated
   by `-PffmpegNative=true` instead of merely by the presence of `src/main/jni/ffmpeg` - so a
   leftover FFmpeg checkout can never break a normal `./gradlew assembleDebug` on a machine
   without an NDK.
2. `ExperimentalFfmpegVideoRenderer.java` is **not** vendored. This project fixes audio only —
   HEVC/AV1 video stays on the hardware decoders, and FFmpeg software video decoding would burn
   battery for nothing. `DefaultRenderersFactory` simply skips the missing video class.
3. `src/test` is not vendored (needs Robolectric + media3 test-utils).
4. `minSdk` follows the app (26) instead of upstream's 21.
5. `consumer-rules-vibe.pro` is added next to upstream's `consumer-proguard-rules.txt`: Media3
   creates the renderer reflectively, so a minified release build needs explicit keep rules or
   the class is silently stripped (verified against R8 output; covered by
   `app/src/test/.../FfmpegExtensionWiringTest.kt`).

6. `proguard-rules.txt` is checked in as `consumer-proguard-rules.txt` (upstream renames it through
   its own `common_library_config.gradle`), so AGP forwards it to the app automatically.

Everything else is byte-identical to upstream `1.5.0`, so re-syncing is a plain overwrite. Check it
before and after a media3 bump:

```bash
curl -sS -H "Accept: application/vnd.github.raw" \
  "https://api.github.com/repos/androidx/media/contents/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java?ref=1.5.0" \
  | cmp - src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java && echo unchanged
```

## Producing `libffmpegJNI.so`

The Java half is useless without the native half; if the library cannot be loaded,
`FfmpegLibrary.isAvailable()` returns `false` and playback falls back to MediaCodec (i.e. the
previous "no sound" behaviour for exotic codecs — the player then shows the "audio format not
supported" notice). Build it either way:

* **`scripts/build-ffmpeg-decoder.sh` (recommended)**: clones FFmpeg `release/6.0` into
  `src/main/jni/ffmpeg`, runs upstream `build_ffmpeg.sh` once (it cross-compiles all four ABIs and
  installs the `.a` files into `src/main/jni/ffmpeg/android-libs/<abi>/`, which is where
  `src/main/jni/CMakeLists.txt` imports them from), then links `libffmpegJNI.so` per ABI with CMake
  + the NDK toolchain file and copies the results into `src/main/jniLibs/<abi>/`. Afterwards
  `./gradlew assembleDebug` packages them with no NDK involved. Needs the Android NDK (tested
  with r26b), GNU make and CMake >= 3.21 on Linux, macOS, WSL2 or Windows Git-Bash;
  `ABIS="arm64-v8a" API=26` narrows the run. Output size is about 1.7 MB per ABI (audio-only
  FFmpeg, stripped) - far below the "5-10 MB" usually quoted for a full FFmpeg build.
* **Gradle/CMake**: run step 1 of the script (the FFmpeg tree), then build with
  `-PffmpegNative=true -PffmpegAbis=arm64-v8a` and let `externalNativeBuild` compile the JNI
  layer. Do not combine it with libraries in `src/main/jniLibs/` - pick one source of the `.so`.
* **CI**: `.github/workflows/ffmpeg-decoder.yml` (manual trigger) does the scripted build on a
  Linux runner with a preinstalled NDK, verifies the APK really contains `libffmpegJNI.so`, and
  uploads `jniLibs` as an artifact.

Enabled decoders must cover the MIME types `FfmpegLibrary.getCodecName()` knows about:
`ac3`, `eac3`, `truehd`, `dca` (DTS and DTS-HD), plus the usual `mp3`, `aac`, `vorbis`, `opus`,
`flac`, `alac` as cheap fallbacks. The script passes that list to the upstream build.

## License note (FFmpeg is LGPL, this code is Apache-2.0)

The Java/C++ above is Apache-2.0, but the built `libffmpegJNI.so` statically links FFmpeg
(LGPL-2.1-or-later as configured — `--enable-gpl` is **not** used). Shipping the binary therefore
obliges us to: state that FFmpeg is used, keep its license text available, and make relocation
possible. That is satisfied by the build script + this checkout (anyone can rebuild a modified
`libffmpegJNI.so` from the sources and the FFmpeg tarball). See
[`docs/audio-software-decoding.md`](../../docs/audio-software-decoding.md) for the user-facing
notice and [`THIRD_PARTY_LICENSES.md`](../../THIRD_PARTY_LICENSES.md).
