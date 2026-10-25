# 倍速播放的音画同步与帧调度

2026-10-25，适用于共用 IJK 播放管理层的软解、硬解及其软件回退。

## 代码复核

Qt 的 `VIBEDOCS/PlayerRuntime.md` 和 `PlayerController::setSpeed` 规定由播放核心统一处理
倍速、校验有限值，并为普通视频设置平滑播放策略。Android 仍使用当前 IJK 核心；
具体实现以仓库内固定版本 `k0.8.8` 为准。

发现以下实际问题：

- SoundTouch 改变音频速度，但 `compute_target_delay` 和过期帧判断仍使用原始视频帧间隔；
  音视频时钟的插值速度也保持 1，视频只能依靠同步偏差触发成批追赶或等待。
- PCM 输出缓冲与 AudioTrack 延迟按正常速度从媒体时间扣除，SoundTouch 内部待处理样本
  也未计入，倍速越大时钟偏差越明显。
- SoundTouch 调用在 `swr_ctx` 分支内，原本已经符合目标 PCM 格式的音轨会绕过倍速。
  seek 和倍率切换时处理器仍持有上一时间线的音频。
- 上游变速包装器的多次 receive 始终覆盖同一输出首地址，却累计返回总字节数；
  输出没有显式容量边界。
- IJK 默认 `max-fps=31` 会限制高帧率素材，默认关闭的过期帧丢弃也不利于解码追赶。

## 修改

`ijkplayer-playback-timing.patch` 保持帧 PTS、seek 目标、字幕位置和进度为原始媒体时间。
渲染等待与过期判断除以当前倍率，音视频与外部时钟按倍率插值；倍率变化时重新计算视频
调度起点，渲染线程轮询间隔也跟随缩短后的帧等待，解除固定 10ms 轮询的限制。
诊断 A-V 差仍保留媒体秒单位。正常 1 倍下保留原有实时流外部时钟微调；
其他倍率使用所选速度，避免微调逻辑将其拉回 1 倍。

音频时钟扣除按输出倍率换算的 PCM/设备缓冲，以及 SoundTouch 仍持有的输入和输出样本。
新增 `playback_soundtouch.cpp` 在单声道/双声道重采样及直接 PCM 路径共用同一变速处理，
保持音高，以带容量的单次 receive 取出连续样本。仅 seek serial 变化时重建处理器，
清除旧队列及旧时间线计数；该固定版本的 `clear()` 没有重置输出计数。同一时间线切速
使用 `setTempo` 保留输入和输出队列，避免丢失尚未输出的音频。首次 1 倍可直接输出 PCM；
一旦本时间线启用过 SoundTouch，恢复 1 倍后仍通过处理器输出，直至下一次 seek，避免旁路
积压样本。

PlayerManager 设置 `max-fps=-1`、`framedrop=1`，保留素材帧率并按实际错过的截止时间
丢弃过期帧。拒绝 NaN/Infinity，倍率控制只有原生调用成功才更新状态；长按恢复使用同一入口。

四种 ABI 的 full AAR 均从固定源码重建，支持文件及补丁哈希记录在 `native-build.json`。
构建脚本先撤销最外层倍速补丁，再依次检查/应用旧补丁与新补丁，支持重复构建。

## 本地验证与限制

```text
node scripts/test-ijkplayer-playback-timing.mjs
node scripts/test-ijkplayer-subtitle-timing.mjs
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lint
```

倍速主机回归编译实际 IJK 时钟和帧等待函数：0.25/0.5/1/1.25/1.5/2/3/4 倍，
24/30/60fps，共 24,000 个帧截止时间，检查时钟斜率、切速无时钟跳变、暂停和延迟换算。
同一断言对原来的未缩放调度失败。另直接编译固定版本 SoundTouch 与生产适配器，检查
48kHz 合成正弦波的单声道/双声道输出时长、音高、缓冲边界、残留样本时钟与 seek 清理。
连续倍率回归在同一处理器上切换 48 段倍率，覆盖 `0.5→1`、`4→1` 和所有目标倍率，
分别使用 32 帧及 48,000 帧输出容量，逐块逐字节对照独立 SoundTouch 实例；切速时保留
已处理输出和未处理输入，最终排空后验证输出及 997Hz 尾标记，避免只验证每段独立变速。
原有字幕队列的提前/延后、seek serial 隔离及旧算法失败对照继续通过。

