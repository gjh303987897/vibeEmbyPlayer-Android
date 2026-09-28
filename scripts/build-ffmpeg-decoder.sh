#!/usr/bin/env bash
#
# Builds libffmpegJNI.so for the vendored Media3 FFmpeg decoder extension
# (third_party/media3-decoder-ffmpeg) and drops the result into
# third_party/media3-decoder-ffmpeg/src/main/jniLibs/<abi>/.
#
# WHY THIS EXISTS
# ---------------
# androidx.media3:media3-decoder-ffmpeg is not published to Google's Maven repository
# (https://github.com/google/ExoPlayer/issues/2781); upstream ships only the Java glue plus
# src/main/jni/{ffmpeg_jni.cc,CMakeLists.txt,build_ffmpeg.sh}. FFmpeg itself has no Android
# build system, so the .a files and the final .so have to be cross-compiled by us. Any host
# with a POSIX shell + GNU make + CMake works: Linux, macOS, WSL2, and Windows through Git-Bash
# (verified: NDK r26b ships bin/aarch64-linux-android26-clang as a bash wrapper, which is what
# FFmpeg's --cross-prefix wants). A checkout without those tools is why this script and the
# ffmpeg-decoder workflow exist at all, and why the result is committed as jniLibs.
#
# WHAT YOU GET
# ------------
# Software decoders for AC-3, E-AC3 (incl. EAC3-JOC/Atmos), TrueHD, DTS, DTS-HD and DTS Express,
# so those tracks play through the existing static=true direct stream: no server transcode, no
# server load. PlayerManager enables the renderer with EXTENSION_RENDERER_MODE_ON, meaning
# MediaCodec still wins for every format the device can handle natively.
#
# WHAT IS DELIBERATELY NOT HERE
# -----------------------------
# * Video decoders. The extension also has ExperimentalFfmpegVideoRenderer upstream; we do not
#   vendor it. HEVC/AV1 should stay on the hardware path - FFmpeg video decoding costs battery
#   and the reported problem is audio only.
# * Anything GPL. FFmpeg is configured LGPL-2.1-or-later (--enable-gpl is never passed). Do not
#   add --enable-gpl, x264/x265 or any other GPL component: the app would have to be relicensed.
#   See THIRD_PARTY_LICENSES.md.
#
# USAGE
# -----
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-ffmpeg-decoder.sh
#   ABIS="arm64-v8a" API=26 ./scripts/build-ffmpeg-decoder.sh   # only what you ship
#
# Then rebuild normally; app/build.gradle.kts needs no changes:
#   ./gradlew :app:assembleDebug
#
set -euo pipefail

MODULE_DIR="third_party/media3-decoder-ffmpeg"
JNI_DIR="$MODULE_DIR/src/main/jni"
FFMPEG_DIR="$JNI_DIR/ffmpeg"
OUT_DIR="$MODULE_DIR/src/main/jniLibs"

# The 6.0 branch is what upstream's decoder_ffmpeg README pins.
FFMPEG_SOURCE="https://github.com/FFmpeg/FFmpeg"
FFMPEG_BRANCH="release/6.0"
# Fallback when git-over-HTTPS to github.com is blocked/reset (a plain working tree is enough:
# upstream's build_ffmpeg.sh never runs git, it only configures/makes in jni/ffmpeg).
FFMPEG_TARBALL="https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/heads/release/6.0"

API="${API:-$(grep -oE 'minSdk[[:space:]]*=[[:space:]]*[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)}"
# Upstream build_ffmpeg.sh cross-compiles all four of these in one pass; only the ABIs listed
# here are linked into libffmpegJNI.so and copied out.
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

# Exactly the decoder names FfmpegLibrary.getCodecName() can ask for (verified against
# libavcodec/allcodecs.c of release/6.0), plus mp1/mp2 because media3 maps
# audio/mpeg-L1/L2 to the "mp3" decoder name and those streams are Layer I/II in practice.
# NOTE: --disable-everything means ONLY these decoders exist, exactly as upstream configures it,
# and a missing name is logged by FfmpegLibrary as "No <name> decoder available". There is no
# "dts" decoder in FFmpeg - DTS and DTS-HD are both "dca".
DECODERS=(
  truehd dca eac3 ac3
  aac mp1 mp2 mp3 flac alac vorbis opus amrnb amrwb pcm_mulaw pcm_alaw
)

die() { echo "error: $*" >&2; exit 1; }

