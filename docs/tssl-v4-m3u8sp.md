# TSSL v4 / M3U8SP Android 支持

Android 端现已读取 Qt 桌面端定义的 TSSL v4 和 `.m3u8sp` 单文件容器，同时保留 TSSL v2/v3 `.m3u8s` 目录格式兼容。

## 格式

TSSL v4 在 v3 字段之外强制包含：

- `containerFormat = m3u8sp-tar-index-v1`
- `containerIndexSha256`：首个 TAR 成员 `.vibe/index.cbor` 的原始 CBOR 字节摘要
- `containerLength`：整个容器长度

`.m3u8sp` 是未压缩 POSIX/PAX TAR。首成员必须是 `.vibe/index.cbor`，CBOR index version 为 1，记录 manifest 路径及每个成员的 TAR header offset、data offset、长度与 SHA-256。

## Android 播放流程

1. 本地 SAF 通过可定位文件描述符按范围读取；WebDAV 必须返回 `206 Partial Content` 和匹配的 `Content-Range`，返回整个对象的 `200` 会拒绝。
2. 读取并验证首个 TAR header 和 CBOR index，检查安全相对路径、重复、偏移、长度、摘要与容器边界。
3. 从 index 定位根 manifest，校验成员 header 和 SHA-256。
4. 用 manifest 的严格 4096 字符 identifier 查找本地 TSSL。
5. 同时匹配 root manifest digest、index digest、container length 和 container format。
6. 后续 TS/playlist/resource 按 index 随机读取并校验；TS 继续在 AES-256-GCM tag 验证后才交给 Media3。

新扩展已加入本地和 WebDAV 媒体识别。TSSL import 使用完整 v2/v3/v4 schema 验证，不再只检查版本和 identifier 是否非空。源文件名加密 AAD 和标准 Base64 编码也与 Qt 定义保持一致。

## 打包

Android 不集成 FFmpeg，因此输入仍须是现成 HLS 文件夹；但移动端打包器现在默认将加密后的 manifest/TS 写为 Qt 兼容的 `.m3u8sp` POSIX/PAX TAR，并生成绑定 index digest 与 container length 的 TSSL v4。Packager API 仍提供 `DIRECTORY_V3` 显式兼容模式，以便需要旧目录格式的调用方使用。TSSL 备份按原始字节传输，因此完整保留 v4 字段。

## 验证

```text
./gradlew testDebugUnitTest assembleDebug lint
```

手工测试需要从 Qt 创建真实 `.m3u8sp` + TSSL v4，分别通过 SAF 和支持 HTTP Range 的 WebDAV 播放，并验证篡改 index、manifest、segment、TSSL index digest 或 container length 均会失败。
