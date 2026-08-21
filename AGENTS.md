# AGENTS.md

# 项目说明

本项目将位于 `vibeEmbyPlayerQT/` 下的 Qt Quick（C++）跨平台桌面媒体播放器完整重写为**原生 Android 应用**。

全部代码使用 **Kotlin** 编写，基于原生 Android 组件（Jetpack Compose / AndroidX），不使用 Qt、Electron、CEF、WebView 等跨平台技术。

界面设计严格遵循：

- https://developer.android.com/design/ui
- https://m3.material.io/（Material Design 3）

> 请先阅读 `vibeEmbyPlayerQT/` 下的源码与 `vibeEmbyPlayerQT/VIBEDOCS/` 文档，理解原功能与边界后再编写 Android 代码，禁止凭空猜测原项目行为。

## 项目定位

- 名称：VibePlayer（Android）
- 定位类似 Jellyfin Media Player / Infuse / VidHub / Kodi（轻量化）
- 核心目标：提供现代化、高性能的移动端媒体中心体验
- 使用原生播放能力（ExoPlayer / Media3），不引入 libmpv

项目优先级：

1. 播放体验
2. Android 系统兼容性与性能
3. 代码可维护性
4. 界面美观性（Material 3）

---

# 特别注意

- Emby / Jellyfin / WebDAV 等协议接口：**必须先查阅官方 API 文档**再编写代码（`vibeEmbyPlayerQT/VIBEDOCS/EmbyJellyfinApi.md`、`WebDAV.md` 可作为参考）。
- 任何不确定的实现问题都要查询官方文档，禁止编造接口与函数签名。
- Android 系统能力（播放、前台服务、通知、存储、网络、权限等）应先查阅官方 Android 文档。

---

# 技术栈

## 语言与构建

- Kotlin（优先使用现代 Kotlin 特性：协程、Flow、data class、sealed class、StateFlow）
- Gradle + Kotlin DSL（`build.gradle.kts`）
- targetSdk / compileSdk 使用当前稳定版本
- 仅使用稳定的官方 AndroidX 库

## UI

- Jetpack Compose + Material 3（`androidx.compose.material3`）
- Material You 动态配色（Dynamic Color）优先，并支持静态主题回退
- 遵循 Material 3 组件规范与设计系统（颜色、排版、间距、动效、深浅主题）

UI 层只负责：

- 页面展示
- 动画效果
- 用户交互
- 导航

禁止在 UI 层实现复杂业务逻辑（网络 / 数据库 / 播放器控制）。

## 架构

采用分层架构（MVVM + Repository）：

```
UI (Compose)
   ↓
ViewModel (StateFlow)
   ↓
UseCase / Service
   ↓
Repository
   ↓
DataSource (网络 / 数据库 / 播放器 / 系统)
```

- 单向数据流：UI 观察 `StateFlow`，事件通过 ViewModel 中的方法触发
- Repository 作为唯一数据入口，为多个 ViewModel 复用
- 禁止 UI 直接发起网络请求或访问数据库/播放器

## 网络层

- 使用 OkHttp / Retrofit（或 Ktor Client）
- 序列化使用 kotlinx.serialization 或 Moshi
- 所有网络请求必须异步（协程），禁止阻塞主线程
- 超时、重试、TLS 处理统一封装
- 禁止明文存储 Token / 密码

## 数据存储

- 轻量设置：DataStore（Preferences / Proto）
- 结构化数据/历史：Room（SQLite）
- 缓存：Coil（图片）/ Media3（流媒体缓存）等

## 播放器

- Media3 (ExoPlayer) 作为唯一播放核心
- 播放器生命周期由 PlayerController/PlayerManager 统一管理
- 所有播放（在线 / 本地 / WebDAV / IPTV / HLS）都经由同一播放管理层

## DI

- 使用 Hilt 进行依赖注入

## 图片与资源

- Coil（Compose）加载远程图片（Emby/Jellyfin 缩略图、WebDAV 封面等）

---

# 从 QT 项目迁移的功能清单

