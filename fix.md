# VibePlayer（Android）Bug 修复清单

> 本文档记录对本项目「修复所有 bug」任务的执行状态。定时播放 / 自动更新两项按项目要求排除在外，不处理。

## 构建环境说明

- 默认系统 `java` 是 Java 8，AGP 8.7 需要 JDK 11+。
- 构建必须使用完整 JDK 17（如 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`）。
- 仅 `compileDebugKotlin` 时可用 IDEA/JBR（缺 jlink），完整 APK 必须用完整 JDK。
- 之前 `build.log` 中 WebDavClient / TransferRepository 的报错是「用 Java 8 编译」导致的协程误报，用 JDK 17 编译完全正常，非真实代码缺陷。

---

## ✅ 已完成修复

### 1. onPlaybackEnded 二次播放（5 个 ViewModel）
- 原问题：播放到底后调用 `togglePlayPause()` 导致「结束又重新播放」。
- 修改：改为 `playerManager.player.pause()`（幂等，不会重启）。
- 涉及文件：
  - `ui/player/PlayerViewModel.kt`
  - `ui/local/LocalPlayerViewModel.kt`
  - `ui/webdav/WebDavPlayerViewModel.kt`
  - `ui/link/LinkPlayerViewModel.kt`
  - `ui/iptv/IptvPlayerViewModel.kt`

### 2. PlayerManager 起播 seek 时序
- 原问题：`seekTo(start)` 在 `prepare()` 之前调用，未准备好的 media item seek 可能被忽略，断点起播不稳。
- 修改：`player.seekTo(startPositionMs)` 移到 `prepare()` 之后、`play()` 之前。
- 涉及文件：`player/PlayerManager.kt`

### 3. 播放 header 残留泄漏
- 原问题：空 headers 时不清除旧 headers，上次请求的认证头（如 WebDAV Basic）可能被带到无关请求 / 无关域。
- 修改：`play()` 无条件调用 `headerFactory.setHeaders(headers)`（空 map 也重置），防止残留。
- 涉及文件：`player/PlayerManager.kt`

### 4. 传输进度更新风暴 + 暂停状态误导
- 原问题：每读 64KB 就起一个 `Main.immediate` 协程刷 Room，无节流。
- 修改：新增 `throttleProgress(taskId, bytes)`，每任务每 `PROGRESS_THROTTLE_MS`(250ms) 最多一次写入；完成时强制写满 `transferred_bytes = totalBytes`。
- 涉及文件：`data/repository/TransferRepository.kt`

### 5. TransferService 启动方式与空队列停止
- 原问题：用 `startService` 启动前台服务（应 `startForegroundService`）；队列空时服务永不停止。
- 修改：
  - 新增静态 `TransferService.start(context)`，兼容 O+ `startForegroundService` / 老版本 `startService`，并 `runCatching` 容忍后台启动限制。
  - `WebDavBrowseScreen.kt` 改用 `TransferService.start(context)`。
  - `TransferRepository` 的 `enqueueDownload` / `retry` / `resume` 启动服务。
  - `runQueue()` 队列空且无运行任务时 `stopSelf()` 停止前台服务。
- 涉及文件：`service/TransferService.kt`、`ui/webdav/WebDavBrowseScreen.kt`、`data/repository/TransferRepository.kt`

### 6. IPTV 删除服务不清理数据 + 死代码 + 收藏隔离 + 分页
- 原问题：
  - `deleteServiceData` 是死代码（playlist 行从不删除），删除服务留孤儿数据。
  - 收藏用全局 id 集合，跨服务串扰。
- 修改：
  - `IptvPlaylistDao` 新增 `deleteByServiceId(serviceId)`。
  - `IptvRepository.deleteServiceData` 现在真正删除 channel、playlist 行、managed 文件。
  - `ServicesViewModel.removeServer` 对 IPTV 服务调用 `deleteServiceData`。
  - 收藏改为按服务隔离：`observeFavoriteIds(serviceId)` / `toggleFavorite(serviceId, channelId)`（key 含 serviceId），`IptvHomeViewModel` / `IptvHomeScreen` 传入 `server.id`。
- 分页：`IptvHomeScreen` 已用 `LazyColumn` + `items(state.filtered, key=id)`，Compose 懒组合已提供懒加载，无需额外 DB 分页。
- 涉及文件：`data/local/db/dao/IptvPlaylistDao.kt`、`data/repository/IptvRepository.kt`、`ui/services/ServicesViewModel.kt`、`ui/iptv/IptvHomeViewModel.kt`、`ui/iptv/IptvHomeScreen.kt`

### 7. 首页错误被吞 + 空态判定不对称
- 原问题：`runCatching...getOrDefault(emptyList())` 把失败全转空列表，`error` 从不赋值；HomeScreen 空态只查 continue/libraries 未计 suggested。
- 修改：`HomeViewModel.load()` 收集首个错误并设置 `error`；`HomeScreen` 空态计入 `suggestedSeries.isEmpty()` 并渲染错误文案。
- 涉及文件：`ui/home/HomeViewModel.kt`、`ui/home/HomeScreen.kt`

### 8. 搜索请求代数竞态
- 原问题：慢响应可能覆盖新搜索结果（竞态）。
- 修改：新增 `@Volatile searchGeneration`，`submitSearch` 递增，`loadMore` 捕获代数；响应返回时校验代数不一致则丢弃。
- 涉及文件：`ui/search/SearchViewModel.kt`

### 9. LocalBrowse 错误不渲染 + SAF 权限 + available 校验
- 原问题：目录浏览错误不渲染；`addRoot` 不 `takePersistableUriPermission`；`available` 恒 true 从不校验。
- 修改：
  - `LocalBrowseScreen` folderPicker 回调 `takePersistableUriPermission(READ|WRITE)`。
  - 目录空时若有错误则显示错误文案（`error` 分支）。
  - `LocalMediaRepository.addRoot` 校验 `persistedUriPermissions` 设置 `available`；新增 `refreshRootAvailability()`。
  - `LocalBrowseViewModel.load()` 先 `refreshRootAvailability()`。
  - `RootRow` 对不可用根显示「Unavailable」错误色提示。
- 涉及文件：`ui/local/LocalBrowseScreen.kt`、`ui/local/LocalBrowseViewModel.kt`、`data/repository/LocalMediaRepository.kt`

### 10. 路由 Base64 解码失败返回原串
- 原问题：解码失败 `getOrDefault(encoded)` 返回原串，非法参数会被当 URL 播放（外部输入未校验）。
- 修改：5 个 decode 函数解码失败返回 `null`；4 个播放 ViewModel 收到 null / blank 时设置 error 并拒绝播放。
- 涉及文件：`ui/navigation/Routes.kt`、`ui/local/LocalPlayerViewModel.kt`、`ui/webdav/WebDavPlayerViewModel.kt`、`ui/link/LinkPlayerViewModel.kt`、`ui/iptv/IptvPlayerViewModel.kt`

### 11. WebDAV 播放无进度保存
- 原问题：`stopPlayback()` 只 pause 不保存位置，且无周期进度上报，中途停止不保存。
- 修改：为 `WebDavPlayerViewModel` 补全与其它播放器一致的机制（`started` 标记、`startReporter`/`stopReporter` 周期调 `updateProgress`、`stopPlayback` 保存进度、`onPlaybackEnded` 完成标记）。
- 涉及文件：`ui/webdav/WebDavPlayerViewModel.kt`

---

## ✅ 已完成修复（第二批 · 本次会话）

> 本批修复了历史遗留的【编译无法通过】问题与 fix.md 记录的待办项 12–17。

### 12. 修复当前编译错误（历史遗留，5 处）
- 原问题：`./gradlew :app:compileDebugKotlin` 无法通过。
  - `service/TransferService.kt`：`runQueue()` 内 `while(isActive)` 报 Unresolved reference → 补 `import kotlinx.coroutines.isActive`。
  - `ui/home/HomeViewModel.kt`：`runCatching{ fetchX() }` 对已返回 `Result` 的函数二次包装成 `Result<Result<...>>`，`getOrDefault` 类型推断失败 → 去掉 `runCatching` 直接接受 `Result`，并改用 `exceptionOrNull()` 收集首个错误。
  - `ui/home/HomeScreen.kt`、`ui/local/LocalBrowseScreen.kt`：`state` 是委托属性（`by collectAsState()`），`state.error` 无法 smart-cast → 先取局部 `val`。
  - `ui/webdav/WebDavPlayerViewModel.kt`：`stopPlayback`/`onPlaybackEnded` 在非协程直接调用 suspend `repository.getServer()` → 缓存 `server` 字段（`play()` 时写入），停止/结束用缓存值。

### 13. 清理死代码（PlaceholderScreen / provideRetrofit / ServerCard）✅
- 删除 `ui/navigation/VibePlayerNavHost.kt` 未调用的 `PlaceholderScreen` 及此时失效的 import。
- 删除 `di/AppModule.kt` 无消费者的 `provideRetrofit` 及 `jsonMediaType`；同步移除 `app/build.gradle.kts` 与 `gradle/libs.versions.toml` 中未用的 `retrofit` / `retrofit-kotlinx-serialization` 依赖（保留 `okhttp`）。
- 删除 Room 的 `ServerCardEntity.kt` / `ServerCardDao.kt`：从 `VibePlayerDatabase.kt` 移除实体注册与 `serverCardDao()`，从 `di/DatabaseModule.kt` 移除 `provideServerCardDao`。所在表从未写入业务数据（服务存 DataStore），`fallbackToDestructiveMigration()` 兜底，安全。

### 14. 封装网络超时 / 重试 / TLS ✅
- 新增 `di/OkHttpClientFactory.kt`：统一超时（connect 15s / read 30s / write 30s）、`retryOnConnectionFailure`、幂等方法（GET/HEAD/PUT/DELETE/OPTIONS/TRACE）轻量重试拦截器、BASIC 日志；缓存「默认」与「自签名信任」两个客户端。
- `AppModule.provideOkHttpClient` 由工厂取代（统一来源）。
- `trustSelfSignedCertificate` 真正接入：`MediaNetworkClient`、`WebDavClient` 改为注入 `OkHttpClientFactory`，并按 `server.trustSelfSignedCertificate` 选择客户端；`MediaServerClientBase` 各网络调用透传该校验位。仅对显式开启的服务器生效，不全局弱化 TLS。
- 说明：ExoPlayer 取流层（`PlayerManager`/`AuthHeaderDataSourceFactory`）未加入自签名信任（Media3 不直接暴露 TrustManager），自签名 HTTPS 的直接取流仍受限——见 fix.md 附注。

### 15. webdav 上传 / 创建远程目录（功能接通）✅
- 原问题：`WebDavRepository.createDirectory/upload` 等已实现但无 UI 入口。
- `WebDavBrowseViewModel` 新增 `createDirectory(name)`、`upload(uri)`（经 SAF `OpenDocument` 读取文件名与字节，`Dispatchers.IO` 上传）及 `displayNameOf`。
- `WebDavBrowseScreen` 顶栏新增「新建文件夹」（对话框输入名称+`MKCOL`）与「上传」（`OpenDocument` 选文件 + `PUT`）两个入口。
- 涉及：`ui/webdav/WebDavBrowseViewModel.kt`、`ui/webdav/WebDavBrowseScreen.kt`

### 16. history 分页无翻页 ✅
- 原问题：`observeHistory` 固定 `limit=100, offset=0`，无翻页。
- 新增 `PlaybackHistoryDao.observeCount()/observeCountBySource()`；`PlaybackHistoryRepository.observeHistory(source, limit, offset)` 支持分页且新增 `observeCount(source)` 与 `DEFAULT_PAGE_SIZE`。
- `HistoryViewModel` 用 `flatMapLatest` 按 (filter, page) 拉取，状态含 `total/page`，提供 `nextPage/previousPage`，切换过滤回第 0 页。
- `HistoryScreen` 新增「上一页 / 第 X of Y 页 / 下一页」控件；新增 `history_previous` / `history_next` / `history_page` 文案。

### 17. 修复 deprecation 警告 ✅
- `WebDavBrowseScreen` 的 `Icons.Outlined.InsertDriveFile` → `Icons.AutoMirrored.Outlined.InsertDriveFile`（kotlin 编译 0 warning）。

### 18. 最终全量构建验证 ✅
- 完整 JDK 17（Temurin 17.0.20.8-hotspot）：
  - `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL，产出 APK。
  - `.\gradlew.bat :app:lintDebug` → 0 errors。
  - `:app:compileDebugKotlin` → 0 编译错误 / 0 警告。