[ -d "$MODULE_DIR" ] || cd "$(dirname "$0")/.."
[ -d "$MODULE_DIR" ] || die "run this from the repository root"
command -v cmake >/dev/null || die "cmake (>= 3.21) is required - the SDK ships one in \$ANDROID_HOME/cmake/<ver>/bin"
command -v make >/dev/null || die "GNU make is required (upstream's build_ffmpeg.sh calls make)"
command -v curl >/dev/null || die "curl is required as the fallback way to fetch the FFmpeg sources"
if ! command -v git >/dev/null && [ ! -f "$FFMPEG_DIR/configure" ]; then
  die "git (or a pre-populated $FFMPEG_DIR) is required to get the FFmpeg sources"
fi
case "$(uname -s)" in
  Linux) HOST_PLATFORM=linux-x86_64 ;;
  Darwin) HOST_PLATFORM=darwin-x86_64 ;;
  # The NDK ships POSIX wrapper scripts for Windows too (bin/aarch64-linux-android26-clang is a
  # bash script that execs clang.exe --target=...), so Git-Bash/MSYS works as long as make is
  # GNU make. Verified with NDK r26b + GNU Make 4.4.1 (Windows) + git-bash.
  MINGW*|MSYS*|CYGWIN*) HOST_PLATFORM=windows-x86_64 ;;
  *) die "unsupported host $(uname -s): use Linux/macOS (or WSL2/Git-Bash) with the Android NDK, or run the ffmpeg-decoder workflow" ;;
esac

# The SDK's bundled CMake also provides ninja, which CMake needs for the link step.
for cand in "${ANDROID_HOME:-}/cmake"/*/bin "${ANDROID_SDK_ROOT:-}/cmake"/*/bin "$HOME"/Android/Sdk/cmake/*/bin; do
  [ -x "$cand/cmake" ] || [ -x "$cand/cmake.exe" ] || continue
  case ":$PATH:" in *":$cand:"*) ;; *) PATH="$PATH:$cand" ;; esac
done
export PATH

NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK_PATH" ]; then
  NDK_PATH="$(ls -d "$HOME"/Android/Sdk/ndk/* "$LOCALAPPDATA"/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$NDK_PATH" ] || die "set ANDROID_NDK_HOME to an Android NDK (upstream tested r26b)"
fi
[ -d "$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM" ] ||
  die "$NDK_PATH has no prebuilt toolchain for $HOST_PLATFORM"
echo "NDK: $NDK_PATH ($("$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin/clang" --version | head -1))"
echo "API: android-$API   ABIS: $ABIS"
TOOLCHAIN_PREFIX="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin"

# ----------------------------------------------------------------------------------------------
# 1. FFmpeg sources + the cross-compiled static libraries.
#
# Upstream's build_ffmpeg.sh is the only supported way to get these: it configures FFmpeg once
# per ABI with --disable-everything plus one --enable-decoder per entry of DECODERS, runs
# `make install-libs`, and leaves the .a files in src/main/jni/ffmpeg/android-libs/<abi>/ (the
# exact directory src/main/jni/CMakeLists.txt imports from).
# It takes the *module* path and cd's into <module>/jni/ffmpeg itself, hence "$MODULE_DIR/src/main".
# ----------------------------------------------------------------------------------------------
step() { printf '\n==> %s\n' "$*"; }

step "1/3 FFmpeg $FFMPEG_BRANCH sources"
if [ ! -f "$FFMPEG_DIR/configure" ]; then
  mkdir -p "$JNI_DIR"
  rm -rf "$FFMPEG_DIR"
  if ! git clone --depth 1 --single-branch --branch "$FFMPEG_BRANCH" "$FFMPEG_SOURCE" "$FFMPEG_DIR"; then
    echo "git clone failed - falling back to the $FFMPEG_BRANCH tarball"
    rm -rf "$FFMPEG_DIR"
    curl -fL --retry 5 -o "$JNI_DIR/ffmpeg-src.tar.gz" "$FFMPEG_TARBALL"
    mkdir -p "$FFMPEG_DIR"
    tar -xzf "$JNI_DIR/ffmpeg-src.tar.gz" -C "$FFMPEG_DIR" --strip-components=1
    rm -f "$JNI_DIR/ffmpeg-src.tar.gz"
  fi
fi
[ -f "$FFMPEG_DIR/configure" ] || die "no FFmpeg sources in $FFMPEG_DIR"

step "2/3 FFmpeg static libraries (all four ABIs, this is the slow part)"
# Expected `llvm-readelf -h` Machine per ABI; used to prove the installed archives really belong
# to that ABI (see ffmpeg_libs_ok below).
expected_machine() {
  case "$1" in
    armeabi-v7a) echo "ARM" ;;
    arm64-v8a) echo "AArch64" ;;
    x86) echo "Intel 80386" ;;
    x86_64) echo "Advanced Micro Devices X86-64" ;;
    *) die "unknown ABI $1" ;;
  esac
}

# True only when all three archives exist for $1 AND their objects have the right Machine.
ffmpeg_libs_ok() {
  local abi="$1" f machine
  for f in libavcodec libavutil libswresample; do
    [ -f "$FFMPEG_DIR/android-libs/$abi/$f.a" ] || return 1
  done
  machine="$("$TOOLCHAIN_PREFIX/llvm-readelf" -h "$FFMPEG_DIR/android-libs/$abi/libavutil.a" 2>/dev/null | sed -n 's/.*Machine:[[:space:]]*//p' | head -1)"
  [ "$machine" = "$(expected_machine "$abi")" ]
}