参考 `vibeEmbyPlayerQT/src/` 与 `vibeEmbyPlayerQT/VIBEDOCS/` 迁移以下功能（按模块）：

## 媒体来源 / 服务

- **Emby**（`services/emby`、`VIBEDOCS/EmbyJellyfinApi.md`）
  - 登录认证、多服务器管理、媒体库浏览、搜索、继续观看、断点续播、用户信息
  - 使用官方 REST API
- **Jellyfin**（`services/jellyfin`）
  - 同 Emby 能力，官方 REST API
- **WebDAV**（`services/webdav`、`VIBEDOCS/WebDAV.md`、`EncryptedHlsM3u8s.md`）
  - 认证、文件/文件夹浏览、视频直连播放、上传、下载（TransferManager）、TSSL 加密 HLS 打包与备份
  - 标准 WebDAV 协议（PROPFIND / OPTIONS / GET），禁止自造协议
- **IPTV / M3U**（`services/iptv`、`VIBEDOCS/IPTV.md`）
  - M3U/M3U8 频道列表、分类分组、收藏、搜索
- **本地媒体**（`services/local`、`VIBEDOCS/LocalPlayback.md`）
  - 本地文件夹浏览与播放（Android 上需使用 Storage Access Framework / MediaStore）
- **链接播放**（`services/link`、`VIBEDOCS/LinkPlayback.md`）
  - HTTP/HTTPS 直接媒体与 HLS 链接校验、按日期保存的历史、流量统计

## 核心功能

- **统一播放历史 / 全局历史**（`VIBEDOCS/GlobalPlaybackHistory.md`）
  - 多来源统一历史、SQLite 数据模型、断点续播、流量统计、重播路由
- **定时播放**（`services/scheduler`、`VIBEDOCS/ScheduledPlayback.md`）
  - ⚠️ **Android 端不需要实现**：手动/重复保号策略、日期计算、自动队列、随机选片、前台播放抢占等保号能力仅服务于桌面自建媒体，移动端不做此功能。
- **流量统计 / 使用统计**（`DailyUsageStat`、`SessionRepository`）
- **隐私模式**（PIN、隐私卡片设置）
- **TSSL / 备份**（`services/backup`、`services/webdav/TsslStore`、`VIBEDOCS/TsslBackup.md`）
- **加密 HLS 打包 / 代理**（`services/encryptedhls`、`VIBEDOCS/EncryptedHlsM3u8s.md`）
- **多服务器管理**（`ServiceCard`、`ServiceCardListModel`、拖拽排序）
- **设置**（`settings`、`VIBEDOCS/SettingsAppearance.md`：i18n、明暗主题、布局切换）

> **自动更新（UpdateService，`VIBEDOCS/GitHubActionsRelease.md` / `services/update`）Android 端不需要实现。** 移动端通过应用商店（Google Play 等）分发，自带更新服务不属于 Android 版功能范围。

Android 特有考虑：

- 下载与传输需使用前台服务 + Notification（满足后台下载）
- 权限管理（通知、存储/媒体、网络、后台）
- 系统媒体会话 / 媒体通知 / 锁屏控制（MediaSession）
- 断点续播使用播放进度持久化

---

# 推荐的包结构

```
app/src/main/java/<package>/
    VibePlayerApp.kt            # Application（Hilt）
    MainActivity.kt
    di/                         # Hilt 模块
    model/                      # 数据模型（data class）
    data/
        local/                  # Room 数据库、DAO、DataStore
        remote/                 # OkHttp/Retrofit、API service
        repository/             # Repository 实现
    domain/
        usecase/                # 用例
    ui/
        theme/                  # Material 3 主题、配色、类型
        navigation/             # Compose 导航
        home/                   # 首页 / 媒体库
        player/                 # 播放页
        services/               # 服务（Emby/Jellyfin/WebDAV/IPTV/本地）列表与管理
        settings/               # 设置页
        history/                # 播放历史 / 流量统计
        transfer/               # 下载传输管理
        iptv/                   # IPTV 频道
        local/                  # 本地媒体
        # scheduler 不需要：定时播放（保号）非 Android 端功能
    player/                     # ExoPlayer 封装，统一播放管理
        hls/                    # 加密 HLS 播放代理（loopback 解密服务）
    service/                    # 前台服务（下载、后台播放/MediaSession）
    domain/tssl/                # TSSL 加密打包 / AES-256-GCM / TSSL 文档模型
    util/
```

