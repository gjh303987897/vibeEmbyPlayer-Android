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
6. 后续 TS/playlist/resource 按 index 随机读取并校验；TS 继续在 AES-256-GCM tag 验证后才交给 Media3。

环回解密代理（`EncryptedHlsServer`）使用弹性线程池 + 连接槽上限，读完请求行后把读超时提高到 120 秒，避免 Media3 的空闲 keep-alive 连接占死固定线程池而让真实分片请求超时（表现为「进去没有画面，随后报错」）。

新扩展已加入本地和 WebDAV 媒体识别。TSSL import 使用完整 v2/v3/v4 schema 验证，不再只检查版本和 identifier 是否非空。源文件名加密 AAD 和标准 Base64 编码也与 Qt 定义保持一致。

## 元数据显示

WebDAV 目录先显示文件列表，再异步读取 `.m3u8s` / `.m3u8sp` 的根 manifest：`.m3u8sp` 只需 header + index + manifest 三次小范围请求（实测一个 1 MB 包约 6 KB 流量），`.m3u8s` 最多预读 256 KiB。多个加密包并行读取（上限 4），结果按路径缓存在本次服务会话内，切换目录会取消旧请求。

identifier 优先按严格规则解析（4096 字符 Base64URL），失败时再用宽松前缀解析取识别码，按桌面端规则缩写为前 16 位、`...` 和后 12 位；如果本机存在匹配的 TSSL，且 manifest digest、源文件名密文以及 v4 容器绑定均通过验证，则同时显示解密后的原始文件名（无本机 TSSL 时该行不显示，属正常状态）。读取失败时该行显式显示「识别码： 不可用」，不再出现字段凭空消失的情况。元数据读取失败不会阻塞普通 WebDAV 目录浏览。

浏览页的导航栈记录进入顺序，系统返回键与工具栏返回按 `a/b/c → a/b` 逐级返回，只有在服务根目录才退出；从播放页返回仍停留在原目录。

本地 TSSL 管理页显示包文件名和同样格式的识别码预览。完整 identifier、密钥及未经验证的源文件名不会传给界面。

## 打包

Android 不集成 FFmpeg，因此输入仍须是现成 HLS 文件夹；但移动端打包器现在默认将加密后的 manifest/TS 写为 Qt 兼容的 `.m3u8sp` POSIX/PAX TAR，并生成绑定 index digest 与 container length 的 TSSL v4。生成的根 manifest 会按分段时长写入 `#EXT-X-TARGETDURATION`（RFC 8216 必需项，缺失会被 ExoPlayer 拒绝）。Packager API 仍提供 `DIRECTORY_V3` 显式兼容模式，以便需要旧目录格式的调用方使用。TSSL 备份按原始字节传输，因此完整保留 v4 字段。

## 验证

```text
./gradlew testDebugUnitTest assembleDebug lint
```

手工测试需要从 Qt 创建真实 `.m3u8sp` + TSSL v4，分别通过 SAF 和支持 HTTP Range 的 WebDAV 播放，并验证篡改 index、manifest、segment、TSSL index digest 或 container length 均会失败。
