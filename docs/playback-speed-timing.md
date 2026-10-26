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

## 2026-10-26 输出时间戳与排队倍率

用户反馈在 Emby 连续倍速时画面领先声音，切回 1x 后才逐渐恢复，手机扬声器与蓝牙均会
出现。已核对手机验证包的原生库确实包含上一轮修复，继续复核 AudioTrack 输出路径。

旧路径使用固定的 `2 × AudioTrack 缓冲大小 / PCM 字节率` 估算设备延迟，没有读取实际
播放位置；倍速切换还会立即将音频时钟斜率设为新倍率，而设备可能仍在播放旧倍率的 PCM。
另一个问题是回调内解码和 IO 可能阻塞，旧代码仍使用进入回调前的时间给测得的音频位置
打点。上述问题都发生在共用音频输出层，与硬解或软解视频的选择无关。

新增 `ijkplayer-audio-presentation.patch` 与 `audio_presentation_*` 支持文件：

- 使用公开的 `AudioTrack.getTimestamp(AudioTimestamp)`，以单调时钟推算当前输出帧。
  为每段已经交给输出设备的 PCM 记录媒体位置、对应倍率和 seek serial，按实际呈现的
  输出帧反查媒体时间；新倍率不会覆盖仍排队的旧倍率区间。
- 只累计成功写入 AudioTrack 的帧；处理短写、暂停、恢复、seek、设备切换和缓冲耗尽。
  时间戳启动时快速采样，稳定后降低轮询频率，切换路由后重新采样。无有效时间戳的设备
  保留旧时钟回退，不因设备不支持此 API 而中断播放。
- 原生 seek 清理时先暂停 AudioTrack 再 flush，确保系统确实清空数据，再重置对应的
  帧计数和时间线。若 seek 在解码回调中发生，丢弃该次过时的 PCM，不写回旧时间线。
- 在测量完成时给音频时钟打点。保留原始帧 PTS、字幕时间、播放进度与 seek 坐标。
  诊断属性 `10901` 表示是否实际采用输出时间戳，`10902` 是媒体秒单位的输出积压。
- 硬解路径配置源帧率及实时优先级，并在 API 23+ 随倍率更新公开的 MediaCodec
  `operating-rate` 资源提示。更新在解码输出线程执行，复用其现有锁边界，不从输入线程
  与阻塞 dequeue 循环竞争锁；不支持提示时保留原播放路径，也不修改源帧时间戳。

构建脚本在原倍速补丁外应用此补丁，重复构建时按相反顺序撤销。四种 ABI 重新编译；
补丁、支持文件、主机回归与库哈希均记录在 `native-build.json`。

新增主机回归：

```text
node scripts/test-ijkplayer-audio-presentation.mjs
```

覆盖 40–1000ms 的模拟设备延迟、1x→2x→1x 队列混合、倍率分界、seek serial 隔离、
启动预缓冲、缓冲耗尽、64 位帧计数与有界历史淘汰，并对照旧时钟在混合倍率队列下的偏差。
原倍速/SoundTouch 与字幕主机回归继续通过。新增可选的 `ConfiguredEmbySpeedDeviceTest`
仅在明确传入服务和剧集名称时运行，使用同一验证应用中已有的会话，账号和令牌不出应用。
它不向服务端上报播放进度，并在结束时停止播放、恢复原解码偏好。

本地检查结果：四 ABI 原生库、正常及独立包 APK/AndroidTest APK 构建通过；170 项 JVM
测试 0 失败、0 跳过，lint 0 error、3 项既有 warning。12 个原生库与清单哈希一致，
ABI、16KB ELF 对齐及 APK zipalign 通过；逐层应用五个 IJK 补丁可重现构建源码。
记录位于忽略目录 `build/speed-sync-20261026/`。

实机原片使用已授权服务中名称完全匹配的剧集，测得源为 HEVC Main 10、3840×2160、
60fps、AAC 44.1kHz 双声道。输出设备确认为手机扬声器。硬解 1x 与 1.5x 的原生时钟差
约 40ms 以内，全部采样使用真实输出时间戳；2x 下视频解码约 105fps，未达到所需的
120fps，视频逐渐落后音频，约 11 秒测量窗口的最大墙钟偏差达到 1.37 秒，切回 1x 后
仍需追赶。该原片的 CPU 软解约 15fps，1x 已无法实时处理。静音测试观测到的这一
性能不足方向与用户描述的画面领先声音不同，不能据此断言原始听感问题已经解决。

资源提示在本机上未消除 4K60 的 2x 累积偏差。固定增加连续丢帧的实验也未提高解码吞吐，
已从最终源码撤回。