resource / res/ 遵循 Android 与 Material 规范（drawable、values、values-night 等）。

---

# 开发规范

## 修改代码之前

1. 阅读相关代码
2. 理解现有实现
3. 搜索已有类似功能
4. 评估影响范围
5. 必要时阅读 `vibeEmbyPlayerQT/VIBEDOCS/` 对应文档

禁止直接猜测实现方式。

## 文档优先

- 官方 Android 文档：https://developer.android.com/docs
- Android 设计规范：https://developer.android.com/design/ui
- Material 3：https://m3.material.io/
- Emby / Jellyfin / WebDAV 官方 API 文档

禁止编造接口与签名。

## 最小修改原则

优先"最小改动"完成需求，避免大规模重构与无关代码修改，除非明确要求。

## 错误处理与日志

- 使用密封类（sealed class）表达操作结果（成功/失败），显式携带错误
- 关键操作记录日志：登录、播放、网络请求、错误、服务连接
- 使用 Android 官方 `Log` 或简单日志封装；统一日志接口
- 禁止在日志中记录 Token、密码、Cookie

## 状态与并发

- 所有耗时操作通过协程在 `Dispatchers.IO` 等后台调度器执行
- UI 状态用 `StateFlow` / `MutableStateFlow` 管理并收集
- 避免在 UI 线程做网络、文件扫描、数据库大量查询

## 性能要求

- 保证较大媒体库浏览流畅（分页/懒加载）
- 元数据加载不阻塞界面
- 后台下载与播放稳定可靠
- 图片使用 Coil 缓存，避免重复加载

## 界面规范

遵循 Material 3：

- 使用 Material 3 组件（TopAppBar、Card、NavigationBar/BottomBar、FAB 等）
- 动态配色（Dynamic Color）+ 静态回退主题
- 支持浅色 / 深色主题并跟随系统
- 简洁、现代化、适合移动端触控操作
- 避免复杂嵌套页面、冗余设置项

## 安全要求

- 禁止明文存储密码、Token、Cookie
- 使用 Android Keystore / EncryptedSharedPreferences 等安全方案
- 所有外部输入视为不可信数据，必须校验

## 兼容性

- 尊重 Android 版本差异，必要时使用系统兼容 API
- 使用权限时遵循最小权限原则，并提供运行时权限申请

## AI Agent 工作流程

每一个任务：

1. 阅读相关代码
2. 理解现有架构（含 QT 参考实现）
3. 查阅官方文档
4. 设计最小修改方案
5. 编写代码（Kotlin）
6. 检查构建/编译是否通过（`./gradlew assembleDebug` 或对应 task）
7. 检查功能是否正常
8. 更新相关文档

禁止跳过代码阅读步骤，禁止在不了解现有结构时直接修改。

---

# 构建与验证

- 项目根目录使用 Gradle 构建
- 编写/修改 Kotlin 代码后运行相关构建与 lint：
  - `./gradlew assembleDebug`
  - `./gradlew lint`
- 涉及 Compose 的代码确保可编译并遵循 Material 3 约束

---

# 最终目标

打造一个现代化、高性能的原生 Android 媒体播放器，完整覆盖 QT 版本的 Emby / Jellyfin / WebDAV / IPTV / 本地媒体 / 链接播放能力，界面遵循 Material Design 3，在保证播放体验的前提下实现长期可维护、可扩展的架构。

> **SMB 服务暂不实现**：SMB（含 CIFS）服务**当前不在实现范围内**，本项目暂不要求也不实现 SMB 支持。若未来需要再另行评估。
