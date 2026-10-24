# TSSL v4 / M3U8SP Android 支持

Android 端现已读取 Qt 桌面端定义的 TSSL v4 和 `.m3u8sp` 单文件容器，同时保留 TSSL v2/v3 `.m3u8s` 目录格式兼容。

## 格式

TSSL v4 在 v3 字段之外强制包含：

- `containerFormat = m3u8sp-tar-index-v1`
- `containerIndexSha256`：首个 TAR 成员 `.vibe/index.cbor` 的原始 CBOR 字节摘要
- `containerLength`：整个容器长度

`.m3u8sp` 是未压缩 POSIX/PAX TAR。首成员必须是 `.vibe/index.cbor`，CBOR index version 为 1，记录 manifest 路径及每个成员的 TAR header offset、data offset、长度与 SHA-256。

Qt 使用 `QByteArray::toHex()` 写入每个成员的 `sha256`，因此该字段在 CBOR 中是包含 64 个 ASCII 十六进制字符的 byte string。Android 写入时保持相同编码；读取时也兼容早期 Android 版本曾写出的 CBOR byte array，避免已有移动端容器失效。

## Android 播放流程

1. 本地 SAF 通过可定位文件描述符按范围读取；WebDAV 必须返回 `206 Partial Content` 和匹配的 `Content-Range`，返回整个对象的 `200` 会拒绝。
2. 只读取首个 512 字节 TAR header，从中解析 CBOR index 的长度，再用一次精确范围请求读取 index 本体（不再预读 16 MiB 前缀）；随后校验安全相对路径、重复、偏移、长度、摘要与容器边界。
3. 从 index 定位根 manifest，按成员 header 与 SHA-256 校验。
4. 用 root manifest digest 选择本机 TSSL（与桌面端一致），identifier 作为交叉校验；本机存在多个包时不会被相邻包顶替。
5. 同时匹配 index digest、container length 和 container format。
6. 后续 TS/playlist/resource 按 index 随机读取并校验；TS 继续在 AES-256-GCM tag 验证后才交给 IJK。

播放器进入页面就显示加载状态，覆盖 WebDAV 身份读取、容器索引、manifest 和本机 TSSL 匹配阶段；准备失败时结束加载并显示错误。

WebDAV 加密播放的 seek 会立即静音旧输出并保留目标进度，先向 IJK 提交 seek，再关闭旧环回响应并取消对应的 OkHttp 请求（包括正在读取的响应体），使 demuxer 无需等旧分片下载完成。播放中的 seek 不再额外暂停原生管线，避免进入 IJK 的暂停单帧定位路径；用户原本暂停时仍保留暂停意图。连续拖动合并到最后一个目标；只有当前目标完成后才按用户最新的播放/暂停意图恢复。取消请求不会跳过 SHA-256、ETag、Content-Range 或 AES-GCM 校验。停止代理也会关闭已接收的连接并取消其远程读取。

### Seek 后的音视频恢复

IJK 的 `OnSeekComplete` 只表示解封装器完成定位，不能作为声音和画面已经恢复的依据。
TSSL seek 现在分别等待当前音轨的 `MEDIA_INFO_AUDIO_SEEK_RENDERING_START` 和视频的
`MEDIA_INFO_VIDEO_SEEK_RENDERING_START`。两路实际输出都就绪后才恢复音量、结束加载并
向界面报告播放中；在此之前保留目标进度，让 IJK 在静音状态下完成输出恢复。无音轨的视频
或纯音频不会等待不存在的流。定位确认之前的旧输出事件不能放行新目标。

暂停时不为了等待音频而自动播放；定位完成后保持暂停，用户恢复播放时再等待所需输出。
连续 seek 为新目标重新建立就绪条件；停止、播放完成、切换来源和播放器 reset/release 都会
清除旧条件。等待期间更换音轨会重新定位，避免继续等待旧音轨的队列事件。

### Seek 加载期间的画面

`.m3u8sp` / `.m3u8s` 跳转立即设置 `videoOutputBlocked`：WebDAV 与本地播放页用不透明
遮挡层盖住原生 Surface 上的旧画面和位图字幕，并隐藏文字字幕，显示现有加载提示。
进度始终保留在最新目标，连续拖动不会在中途露出旧片段。

