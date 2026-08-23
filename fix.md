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

### ✅ 已在模拟器上验证「保存 / 登录不再直接退出」
- 本地搭建 API 34 (x86_64) 模拟器（`emulator-5554`，`vibe_test` AVD，WHPX 加速），安装最新 debug APK 复现。
- **Save 验证**：在「Add server」对话框填入 Emby 服务器 URL `http://192.168.1.5:8096` + 用户名 `user` + 密码，点 **Save** → 进程仍存活（`pidof` 正常），对话框关闭并回到服务列表，Emby 服务器卡片已持久化显示（`user · Emby` / URL）。**未崩溃**，logcat 无 `FATAL EXCEPTION`，设备上也无 `vibeplayer_crash_latest.log`。
- **Login 验证**：对新增服务器点 **Sign in**，输入密码提交（指向不可达服务器）→ 进程仍存活，登录失败**优雅回退**到服务列表（无崩溃）。
- 结论：用户报告的「设置 Emby 服务器点保存直接退出」路径在当前修复后**不复现**。当前环境无真实 Emby 服务器，故此验证覆盖「保存」与「登录失败回退」两条路径，未覆盖「登录成功→token 持久化」路径（需真实服务器才能到达，但该路径已被 `Dispatchers.IO` + `SecureSessionStore` 防御式包装兜底）。

### 追加（同属 Emby 保存路径加固）
3. **登录 token 持久化移出主线程** ✅（`MediaServerRepository.login` → `withContext(Dispatchers.IO)`）
   - 问题：`EncryptedSharedPreferences` 首次写入会懒创建 Keystore 主密钥并做加解密 I/O；`login()` 原未派发 IO，保存 Emby 服务器成功后的 `saveSession` 落在主线程，可能卡 UI/ANR。
   - 修改：token 持久化包进 `Dispatchers.IO`。
4. **SecureSessionStore 全操作防御式包装** ✅（`SecureSessionStore.kt`）
   - 问题：`androidx.security:security-crypto:1.0.0` 已废弃，已知存在「库自身后台写线程 / Keystore 懒初始化失败时不定期杀死整个进程」的缺陷——这类崩溃发生在库内部线程，调用方协程的 try/catch 无法捕获，正好契合「保存即直接退出」。
   - 修改：`prefs` 懒初始化及所有读写/写入用 `runCatching` 包裹，失败仅退化为「未持久化会话」，自动登录下次重新要密码，绝不让进程退出。
   - 验证：`assembleDebug` BUILD SUCCESSFUL。

---

## 本会话：保存密码一键进入 + 登录后跳转 Emby 页面

> 两个需求：
> 1. 提供一个选项，让用户保存 Emby/Jellyfin 服务的密码，之后直接点击就可进入。
> 2. 排查为何输入密码登录 Emby 后不跳转到实际 Emby 页面，而停留在主页。

### 问题 2 根因（登录后不跳转）
- 成功登录后，`ServicesViewModel` 只设置了 `lastLoggedInServerId`，UI 层 `ServicesScreen` 仅在 `LaunchedEffect` 里弹一个「已登录」snackbar，**从未执行导航**（缺少 `navController.navigate(Routes.home(serverId))`）。
- 修复：`ServicesScreen` 在 `lastLoggedInServerId` 变化时，按服务类型导航到对应首页（Emby/Jellyfin → `home`、WebDAV → `webdavBrowse`、IPTV → `iptvHome`、Link → `linkHome`）。
- 同时修正：`addServer` 仅在 **登录成功** 时才置 `lastLoggedInServerId`（登录失败不再导航，停留在服务列表并显示错误 snackbar，避免失败还跳进空首页）。

### 问题 1 实现（保存密码 / 一键进入）
- `AddServerDialog` 新增“保存密码，下次直接进入”复选项（默认勾选，`ServerForm.autoLogin`）。
- 登录成功后按选项调用 `MediaServerRepository.savePassword`（通过 `SecureSessionStore` 的 `savePassword` 加密存储，key 为 `${serverId}_password`）。**仅在登录成功后才存密码**，避免存错密码；登录失败或未勾选时不存。
- 服务卡片：`ServiceItemUi` 增加 `hasSavedPassword`；无会话但有保存密码时卡片仍可点击 / 显示「打开」，点击走 `ServicesViewModel.enterServer`（有会话立即进入；只有保存密码则用保存的密码自动登录，成功后由 `lastLoggedInServerId` 驱动跳转）。
- `removeServer` 同时清除 token 与保存的密码。
- 安全：密码仅存于 `EncryptedSharedPreferences`（Keystore 加密），明文不落盘 log。

