# 第三方组件与许可

VibePlayer（Android）本体以项目仓库所选许可证发布。除官方 AndroidX / Jetpack / Media3 /
Kotlin / OkHttp / Coil / Room 等依赖（Apache-2.0 或 BSD）之外，音频客户端解码会引入下列
第三方原生组件，分发包含它们的安装包时必须一并说明。

## FFmpeg

- 用途：`third_party/media3-decoder-ffmpeg`（Media3 官方 FFmpeg 解码扩展的本地副本）在
  **客户端本机**解码 AC-3 / E-AC-3 / DTS 核心 / TrueHD 等手机硬件不支持的音轨，
  从而修复「有画面没声音」，同时保持 `static=true` 直连、服务器零转码。
- 版本：FFmpeg `release/6.0`（由 `scripts/build-ffmpeg-decoder.sh` 克隆后交叉编译）。
- 许可证：**LGPL-2.1-or-later**（构建时未使用 `--enable-gpl`，也未启用任何 GPL 组件、
  nonfree 组件或外部专利编解码器库）。
- 上游：<https://ffmpeg.org/>
- 许可原文：<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt>

### LGPL 义务与本项目做法

1. **声明使用**：本文档 + 播放页在真正使用客户端解码时的用户提示。
2. **可重新链接（relocation）**：FFmpeg 以静态库形式链入 `libffmpegJNI.so`。为保留用户
   自行替换/重链接的自由，本仓库提供完整可复现的构建配方：
   - `scripts/build-ffmpeg-decoder.sh`（唯一的构建入口，参数化 ABI 与启用的解码器）；
   - `third_party/media3-decoder-ffmpeg`（Apache-2.0 的 JNI 包装源码，随仓库分发）；
   - `.github/workflows/ffmpeg-decoder.yml`（可一键复现上述产物）。
   分发二进制（APK / AAB）的一方应同时提供该脚本与所用 FFmpeg 源码对应方式（本文档即为
   该「书面要约」）。若更倾向严格做法，可改用 `--enable-shared` 输出 `libffmpeg.so` 后
   动态链接（`third_party/media3-decoder-ffmpeg/src/main/jni/build_ffmpeg.sh` 的
   `COMMON_OPTIONS` 即为此预留）。
3. **许可证文本**：随包分发 LGPL-2.1 与 Apache-2.0（`third_party/media3-decoder-ffmpeg/LICENSE`）
   文本，或在应用内「关于 / 开源许可」页面提供同等内容。
4. **不升级许可证**：不启用 `--enable-gpl`、`--enable-nonfree`、`libx264`、`libfdk-aac`
   等选项，因此产物不受 GPL 传染，也不会声称拥有 H.264/AAC 等编码的专利授权。

## Media3（ExoPlayer）FFmpeg 解码扩展

- 用途：`FfmpegAudioRenderer` / `FfmpegAudioDecoder` / `FfmpegLibrary`。
- 来源：`androidx/media`（`libraries/decoder_ffmpeg`）tag `1.5.0`，与项目依赖的
  `androidx.media3:*:1.5.0` 同版本；因其**不在 Google Maven 发布**（上游 issue #2781），
  按官方 README 的做法以源码方式随项目分发。
- 许可证：Apache-2.0，原文见 `third_party/media3-decoder-ffmpeg/LICENSE`，文件头保留原版权与
  许可声明。

## 专利提示

硬件或软件「能解码」不等于「有专利授权」。Dolby（AC-3 / E-AC-3 / TrueHD）与 DTS 相关技术
可能受专利约束；本项目的客户端解码仅用于用户自身拥有合法授权的媒体内容，且分发包中启用的
解码器清单（见 `scripts/build-ffmpeg-decoder.sh`）在法务需要时可按需裁剪。
