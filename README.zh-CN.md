# VibePlayer（Android）

[English](README.md) | [简体中文](README.zh-CN.md)

VibePlayer 是一个原生 Android 媒体中心：Emby、Jellyfin、WebDAV、IPTV、本地文件夹与直链媒体，
全部在同一套播放栈里完成浏览与播放。全部代码使用 Kotlin + Jetpack Compose，播放核心是 Media3/ExoPlayer
—— 不使用 Qt、Electron、WebView 或 libmpv。

本项目是 [vibePlayerQT](vibeEmbyPlayerQT/README.zh-CN.md)（Qt Quick 桌面客户端）的 Android 重写版，
桌面端源码作为嵌套参考仓库保留在本仓库内，其 `vibeEmbyPlayerQT/VIBEDOCS/` 记录了本应用需要复刻的行为，
因此协议与格式的实现细节一律以桌面端与官方文档为准，不做猜测。

项目仍在积极开发中：当前版本 `0.1.0`、`versionCode 1`，尚未上架应用商店。

## 功能亮点

- 原生 Kotlin + Jetpack Compose，Material 3 设计规范，支持 Material You 动态取色并带静态回退配色。
- 以 Media3 / ExoPlayer 作为唯一播放核心，统一由 `PlayerManager` 管理；在线、本地、WebDAV、IPTV、
  直链、加密 HLS 全部经过同一套播放栈。
- Emby 与 Jellyfin：登录、多服务器多账号、媒体库、搜索、继续观看、详情页（含季/集）、断点续播与播放进度上报。
- 音频自动回退：当本机无法解码某部影片的音轨（AC-3、E-AC-3、DTS、TrueHD 等）时，
  只要求服务器转码音频、视频保持 stream copy，而不是「有画面没声音还不报错」。
- WebDAV：PROPFIND 目录浏览、MKCOL 建目录、上传、经前台服务的后台下载，以及直接播放。
- 加密 HLS 包：播放桌面端产出的 `.m3u8s` 目录包与 `.m3u8sp` 单文件容器，
  由本机环回代理逐分片解密并校验后交给播放器。
- 端上 TSSL 管理：打包 HLS 目录、导入并校验包、备份与恢复到 WebDAV。
- IPTV：导入 M3U/M3U8，支持分组、搜索与收藏。
- 本地文件夹通过 Storage Access Framework 接入；同时支持 HTTP/HTTPS 直链与 HLS 链接播放。
- 跨所有来源的统一播放历史，含观看时长与流量统计。
- 播放与传输各自使用前台服务，提供通知、媒体会话与锁屏控制。
- 深色 / 浅色 / 跟随系统主题，应用内中英文切换与独立语言覆盖，可选 PIN 隐私模式。

## 项目状态

| 模块 | 状态 |
| --- | --- |
| Emby | 登录、服务器管理、媒体库、搜索、继续观看、详情、季/集、播放地址构建、断点续播与进度上报均已实现。 |
| Jellyfin | 与 Emby 同一套能力，使用 `MediaBrowser` 认证方案。 |
| WebDAV | PROPFIND 浏览、MKCOL、上传、后台下载与传输列表、视频播放、加密包元数据均已实现。 |
| 加密 HLS | `.m3u8s` 与 `.m3u8sp` 播放已实现，经环回解密代理逐分片做 SHA-256 与 AES-256-GCM 校验。 |
| TSSL | v2/v3/v4 读写、端上打包、导入校验、WebDAV 备份与恢复均已实现。 |
| IPTV | 经 SAF 导入 M3U/M3U8、分组、搜索、收藏与播放已实现。 |
| 本地媒体 | SAF 文件夹选择、目录浏览与播放已实现；每次访问都会重新校验授权。 |
| 链接播放 | HTTP/HTTPS 直接媒体与 HLS 播放、历史与统计已实现。 |
| 定时播放 | 不实现 —— 桌面端的保号定时策略在移动端刻意不做。 |
| 自动更新 | 不实现 —— 移动端通过应用商店分发。 |
| SMB | 未实现，当前不在范围内。 |

## 架构

分层 MVVM + Repository，单向数据流。Compose 只观察 `StateFlow`，UI 层不直接访问网络、数据库或播放器。

```text
Compose UI
  -> ViewModel (StateFlow)
    -> UseCase / Service
      -> Repository
        -> DataSource（网络 / Room / DataStore / 播放器）
```