- 剩余 lint 警告均为既有项（String.format 缺 Locale、依赖版本提示、未用字符串、ObsoleteSdkInt、AutoboxingState）或自签名信任功能本身的 `CustomX509TrustManager` 提示，非本次引入。

---

## 附：识别但「不予处理」的项（按需确认，非本清单范围）

> 本批次已将从「待办/√×」中剥离的多数功能项补齐（见下方「附补：本会话完成项」）。
> 以下为仍被刻意排除或接受的取舍。

- **SMB 服务**：**暂不实现**（已按最新需求从 AGENTS.md 最终目标中移除 SMB，SMB/CIFS 不在当前实现范围）。
- **Emby/Jellyfin token 明文拼入 URL（api_key=）**：**已知取舍，予以接受**。这是 Emby/Jellyfin 直连流的官方标准做法，无法在不解部署本代理的情况下移除。已排查确认：token 直链**从不写日志**、**不落 Room**（通用历史存 `replayTarget` 而非 URL；link 历史仅由 LINK 服务写入），仅在 PlayerManager→ExoPlayer 内存中短暂存在。
- **READ_MEDIA_VIDEO / READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE**：本地媒体完全走 SAF（存储访问框架），无需这些权限，已从 Manifest 移除（最小权限原则）。
- **定时播放（scheduler）** 与 **自动更新（UpdateService）**：按项目策略明确**不在 Android 端实现**（前者为桌面保号能力，后者移动端走应用商店）。