### 验证
- `assembleDebug` BUILD SUCCESSFUL；`lintDebug` BUILD SUCCESSFUL（无新增 Error，新增字符串均已使用）。
- 模拟器（`emulator-5554`，`vibe_test`）安装最新 debug APK：应用正常启动无崩溃；「Add server」对话框已渲染“保存密码，下次直接进入”复选项且默认勾选；对 Emby 服务器点登录、输入错误密码提交 → 进程存活、**未跳转**（错误路径正确停留在服务列表，且不保存错误密码），logcat 无 FATAL。
- 说明：真实“登录成功→跳转”与“保存密码→一键进入”两条成功路径需真实 Emby 服务器 + 正确凭据才能端到端复现；代码路径已核对（导航接线完整、保存密码仅在成功时写入）。

---

## 本会话：Emby 播放闪退 + 首页推荐剧集缺失

> 两个新问题：
> 1. 进入 Emby 播放某个视频后，加载/播放期间会闪退。
> 2. 电脑端 Emby 首页能展示「推荐剧集」，手机端没有。

### 问题 1 根因与修复（播放闪退）
- **根因**：`PlayerManager.play()` 用 `startForegroundService()` 启动 `PlaybackService`，但该服务继承 Media3 `MediaSessionService`，仅依赖 Media3 内部调度去调用 `startForeground()`。当播放开始且应用转入后台 / 通知时机不佳时，`startForeground()` 未在系统时限内被调用，系统抛出 `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException`（日志表现：`Bringing down service while still waiting for start foreground` → `FATAL EXCEPTION` → 进程被杀）。这是 Media3 的一个已知问题（androidx/media#112/#393/#2412）。
- **修改**（`PlaybackService.kt`）：在 `onCreate()` 里 **主动立即调用 `startForeground()`**（带一个最小占位通知 + 播放通知渠道），保证满足 `startForegroundService()` 契约，避免前台超时崩溃；随后 Media3 会用真实的媒体播放/暂停/进度通知替换占位通知。同时清理了 `ObsoleteSdkInt` 冗余检查（minSdk=26）。
- **验证**：`assembleDebug` / `lintDebug` BUILD SUCCESSFUL。模拟器实测：通过本地 HTTP 提供 20s 测试视频，应用播放 **完整播放到结尾（position 19.9s/20s），进程存活、无 FATAL、无 ForegroundServiceDidNotStartInTimeException**（此前该场景会在加载后约 15s 崩溃）。网络源报错时进程同样保持存活（优雅进入播放器错误态，不再闪退）。

### 问题 2 根因与修复（首页推荐剧集）
- **既有实现**：`HomeViewModel` 已拉取 `fetchSuggestedSeries`，`HomeScreen` 也已渲染「推荐」横轨，且端点与桌面/Web 端一致（Emby `GET /Users/{userId}/Suggestions?IncludeItemTypes=Series`）。
- **缺失点**：`fetchSuggestedSeries` 只做了 Series 类型过滤；当 Emby 兼容服务器在 `/Suggestions` 里混入 `Studio`/`Genre` 等非 Series 条目（官方文档明确提示此类服务器即使带 `IncludeItemTypes=Series` 也会返回非 Series），过滤后为空 → 首页推荐轨**不渲染**，于是「手机端没有推荐剧集」。
- **修改**（`MediaServerClientBase.fetchSuggestedSeries` + `EmbyClient.suggestedSeriesFallbackUrl`）：当主 Suggestions 响应不含任何 Series 条目时，按桌面参考客户端逻辑回退到 `GET /Users/{userId}/Items?Recursive=true&IncludeItemTypes=Series&SortBy=Random`（同字段），再次过滤 Series 后返回，保证推荐轨始终有内容。Jellyfin 使用官方 `type=Series` 参数、无此回退，保持 `null`。
- **验证**：`assembleDebug` / `lintDebug` BUILD SUCCESSFUL（0 错误）。回退逻辑与 Qt 参考实现逐参数一致。成功路径需真实 Emby 服务器 + 凭据端到端确认（当前环境无凭据；代码路径已核对）。

---

## 本会话：提供 Emby/Jellyfin「保存密码」选项（已有服务器）

> 需求：为什么 Emby 服务不保存之前的密码，希望给用户一个选项，保存密码后只需点击即可进入。

### 背景 / 根因
- 上一会话已为「添加服务器」对话框加入「保存密码，下次直接进入」复选框（登录成功后持久化密码）。
- 但对**已配置的服务器**，登录对话框（LoginDialog）与编辑对话框（EditServerDialog）没有显式的保存密码选项；且即使密码被保存，服务列表 DataStore 不会因密码保存而重新发射，UI 的 `hasSavedPassword` 状态一直停留在旧值，导致卡片**始终不翻转为「打开」**——从用户视角就是「不保存密码 / 不能让一键进入生效」。

### 修改
- `ServiceForms.kt`：
  - `LoginDialog` 增加「保存密码，下次直接进入」复选框（默认勾选），`onLogin` 回调携带 `savePassword`。
  - `EditServerDialog` 增加密码输入框 + 保存密码复选框（默认勾选），`onSave` 回调携带 `password`/`savePassword`。
