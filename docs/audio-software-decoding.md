# 播放器音频：客户端软解码（Media3 FFmpeg 解码扩展）

## 问题

Emby / Jellyfin / WebDAV / 本地 / IPTV 的播放地址一律是 `static=true` 的**原始文件直连**，
服务器不做任何转码。手机硬件（MediaCodec）解不了的音轨就会被 Media3 静默丢弃：
**有画面、没声音、不报错**。常见触发格式：

| 音频格式 | Media3 MIME | 说明 |
| --- | --- | --- |
| Dolby Digital (AC-3) | `audio/ac3` | 无 Dolby 授权的机型没有硬解 |
| Dolby Digital Plus (E-AC-3 / Atmos) | `audio/eac3`、`audio/eac3-joc` | 同上 |
| Dolby TrueHD | `audio/true-hd` | 手机侧几乎都没有 |
| DTS / DTS-HD（使用 DTS 核心） | `audio/vnd.dts`、`audio/vnd.dts.hd` | FFmpeg 6.0 解码 DTS 核心；不承诺 DTS-HD 无损扩展 |

原先尝试过的方案是「按本机解码能力回退到服务器只转音频」，已被明确要求移除
（见 `fix.md` 2026-09-15 条目）：那会给服务器增加转码负载、依赖服务器装 ffmpeg，并且放弃直连。

## 方案：客户端解码，不是服务端转码

引入 Media3 官方的 **FFmpeg 解码扩展**（`FfmpegAudioRenderer`），在手机本机用 FFmpeg
解码上述格式。做法来自官方模块说明
`libraries/decoder_ffmpeg/README.md`（<https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md>）
与其指向的官方文档：支持格式 <https://developer.android.com/media/media3/exoplayer/supported-formats#ffmpeg-library>、
解码库加载/排查 <https://developer.android.com/media/media3/exoplayer/troubleshooting#how-can-i-get-a-decoding-library-to-load-and-be-used-for-playback>。

要点：

1. **仍是直连**：`static=true` 不变，服务器零负担；没装 ffmpeg 的服务器一样能用；弱网、
   多人共用服务器时体验最好；一次性覆盖所有来源（五个播放入口共用同一个 `ExoPlayer`）。
2. **只在硬件解不了时才用软解**：`PlayerManager` 使用
   `DefaultRenderersFactory.setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)`，
   即扩展渲染器排在 MediaCodec **之后**。硬解能做的仍由硬解做（省电），只有解不了的
   音轨落到 FFmpeg。（`EXTENSION_RENDERER_MODE_PREFER` 会把全部音频交给软解，无必要。）
3. **不用 libmpv / 不引跨平台播放器**：只是 Media3 的一个官方解码扩展，架构不变。
4. **只解决音频**：HEVC/AV1 等视频授权与硬解问题不在本方案范围内。

### 模块不在 Google Maven 上

「官方 GitHub Release 提供预编译 AAR/so」这一点与事实不符：Media3 的 FFmpeg/AV1/Libvpx
这类解码扩展**从未发布过 Maven 产物，也没有官方预编译二进制**
（上游 google/ExoPlayer#2781，README 亦明确 “The module is not provided via Google's Maven
repository”）。因此采用官方 README 推荐的做法——**以源码方式随项目分发该模块**：

- `third_party/media3-decoder-ffmpeg/`：`androidx/media` tag `1.5.0`（与依赖的
  `androidx.media3:*:1.5.0` 同版本）的 `libraries/decoder_ffmpeg` 副本，包名/类名不变
  （`DefaultRenderersFactory` 按 `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer`
  这个名字反射加载，见 `app/src/test/.../FfmpegExtensionWiringTest.kt` 的回归测试）。
- 原生库 `libffmpegJNI.so` 必须自行交叉编译：`scripts/build-ffmpeg-decoder.sh`
  （Linux/macOS + NDK + CMake），或在 CI 上跑 `.github/workflows/ffmpeg-decoder.yml`
  产出四个 ABI 的产物，落到 `src/main/jniLibs/<abi>/` 后由 AGP 直接打包
  （这样日常 `./gradlew assembleDebug` 不需要 NDK）。
- **缺少 `.so` 也不会崩**：`FfmpegLibrary.isAvailable()` 返回 false，渲染器声明不支持任何
  格式，行为回到方案实施前；此时界面会明确告诉用户「未包含客户端解码库，可能没有声音」。