用户随后明确选择：送屏帧率跟随当前手机屏幕刷新率；仍然解码不足时允许自动降低实际
倍速，并告知用户。实现如下：

- `IjkPlayerView` 读取当前 View 所在 Display 的刷新率，监听 Display 变化，并经共用
  PlayerManager 更新原生送屏节奏。没有 View 时先使用默认 Display 的有效刷新率。
  原生显示按墙钟网格限制提交次数，源帧率、PTS 和进度不变，暂停和 seek 的首帧仍可
  立即显示。主机回归覆盖 30–144Hz 及高频输入。
- `PlaybackSpeedGovernor` 观察解码吞吐、实际送屏吞吐与有符号音画差。启动/切速/seek 后
  先等待 2.5 秒，连续四次采样确认视频落后超过 120ms 且处理不足，才选择留有余量的
  较低倍率。送屏目标取源帧率乘倍速与当前刷新率的较小值，避免把正常的刷新率限制
  当作性能不足；原始解码足够而颜色转换/送屏不足时，也会继续保护。
  加载、暂停、seek 遮挡、未知数据与画面领先声音均不触发该性能回退。
- 降速后沿用原 seek 管线重新对齐两路输出；新实际倍速同步到控件，五类来源的播放页均
  显示可关闭的 7 秒提示。用户手动选速会重新尝试；下一视频恢复用户选择的倍率。
  该限制不写入全局偏好。手动选速的原范围保留；自动性能保护可用 0.2/0.125/0.1x
  低速档。原生校验与 PCM 容量边界相应支持 0.1x，乘法溢出检查保留。
- 低于 0.25x 的进入、退出或切换通过 seek 重建该处理时间线。固定版本 SoundTouch
  在这些低倍率与普通倍率的短段连续混合试验中，最终时长与逐段期望不一致；生产路径
  使用已验证的重置边界，不把未通过的低倍率连续混播当作可用行为。普通倍率原有 48 段
  连续队列保留回归保持不变。独立处理/清理回归扩展至 11 档、33,000 个帧截止时间。

更新后的原片硬解实测通过：Display 约 60Hz；1x 与 1.5x 分别按所选速度推进；选择 2x
后因持续解码不足自动降至 1.5x，最终一轮重新对齐后的最大原生时钟差约 35ms；切回 1x
最大差约 5ms。采样窗口全部采用实际音频时间戳，原生送屏统计约 59–61fps（毫秒采样取整）。
这表示能力不足时以较低实际倍率保持同步，并非本机已经能实时解码这份 4K60 的 2x。
同一原片的 CPU 软解也完成实测：0.25/0.2x 仍受完整画面处理吞吐限制；最终保护会
继续降低至 0.1x，实测速率约 0.09994，送屏约 6fps，稳定窗口最大原生时钟差约 1.2ms，
首尾采样中位数差约 0.85ms，没有持续漂移。如此低的实际倍率明确显示在控件与提示中。
极低帧率的测试容差按最多一帧加 40ms 计算，并额外检查首尾窗口漂移；本次实际误差
仍远低于原有 150ms 容差，没有依赖放宽容差通过。
四组软硬解 AAC/PCM 合成素材实测也通过，覆盖到 4x、暂停、长按恢复和 seek。原先一次
帧编号采样失败的截图显示系统正在旋转动画中；测试改为固定方向后完整重跑通过。
最终扬声器回归共 9 项通过：原片硬解、原片软解、四组常规倍速，以及三项 TSSL seek
遮挡、慢分片中断与两路输出恢复检查。2026-10-27 用户选择本次先完成扬声器验证，
蓝牙物理输出尚未实测，不将主机模拟延迟检查当作蓝牙实测结果。

实机测试遵守用户要求保持静音，不录制音频；时间戳、原生 A-V 时钟和截图只能验证输出
时序及画面推进，不能代替用户对实际听感的复核。生产 EOF 排空仍保留上文的已知限制。

## 参考

- [IJK k0.8.8 播放时钟与调度源码](https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay.c)
- [IJK 配置项](https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay_options.h)
- [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
- [AudioTrack 输出时间戳](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp))
- [AudioTrack flush 的暂停要求](https://developer.android.com/reference/android/media/AudioTrack#flush())
- [MediaCodec operating-rate 资源提示](https://developer.android.com/reference/android/media/MediaFormat#KEY_OPERATING_RATE)
- [Display 当前刷新率](https://developer.android.com/reference/android/view/Display#getRefreshRate())
- Qt `VIBEDOCS/PlayerRuntime.md`、`src/player/PlayerController.cpp`。