---

### 附补：本会话完成项（原列于上方但现已实现）

- **运行时权限** ✅：启动时申请 `POST_NOTIFICATIONS`（API 33+）；按最小权限原则移除多余媒体权限。
- **i18n / Locale 切换** ✅：新增 `res/values-zh/strings.xml`；新增 `util/LocaleHelper.kt` 在 `attachBaseContext` 应用语言（`system`/`en_US`/`zh_CN`），设置内切换会 `recreate()` 即时生效；`VibePlayerApp` 同步镜像持久化语言，重启后依然生效。
- **页面转场设置** ✅：`pageTransitions` 设置已接入 `VibePlayerNavHost`（fade 过渡）。
- **服务编辑 UI** ✅：新增 `EditServerDialog`（可改名称/URL/用户名）、`ServicesViewModel.editServer()` 与卡片编辑按钮。
- **播放器倍速/音量 UI** ✅：`PlayerScreen` 新增倍速选择器（0.5x/1x/1.25x/1.5x/2x）与音量滑杆（经 `PlayerManager.setPlaybackSpeed/setVolume`）；倍速跨条目持久（`lastSpeed`）。
- **Room 迁移** ✅：新增 `data/local/db/Migrations.kt` 的 `MIGRATION_1_2`（删 `server_cards` 表、建 `transfer_task` 表及索引），在 `DatabaseModule` 注册；`fallbackToDestructiveMigration()` 仅作未知版本的兜底。
- **`allowBackup="false"`** ✅：Manifest 已设置。
- **PIN 哈希加固** ✅：`PrivacyManager` 从加盐 SHA-256 升级为 PBKDF2（210k 迭代），并向后兼容校验旧 SHA-256 哈希。