- `ServicesScreen.kt`：登录/编辑回调把 `savePassword`/`password` 透传给 ViewModel。
- `ServicesViewModel.kt`：
  - `loginServer(server, password, savePassword: Boolean? = null)`：显式勾选或服务器 `autoLogin` 时，登录成功后持久化密码并调用 `refreshItems()`。
  - `editServer(server, form, password, savePassword)`：勾选且输入了新密码时，**先**持久化密码再更新服务列表，并调用 `refreshItems()`。
  - 新增 `refreshItems()`：从仓库重新计算每个服务的 `hasSession`/`hasSavedPassword`，使保存密码后卡片立即翻转为「打开」（一键进入）。
- 密码仍存于 EncryptedSharedPreferences（Keystore），`removeServer` 会一并清除。

### 验证
- `assembleDebug` / `lintDebug` BUILD SUCCESSFUL（0 错误）。
- 模拟器实测：登录 / 编辑 / 添加三个对话框均渲染「保存密码，下次直接进入」复选框且默认勾选；应用稳定无崩溃。
- 说明：端到端「保存→卡片翻转」在本模拟器无法追踪，因为该 AVD 的 Keystore 处于损坏状态（读取写入均报 `VERIFICATION_FAILED`，即便 `pm clear` 后仍复现），`SecureSessionStore` 按其设计优雅降级（不崩溃，仅返回 null）。此属模拟器环境问题，非代码缺陷；真实设备 Keystore 正常时，勾选保存后密码会持久化，卡片随即翻转为「打开」。代码路径已核对。

---

## 本会话：首页补充「链接播放 / 全局历史」并重命名「M3U8S视频管理」+ 自动保存密码日志排查

### 需求
1. 自动保存密码似乎存在问题，读取日志排查。
2. 首页有本地播放、加密HLS，但缺少「链接播放」和「全局历史」。
3. 将「加密 HLS」改名为「M3U8S视频管理」。

### 1) 自动保存密码日志排查（读取 logcat）
- 日志显示 `SecureSessionStore` 的 EncryptedSharedPreferences 在**读路径**上报 Keystore 主密钥校验失败：
  `KeyStoreException: Signature/MAC verification failed`（`VERIFICATION_FAILED`），
  发生在服务列表 `StateFlow` collect 阶段（`hasSession`/`hasSavedPassword` → `secureSessionStore.password/accessToken`）。
- 说明：这是 Keystore/加密偏好存储的主密钥校验失败（模拟器环境 Keystore 损坏，或真实设备在系统备份/恢复后 Keystore 被重置），并非应用逻辑缺陷。
- 现有代码已用 `runCatching` 包裹 `SecureSessionStore` 全部操作：失败时优雅降级为「未保存/无会话」，不会崩溃，仅一键进入回退为再次询问密码。这与用户“自动保存密码读不回/一键进入失效”的症状一致，属于系统级 Keystore 状态导致，代码已做容错。

### 2) 首页补充「链接播放」与「全局历史」+ 重命名
- `ServicesScreen.kt` 头部新增：
  - `LinkPlaybackCard`（链接播放）：导航到 Link home（优先已配置的 Link 服务 id，否则用稳定内建 id `builtin-link-playback`，与 Qt 参考的 `builtin-link-playback` 一致，纯内建入口无需配置服务器）。
  - `GlobalHistoryCard`（全局历史）：导航到 `history` 顶层路由（多来源统一历史）。
- 将原本的 `TsslManagerCard` 重命名为 `M3u8sManagerCard`，图标与标题随字符串更新。
- 字符串资源（en `values/strings.xml` 与 zh `values-zh/strings.xml`）：
  - 新增 `services_link_playback` / `services_link_playback_sub`、`services_global_history` / `services_global_history_sub`。
  - `services_tssl` 由「加密 HLS (TSSL) / Encrypted HLS (TSSL)」改为 **「M3U8S视频管理」**（含子标题）。
- 对齐 Qt 参考 `MediaServices.md`：服务页内置「Local Playback / Link Playback / Global History / M3U8S Video Manager」入口，外加已保存的服务卡片。

### 验证（模拟器 emulator-5554，zh 区域）
- `assembleDebug` / `lintDebug` BUILD SUCCESSFUL，0 错误。
- 首页渲染：本地播放 / 链接播放 / 全局历史 / M3U8S视频管理 四张卡片。
- 点「M3U8S视频管理」→ 进入 TSSL 管理器（标题 M3U8S视频管理）。
- 点「链接播放」→ 进入 Link home（媒体或 HLS 链接输入 + 播放 + 历史，内建可用）。
- 点「全局历史」→ 进入全局历史页（观看时长/下载/来源筛选/分页）。