上述为主机验证。这些检查不能证明具体设备上的实际听感、
MediaCodec 输出节奏或高负载下的流畅度；后续需在硬解、软解分别验证连续切速、长按恢复、
倍速 seek、暂停恢复和高帧率视频。输出帧数仍受显示刷新率、设备解码能力与网络吞吐限制。
本地日志存放于忽略目录 `build/speed-playback/`。

已知限制：生产 EOF 路径尚未显式排空 SoundTouch，最后一批缓冲音频仍有截尾风险。
主机用例显式调用 `flush()` 验证适配器保存尾样本，不代表生产 EOF 已实现或验证排空。
生产修复需要同时处理 `frame_queue_peek_readable` 的阻塞等待、解码完成唤醒、
`read_thread` 的完成条件，以及当前 PCM / AudioTrack 缓冲排空；本次切速修复未扩展该流程。

本地结果：四 ABI 原生构建、debug APK 与 AndroidTest APK 构建、164 项 JVM 测试
（0 失败、0 跳过）及 lint 通过，lint 为 0 error、3 项既有 warning。
检查 APK 内 12 个原生库的 SHA-256 与构建清单一致、ABI 正确、ELF LOAD 段对齐至少
16KB，`zipalign -c -P 16 4` 通过。逐层应用补丁后的源码与构建源码文本一致
（比较时统一平台换行）。关键记录为 `host-test.log`、`subtitle-regression.log`、
`final-native-build.log`、`native-verification.log`、`patch-reproduction.log`、
`local-check.log` 与 `final-local-check.log`。

## 2026-10-25 实机补测

用户允许实测后，在华为 TAS-AL00 / Android 12（API 31）独立 `.regression` 包中验证。
测试前进一步修正同 serial 切速丢弃音频队列及回到 1x 绕过残留队列的问题；仅 seek serial
变化才重建处理器。四种 ABI 均完成编译、打包及哈希/对齐核对。

`PlaybackSpeedDeviceTest` 四项用例均通过，分别实际使用 MediaCodec 硬解和 CPU 软解，
配合 AAC 及 PCM S16LE 双声道音轨。60 秒 / 60fps / 320×180 的合成 H.264 视频在画面
顶部编码 16 位帧编号，并含 48kHz 的 440Hz 合成音频；素材由 FFmpeg 的 nullsrc/geq/sine
生成，PCM 版本复制视频、将音频解码为 PCM，均不包含用户视频或账号。

每个组合覆盖 0.5/1.25/1.5/2/3/4/1x，以及长按 4x 后恢复 1.25x、暂停中修改与恢复倍率、
seek 后继续播放。共 32 组测量、512 次系统合成截图：读取实际显示的帧编号，
对比原生媒体时钟推进速率，同时记录原生输出 FPS 与 A-V 差。实际时钟倍率的最大相对误差
约 1.83%；观测到的最大原生 A-V 时钟差约 96.8ms（墙钟单位），截图帧相对媒体时钟采样
区间的最大差约 134.5ms。所有检测窗口都持续推进画面，没有持续停留于旧位置。

IJK 的 A-V 差保留媒体秒，设备断言必须除以倍率后再与一致的墙钟容差比较。
首次软解 4x 的用例因使用固定媒体秒阈值失败，修正单位后完整四组复测通过；该调整也使
低倍速的检查相应收紧。截图与时钟对比没有录制或测量扬声器输出，不能等同于实际听感
或逐帧显示间隔的完整测量；原生输出 FPS 是提交统计，也不等同于超过显示刷新率的实际帧数。

原有 TSSL seek 遮挡、慢分片中断/暂停目标和双路输出时钟共 3 项设备回归也通过。
连同搜索页 2 项布局用例，本轮共 9 项不同的设备用例通过。生产 EOF 排空仍保留为上文的
已知限制，此次用例不覆盖尾音完整性。

正常包名的构建、164 项 JVM 测试和 lint 通过（0 error、3 项既有 warning）。
设备报告、最终截图和汇总位于忽略目录 `build/speed-search-device-20261025/`：
`final-device-results.log`、`remaining-device-results.log`、`device-summary.json`、
`speed-device-results/*.txt`、`host-speed-results.log`、`local-check.log`、`final-local-check.log`。

## 参考

- [IJK k0.8.8 播放时钟与调度源码](https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay.c)
- [IJK 配置项](https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay_options.h)
- [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
- Qt `VIBEDOCS/PlayerRuntime.md`、`src/player/PlayerController.cpp`。