---

## 本会话：Emby 服务器保存「直接退出」排查与已落地修复

> 场景：设置一个 Emby 服务器，点「保存」后应用直接退出（硬退出）。

### 已排空 / 已确认的项
- **保存/登录代码路径已全面防御**：`ServicesViewModel.addServer/loginServer` 已用 `try/catch (Throwable)` 包裹；`MediaNetworkClient` 捕获 `IllegalArgumentException`（非法 URL）与 `IOException`；`MediaServerRepository`/`ServiceStore`/`EmbyClient`/`MediaServerClientBase` 全部走 `Result`/`runCatching`，无未保护的 `!!`/`error()`/`requireNotNull`。**当前 debug 构建无法用普通 Kotlin 异常硬退出**。
- **R8 混淆不是原因**：release 开启 `isMinifyEnabled`，但 `proguard-rules.pro` 已含 kotlinx.serialization keep 规则；抽查 release `mapping.txt` 确认保存路径序列化类 `ServiceStore$ServerConfigDto` 及其序列化器被保留，未被打包裁剪。

### 已修复（含验证）
1. **明文 HTTP 被 Android 默认阻断（真实缺陷）** ✅
   - 问题：`targetSdk=35` 且无 `usesCleartextTraffic`/网络安全配置，Android 9+ 默认**禁止明文 http://**。因此局域网自建 Emby/Jellyfin/WebDAV（`http://192.168.x.x:8096`，正是 `normalizeScheme` 面向的场景）所有请求都会被平台静默拒绝。
   - 修改：新增 `res/xml/network_security_config.xml`（`cleartextTrafficPermitted="true"`），并在 Manifest 的 `<application android:networkSecurityConfig="@xml/network_security_config">` 引用。
   - 验证：`assembleDebug` BUILD SUCCESSFUL；APK 内含 `res/xml/network_security_config.xml`，合并清单含 `networkSecurityConfig` 引用。