# Idempotency guard: skip when every requested ABI has correctly-archived libraries already.
need_ffmpeg=0
for abi in $ABIS; do
  ffmpeg_libs_ok "$abi" || need_ffmpeg=1
done
if [ "$need_ffmpeg" = 1 ]; then
  # Upstream's script configures/builds the four ABIs in one tree. Clear any leftover objects
  # first: a stale tree (e.g. a previous single-ABI build) can otherwise be re-archived into the
  # *first* ABI's android-libs directory and link as "incompatible with armelf_linux_eabi".
  ( cd "$FFMPEG_DIR" && make clean > /dev/null 2>&1 || true )
  ( cd "$MODULE_DIR/src/main" && bash jni/build_ffmpeg.sh "$PWD" "$NDK_PATH" "$HOST_PLATFORM" "$API" "${DECODERS[@]}" )
else
  echo "android-libs already correct for: $ABIS - skipping (rm -rf $FFMPEG_DIR/android-libs to force)"
fi
# Never trust, always verify: a wrong-architecture archive is a link error several minutes later.
for abi in $ABIS; do
  ffmpeg_libs_ok "$abi" ||
    die "android-libs/$abi does not contain $(expected_machine "$abi") objects - the FFmpeg build went wrong, delete $FFMPEG_DIR/android-libs and re-run"
done

# ----------------------------------------------------------------------------------------------
# 3. Link libffmpegJNI.so per ABI with the upstream CMakeLists.txt, copy into jniLibs.
# ----------------------------------------------------------------------------------------------
step "3/3 libffmpegJNI.so for: $ABIS"
# Prefer Ninja (CMake's most reliable generator on Windows hosts); fall back to Unix Makefiles.
if command -v ninja >/dev/null; then GEN="Ninja"; else GEN="Unix Makefiles"; fi
echo "CMake generator: $GEN"
mkdir -p "$OUT_DIR"
for abi in $ABIS; do
  [ -f "$FFMPEG_DIR/android-libs/$abi/libavcodec.a" ] ||
    die "no FFmpeg static libs for $abi in $FFMPEG_DIR/android-libs/$abi - re-run step 2 or drop $abi from ABIS"
  build_dir="$JNI_DIR/build-ffmpeg-$abi"
  rm -rf "$build_dir"
  cmake -S "$JNI_DIR" -B "$build_dir" \
    -G "$GEN" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo 2>&1 | tail -3
  # Do not hide compiler/linker output: a swallowed failure here is why this script used to
  # silently produce nothing for the later ABIs.
  cmake --build "$build_dir" --target ffmpegJNI -j "$(nproc 2>/dev/null || sysctl -n hw.ncpu)" 2>&1 | tail -5

  so="$(find "$build_dir" -name 'libffmpegJNI.so' -print -quit)"
  [ -n "$so" ] || die "ffmpegJNI target produced no .so for $abi (see $build_dir)"
  machine="$("$TOOLCHAIN_PREFIX/llvm-readelf" -h "$so" | sed -n 's/.*Machine:[[:space:]]*//p' | head -1)"
  [ "$machine" = "$(expected_machine "$abi")" ] ||
    die "$abi library has Machine '$machine', expected '$(expected_machine "$abi")'"
  mkdir -p "$OUT_DIR/$abi"
  cp "$so" "$OUT_DIR/$abi/libffmpegJNI.so"
  "$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin/llvm-strip" --strip-unneeded \
    "$OUT_DIR/$abi/libffmpegJNI.so" 2>/dev/null || true
  ls -l "$OUT_DIR/$abi/libffmpegJNI.so"
done

cat <<EOF

Done. libffmpegJNI.so for [$ABIS] is in $OUT_DIR.

Next steps:
  1. ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
  2. Play something with a DTS / TrueHD track, still static=true (the app never asks for a transcode).
     Expect sound plus the on-screen "已启用客户端解码（应用内置 FFmpeg 软解码）播放" notice.
     Without these binaries the player instead says "系统和应用内置解码器都无法解码，可能没有声音".
  3. Commit the binaries if this build should be reproducible without a local NDK, otherwise let
     .github/workflows/ffmpeg-decoder.yml produce them for release packaging.
EOF