播放状态下，两路输出都就绪后才撤掉遮挡并恢复声音；暂停状态下，目标视频帧到达即可显示
新位置的静止画面，不等待尚未恢复的音频。停止、错误、完成或切换来源时会清除遮挡状态。
遮挡只改变 `IjkPlayerView` 上的普通 View，不隐藏、移除或重建 SurfaceView，也不额外暂停
IJK，保留上一轮音视频恢复修复。普通缓冲和未发生加密 seek 的播放不启用此遮挡。
层叠方式依据 Android [SurfaceView 文档](https://developer.android.com/reference/android/view/SurfaceView)：
保留底层 Surface 的生命周期，用上方的普通 View 覆盖视频输出。

`encryptedSeekShutterHidesOldVideoUntilTargetRenders` 使用延迟 8 秒的目标分片，分别在
硬解和软解下检查屏幕实际像素：初始红色视频、seek 期间黑色遮挡、新位置蓝色视频；同时
检查 Surface 不变、目标进度不回退、暂停后可看到目标帧，以及停止后遮挡清除。
2026-10-25 已在华为 TAS-AL00 / Android 12 的独立验证包内实测通过，硬解、软解均覆盖。
`SeekRenderGateTest` 另覆盖视频帧先于音频就绪时的暂停显示条件。

`SeekRenderGateTest` 覆盖音频晚到、视频晚到、定位前旧事件、缺失流和目标替换。
`PlayerManagerPlaybackTest.webDavTsslSeeksWaitForBothOutputsAndKeepAudioVideoClocksAligned`
在独立验证包内播放合成 H.264 + AC-3 `.m3u8sp`，通过生产监听器验证两路 seek 输出，
并读取 IJK 已有的 A-V 差值属性进行数值检查（反射仅在测试代码使用）。硬解、软解均覆盖
非分片边界的前后跳转、连续拖动和暂停恢复。设备输出有最多 1.5 秒的时钟收敛窗口，随后
连续 12 个 100ms 样本必须为有限值且绝对差不超过 250ms；此检查不能替代特定媒体的实际听感。

环回解密代理（`EncryptedHlsServer`）使用弹性线程池 + 连接槽上限，读完请求行后把读超时提高到 120 秒，避免播放器的空闲 keep-alive 连接占死固定线程池而让真实分片请求超时（表现为「进去没有画面，随后报错」）。

代理绑定 `127.0.0.1`，并向 IJK 提供该地址的 HTTP 播放 URL。`network_security_config.xml` 必须明确允许 `127.0.0.1` 的明文流量；只放行 `localhost` 不会覆盖这个数字地址，否则 WebDAV 的范围读取和 TSSL 校验成功后仍会在播放器连接本机代理时失败。其他远程地址仍遵循原有网络安全配置。

新扩展已加入本地和 WebDAV 媒体识别。TSSL import 使用完整 v2/v3/v4 schema 验证，不再只检查版本和 identifier 是否非空。源文件名加密 AAD 和标准 Base64 编码也与 Qt 定义保持一致。

## 元数据显示

WebDAV 目录先显示文件列表，再异步读取 `.m3u8s` / `.m3u8sp` 的根 manifest：`.m3u8sp` 只需 header + index + manifest 三次小范围请求（实测一个 1 MB 包约 6 KB 流量），`.m3u8s` 最多预读 256 KiB。多个加密包并行读取（上限 4），结果按路径缓存在本次服务会话内，切换目录会取消旧请求。

identifier 优先按严格规则解析（4096 字符 Base64URL），失败时再用宽松前缀解析取识别码，按桌面端规则缩写为前 16 位、`...` 和后 12 位；如果本机存在匹配的 TSSL，且 manifest digest、源文件名密文以及 v4 容器绑定均通过验证，则同时显示解密后的原始文件名（无本机 TSSL 时该行不显示，属正常状态）。读取失败时该行显式显示「识别码： 不可用」，不再出现字段凭空消失的情况。元数据读取失败不会阻塞普通 WebDAV 目录浏览。

浏览页的导航栈记录进入顺序，系统返回键与工具栏返回按 `a/b/c → a/b` 逐级返回，只有在服务根目录才退出；从播放页返回仍停留在原目录。

本地 TSSL 管理页显示包文件名和同样格式的识别码预览。完整 identifier、密钥及未经验证的源文件名不会传给界面。

## 打包

Android 打包器不执行 FFmpeg 转码，因此输入仍须是现成 HLS 文件夹；移动端打包器默认将加密后的 manifest/TS 写为 Qt 兼容的 `.m3u8sp` POSIX/PAX TAR，并生成绑定 index digest 与 container length 的 TSSL v4。生成的根 manifest 按分段时长写入 `#EXT-X-TARGETDURATION`（RFC 8216 必需项）。Packager API 仍提供 `DIRECTORY_V3` 显式兼容模式，以便需要旧目录格式的调用方使用。TSSL 备份默认使用 Qt 兼容 ZIP 快照，也可选择旧散装文件；容器中的 TSSL 始终保留原始字节和完整 v4 字段，详见 [压缩备份与恢复](tssl-backup-zip.md)。

## 验证

```text
./gradlew testDebugUnitTest assembleDebug lint
```

手工测试需要从 Qt 创建真实 `.m3u8sp` + TSSL v4，分别通过 SAF 和支持 HTTP Range 的 WebDAV 播放，并验证篡改 index、manifest、segment、TSSL index digest 或 container length 均会失败。

`EncryptedHlsCancellationTest` 和 `WebDavRangeCancellationTest` 覆盖取消旧读取、关闭连接、取消响应体与后续请求可用性。`PlayerManagerPlaybackTest.webDavTsslSeekInterruptsSlowOldSegmentAndKeepsLatestPausedTarget` 使用合成 TSSL v4 容器和严格 Range 服务，将旧分片延迟 8 秒，检查 seek 耗时、目标画面和连续拖动后的暂停状态。测试素材 `video_sample_seek.zip` 为 FFmpeg 生成的 30 秒 H.264 + AC-3 HLS，0–10 秒红色、10–20 秒绿色、20–30 秒蓝色，每 2 秒一个分片，不包含用户媒体或凭据。

2026-10-18：95 项 JVM 测试、debug APK/AndroidTest 构建与 lint 通过（0 error，3 项既有 warning）；华为 TAS-AL00 / Android 12 上，上述 WebDAV TSSL seek 测试通过，实际目标画面在 4 秒上限内出现，并通过软解/硬解的暂停 Surface 恢复与字幕会话回归，共 2 项实机测试。

2026-10-22 音视频恢复回归：华为 TAS-AL00 / Android 12（API 31）在独立 `.regression`
验证包上通过慢分片中断测试及新增的硬解/软解时钟测试，共 2 项，最终耗时 25.24 秒。
前后跳转目标为 23.75、12.25、18.5 秒，另覆盖暂停后连续跳转到 4 / 22 秒再恢复，
以及 seek 后立即停止。两路输出未就绪前，播放器不得报告播放中。两种解码器共 96 个
时钟样本的最大绝对差为 238.755ms，收敛后的差值约为 9–66ms。

修复前保留的独立验证包在同一测试中触发「两路输出尚未就绪就报告播放中」断言；恢复修复版
后通过。该对照验证的是恢复时序，使用的仍是生成样本，尚未核对用户原始出错文件的听感。
结果保存在忽略的构建目录 `build/tssl-av-seek/`，包括 `before-fix-device-results.log`、
`final-device-results.log` 和 `final-clock-samples.txt`。最终正常包名下的 debug APK、
AndroidTest APK、148 项 JVM 测试和 lint 均通过（0 error，3 项既有 warning）。

原片可选诊断 `originalTsslLoopbackSeekClockProbe` 默认跳过。通过
`originalTsslService` 和 `originalTsslPath` 参数显式指定验证包中用户已配置的 WebDAV 服务及
`.m3u8sp` 路径时，使用应用已有 Repository 和 EncryptedHlsManager，覆盖完整范围读取、
解密、中断旧请求与原生输出恢复；测试不导出账号或 TSSL 密钥。时钟报告仅记录输出事件、
缓存统计、恢复耗时与 A-V 差值。只提供 `originalTsslPort` 时则仅能检查已有本机入口，
无法调用持有入口的旧应用的读取取消回调，且入口会受旧应用生命周期影响，不能作为完整链路验证。

用户指定的约 180 秒 4K H.264 + AAC 原片已在完整链路中复现：首次从头播放正常，但定位到
24 秒后只出现视频 seek 输出事件，音频 seek 输出未到达，A-V 差值变为 NaN。基于该结果，
进一步移除了播放中 seek 之前额外发送的原生 pause，仍保持静音及双路输出就绪条件。
该原片在用户重新配置的独立验证包上通过完整链路复测（硬解，137.892 秒）：定位到 24、90、
150 秒以及退回 30 秒均恢复两路输出，连续采集的 120 个 A-V 差值样本最大绝对值为
41.988ms，各次稳定值约为 0、25、15、34ms。此为播放器输出时钟检查，不替代实际听感确认。
每次恢复仍需 25.9–27.2 秒，本次没有解决原片的这段加载等待，不能描述为即时 seek。

最终候选版本再次通过合成慢分片中断、连续拖动、暂停恢复及硬解/软解同步回归，2 项实机测试
耗时 25.049 秒；正常包名下的 debug APK、AndroidTest APK、148 项 JVM 测试和 lint 均通过
（0 error，3 项既有 warning）。原片失败/通过对照、时钟样本及本地检查结果保存在忽略目录
`build/tssl-original-seek/`，主要文件为 `configured-device-results.log`、
`active-seek-device-results.log`、`active-seek-clocks.txt`、`active-seek-regressions.log`
及 `local-check.log`。测试过程中没有从旧应用复制账号或 TSSL 密钥；验证包配置由用户在手机上完成。

2026-10-23 seek 画面遮挡修复：debug APK、AndroidTest APK、149 项 JVM 测试及 lint
均通过（0 error，3 项既有 warning），最终日志保存在忽略目录
`build/tssl-seek-shutter/final-local-check.log`。本轮 ADB 未检测到设备，新增的硬解/软解
屏幕像素测试仅完成编译，尚未实机执行；不将此前音视频时钟回归结果视为本轮画面验证结果。

2026-10-25 补做实机验证：在华为 TAS-AL00 / Android 12（API 31）独立 `.regression`
验证包中，新增 seek 遮挡用例通过实际屏幕像素检查红色旧画面、加载时黑色遮挡与蓝色目标
画面，硬解和软解均通过；同一轮慢分片取消/连续暂停目标测试、双路输出/音画时钟回归
也通过，共 3 项。没有修改原生解码器或生产播放代码，仍使用合成 H.264 + AC-3 媒体，
此次未重测用户原始文件的听感与公网加载耗时。设备日志位于
`build/device-audit-20261025/final-device-results.log`。