```text
app/src/main/java/com/vibeplayer/app/
  data/local/       Room 数据库、DAO、DataStore、基于 Keystore 的密文存储
  data/remote/      OkHttp 传输层、Emby 与 Jellyfin 客户端、WebDAV 与 IPTV 客户端
  data/repository/  每个来源唯一数据入口，供多个 ViewModel 复用
  domain/           链接播放、本地媒体、TSSL 用例
  player/           PlayerManager（Media3）、播放会话、HTTP 请求头处理
  player/hls/       加密 HLS：环回代理、TAR/CBOR 索引、manifest 元数据
  domain/tssl/      TSSL 文档模型与 HLS 打包器
  service/          PlaybackService 与 TransferService 前台服务
  security/         Keystore 密文存储与 PIN 隐私模式
  ui/               home、library、details、player、webdav、iptv、local、link、
                    transfer、history、tssl、services、settings、theme、navigation
  di/               Hilt 模块
```

## 技术栈

- Kotlin 2.0.21，JVM target 17
- AGP 8.7.3、Gradle 8.10.2，compileSdk / targetSdk 35，**minSdk 26**
- Jetpack Compose（BOM 2024.12.01）+ Material 3 + Material You 动态取色
- Media3 1.5.0（ExoPlayer、HLS、DASH、session、OkHttp data source）
- Hilt 2.52 依赖注入，Navigation Compose 2.8.5
- Room 2.6.1、DataStore 1.1.1、Coil 2.7.0
- OkHttp 4.12.0 配合 kotlinx.serialization（JSON 与 CBOR），不使用 Retrofit
- 全程协程与 Flow，主线程不做阻塞调用

## 开始使用

### 环境要求

- JDK 17（完整 JDK，不能是仅 JRE 或仅 JBR 运行时）
- Android SDK：platform 35 与 build-tools 35.x
- 使用项目自带的 Gradle Wrapper，无需本机另装 Gradle

`local.properties` 需指向你的 SDK：

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

### 构建

```bash
./gradlew assembleDebug      # debug APK，使用 Android Debug 证书签名
./gradlew assembleRelease    # 需要配置 release 签名环境变量
./gradlew testDebugUnitTest  # 单元测试
./gradlew lintDebug          # lint 报告位于 app/build/reports/
```

debug APK 产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

debug 构建刻意强制开启 v1、v2、v3 三种签名方案。AGP 在 `minSdk >= 24` 之后不再写 v1（JAR）签名块，
而不少真机安装路径 —— OEM 自带文件管理器、侧载工具、部分 `pm install` 分支 —— 会先读 JAR 签名块，
读不到就判定 APK 未签名从而拒绝安装。

### Release 签名

Release 构建一定会签名；缺少凭据时直接失败，绝不产出未签名包。执行 `./gradlew assembleRelease` 前设置：

| 环境变量 | 含义 |
| --- | --- |
| `KEYSTORE_FILE` | `.jks` keystore 路径 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | keystore 内的 key 别名 |
| `KEY_PASSWORD` | 私钥密码 |

keystore 与密码一律不得提交进仓库。

## CI

`.github/workflows/android-build.yml` 在每次 push 与 pull request 时构建，随后：

- 上传 `app-debug-apk-android-test-signed`，上传前会校验该 APK 确实由 Android Debug 测试证书签名；
- 构建并上传 `app-release-apk`，未签名的产物一律拒绝上传；
- 执行 lint。

## 文档

- `docs/tssl-v4-m3u8sp.md` —— TSSL v4 与 `.m3u8sp` 容器结构、播放与校验流程
- `docs/self-signed-certificates.md` —— 自签名证书服务器的信任策略
- `docs/player-fullscreen-audio-tracks.md` —— 全屏行为与音轨选择
- `docs/user-messages.md` —— 面向用户的错误文案约定
- `fix.md` —— 缺陷记录：现象、根因、修法，以及证明修好的实测数据
- `AGENTS.md` —— 本仓库的架构约束与开发规范
- `vibeEmbyPlayerQT/VIBEDOCS/` —— 协议与格式来源的桌面端参考文档

## 安全说明

- Emby/Jellyfin 访问令牌、WebDAV 密码以及用户选择保存的登录凭据，
  统一由不可导出的 Android Keystore 密钥以 AES-256-GCM 封装（`KeystoreSecretStore`），不明文落盘。
- `EncryptedSharedPreferences` 仅保留用于迁移旧版本写入的数据；新数据一律走 Keystore 方案，
  后者按条目降级，不会像前者那样整体不可读写。
- 日志中绝不出现密码、令牌、Cookie 或带令牌的完整播放地址。
- 「信任自签名证书」是按服务器逐个显式开启的选项，界面有明确警告，因为它会关闭证书链与主机名校验。
- 不要把真实服务器凭据、令牌、keystore 或含令牌的日志提交进仓库。

## 后续计划

- 更完整的播放错误映射与更完善的外挂字幕支持。
- 在更多机型与更多 WebDAV / HLS 服务器上验证加密播放。
- 持续优化大媒体库与长距离 seek 下的播放表现。
- 扩充协议解析与 TSSL 容器相关的自动化测试。
- 若有需求，再基于成熟的库重新评估 SMB 支持。