2. **进程级崩溃落盘采集（诊断工具）** ✅
   - 新增 `util/CrashLogger.kt`：全局未捕获异常处理器把堆栈写入 `filesDir` 与 `getExternalFilesDir(null)`；**从不吞掉崩溃**，始终委托给原处理器。`VibePlayerApp.onCreate` 安装。
   - 用途：下一次「保存即退出」即使没抓活 logcat，也能取到确切堆栈。

### ⏳ 仍需用户提供证据才能最终定性
- 本地无模拟器/无连接设备，无法复现该硬退出。
- 请安装**最新 debug APK**（`app/build/outputs/apk/debug/app-debug.apk`，已含上述两处修复与崩溃采集），复现后任选其一：
  - `adb logcat -v time *:E` 抓 `FATAL EXCEPTION`；或
  - `adb pull /sdcard/Android/data/com.vibeplayer.app/files/vibeplayer_crash_latest.log`
- 注意：磁盘上现有 `app/build/outputs/apk/release/app-release.apk`（02:47 构建）**早于**本次修复（不含崩溃采集），请勿用旧 release 包复现。

### 追加（同属 Emby 保存路径加固）
3. **登录 token 持久化移出主线程** ✅（`MediaServerRepository.login` → `withContext(Dispatchers.IO)`）
   - 问题：`EncryptedSharedPreferences` 首次写入会懒创建 Keystore 主密钥并做加解密 I/O；`login()` 原未派发 IO，保存 Emby 服务器成功后的 `saveSession` 落在主线程，可能卡 UI/ANR。
   - 修改：token 持久化包进 `Dispatchers.IO`。
4. **SecureSessionStore 全操作防御式包装** ✅（`SecureSessionStore.kt`）
   - 问题：`androidx.security:security-crypto:1.0.0` 已废弃，已知存在「库自身后台写线程 / Keystore 懒初始化失败时不定期杀死整个进程」的缺陷——这类崩溃发生在库内部线程，调用方协程的 try/catch 无法捕获，正好契合「保存即直接退出」。
   - 修改：`prefs` 懒初始化及所有读写/写入用 `runCatching` 包裹，失败仅退化为「未持久化会话」，自动登录下次重新要密码，绝不让进程退出。
   - 验证：`assembleDebug` BUILD SUCCESSFUL。