代价：APK 每 ABI 增加约 4–8 MB；LGPL 合规要求见 `THIRD_PARTY_LICENSES.md`。软解为
2ch/多声道 PCM 输出（走 `AudioSink`），不做源码透传（bitstream passthrough），因此不会
把 DTS/Atmos 裸流送到外部功放——这是该扩展的固有限制。

## 用户提示（必做）

`ui/components/AudioDecodeNotice.kt`，显示在五个播放页顶栏标题下方（跟随控制条显隐，可关闭）：

- `AudioDecodeMode.SOFTWARE`：「本机不支持 <格式> 音轨，已启用客户端解码（应用内置 FFmpeg
  软解码）播放。」
- `AudioDecodeMode.UNSUPPORTED`（没有 `.so` 时才可能）：「本机不支持 <格式> 音轨，且当前安装
  包未包含客户端解码库，本片可能没有声音。」
- `AudioDecodeMode.HARDWARE`：不显示任何内容（绝大多数影片）。

格式名由 `audioCodecLabel()` 从 MIME 映射为可读文案（`DTS-HD Master Audio`、
`Dolby Digital Plus (E-AC-3)` …），字符串在 `values/strings.xml` 与 `values-zh/strings.xml`。

判定链路（`player/AudioDecodeSupport.kt`）：

1. `Tracks.Group.isTrackSupported()` —— Media3 自己的权威结论，只有它说不支持才报
   `UNSUPPORTED`（这正是静音场景）。
2. `MediaCodecList`（**只取可见注册表**，不含被系统隐藏的授权解码器）→ 判定是否硬解。
3. 反射调用 `FfmpegLibrary.isAvailable()` / `supportsFormat(mime)` → 判定是否客户端软解。
4. 三者都推不出结论时不打扰用户（返回 null）。

判定结果放在 `PlayerState.audioDecode`，由 `PlayerManager.updateTracks()/updateDerived()`
计算，五个 ViewModel 原样镜像到共用的 `PlayerUiState`，UI 层零业务逻辑。MediaCodec 注册表
与 FFmpeg 查询结果全部按进程缓存，`AudioDecodeSupport` 在播放器初始化时于
`Dispatchers.Default` 预热一次，避免在应用线程里做首次反射/扫描。

## 相关文件

| 文件 | 作用 |
| --- | --- |
| `third_party/media3-decoder-ffmpeg/**` | 官方 FFmpeg 解码扩展源码副本 |
| `scripts/build-ffmpeg-decoder.sh` | 交叉编译 `libffmpegJNI.so` → `jniLibs/` |
| `.github/workflows/ffmpeg-decoder.yml` | 手动触发的二进制构建（Linux runner + NDK） |
| `player/PlayerManager.kt` | 渲染器链（`EXTENSION_RENDERER_MODE_ON`）与判定输出 |
| `player/AudioDecodeSupport.kt` | 解码器可用性探测 + 纯函数判定 + 可读格式名 |
| `ui/components/AudioDecodeNotice.kt` | 顶栏一行提示 |
| `app/src/test/.../AudioDecodeSupportTest.kt` | 判定逻辑单测（无需真机/原生库） |
| `app/src/test/.../FfmpegExtensionWiringTest.kt` | 反射契约回归测试 |
| `THIRD_PARTY_LICENSES.md` | FFmpeg LGPL / Media3 Apache-2.0 说明 |

## 验证

```text
./gradlew :media3-decoder-ffmpeg:compileDebugJavaWithJavac
./gradlew testDebugUnitTest
./gradlew assembleDebug lintDebug
```

真机验证（需要包含 AC-3 / E-AC-3 / DTS / TrueHD 音轨的影片，以及一台无对应授权的机型）：

1. 未安装 `libffmpegJNI.so` 时播放：顶栏出现「未包含客户端解码库，可能没有声音」，
   `adb logcat -s AudioDecode` 打印 `FFmpeg audio decoder not usable in this build`。
2. 安装 FFmpeg 二进制后重装 APK 播放同一影片：**有声音**，顶栏提示改为「已启用客户端解码
   （应用内置 FFmpeg 软解码）播放」，logcat 出现 `Loaded FfmpegAudioRenderer.` 与
   `FFmpeg audio decoder available (version 6.0)`。
3. 同一影片若是 AAC 音轨：无任何提示（硬解路径），`adb shell dumpsys media.codec` 亦无新增
   FFmpeg 实例。
4. `logcat -s EmbyClient JellyfinClient` 确认请求地址仍带 `static=true`（无服务器转码）。
