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

---

## 本会话：修复底部「服务」按钮无法跳转到服务页

### 需求
点击底部的「服务」（Services）按钮不会跳转到服务页面。

### 复现
- 从首页点「全局历史」卡片（plain `navigate("history")`，栈变为 `[services, history]`）后，再点底部「服务」按钮：不跳转，仍停留在历史页。
- 对比：同样状态下点「传输」「历史」「设置」均可正常跳转 → 故障仅针对**起始目的地（Services）**。
- 从干净启动后经底部导航进入 history 再点服务可正常返回（说明与具体进入方式相关，核心是起始目的地导航触发）。

### 根因
- Services 是 NavGraph 的**起始目的地（start destination）**，始终位于返回栈底部、不会被弹栈。
- 底部栏对每个 tab 执行：
  `navigate(route){ popUpTo(findStartDestination().id){saveState=true}; launchSingleTop=true; restoreState=true }`
- 当目标目的地正是起始目的地（services）且栈中已存在该目的地时，`navigate` 与 `launchSingleTop` 在 Navigation Compose（2.8.5）下会判定「目的地已存在」而**成为 no-op**，既不新增 entry 也不切换页面 × popUpTo 也未生效 → 视觉上点击服务无反应。

### 修复（`VibePlayerNavHost.kt`）
- 底部 tab `onClick` 增加「已选中则提前返回」。
- 对**起始目的地 Services** 改用 `navController.popBackStack(Services.route, inclusive=false)`（pop 回根），不再 `navigate`；因为服务页是根且始终在栈底，popBackStack 可靠返回。
- 其余非起始 tab（传输/历史/设置）维持原有 `navigate{popUpTo(start){saveState}; launchSingleTop; restoreState}`。

### 验证（模拟器 emulator-5554）
- `assembleDebug` / `lintDebug` BUILD SUCCESSFUL，0 错误。
- 首页卡片→历史→点「服务」：现可返回服务页。
- 服务→设置→点「服务」：返回服务页。
- 服务→历史→点「服务」：返回服务页。
- 服务→传输：正常跳转（无回归）。
- 遍历导航无 FATAL 崩溃。

---

## 本会话：为什么进入之前的 Emby 服务仍然要求密码（已在模拟器实机复现确认）

### 用户问题
我之前输入过密码，为什么这次进入之前的 Emby 服务依然要密码？

### 结论（实时日志确认，非应用代码 bug）
- 应用逻辑正确：登录成功后把 **token** 与（勾选保存时的）**密码**写入
  `SecureSessionStore`（Keystore 支持的 `EncryptedSharedPreferences`）；
  下次 `enterServer/openServer` 读回 token 自动登录，无需再输密码。
  「保存密码」复选框在添加/登录/编辑三个对话框均正确接线且默认勾选。
- 但本模拟器（AVD `vibe_test`，emulator-5554）的 **Android Keystore 已损坏**：
  实时 logcat 中 VibePlayer (PID 8366) 在 `EncryptedSharedPreferences` 上报
  `android.security.KeyStoreException: Signature/MAC verification failed (VERIFICATION_FAILED, code -30)`。
- 按设计 `SecureSessionStore` 对该失败容错（不崩溃，静默降级为「无会话/未保存密码」）
  → token 与密码**存不进也读不出** → 每次进入只能再次手动输密码。
- 服务器的**账号配置**（名称/地址/用户名）存普通 DataStore
  （`files/datastore/services.preferences_pb`，不依赖 Keystore），
  因此「之前的 Emby 服务」卡片仍会显示。

### 关键佐证：系统级 Keystore 损坏，非本应用问题
- `VERIFICATION_FAILED` 不只来自 VibePlayer，还来自**其它多个进程**（PID 5788/7234/7344 等）
  以及**系统 keystore2 服务本身**（PID 175），时间跨度好几天。
- 此前已记录：该 AVD 即使 `pm clear` 后仍复现，说明仅清应用数据无效。

### 处理建议
- **真机/正常设备**：密码可正常保存，勾选后一键进入，无需修改代码。
- **此模拟器**：要恢复「记住密码」需修复 AVD Keystore——重建或 `-wipe-data`
  冷启动该 AVD（破坏性，会清空该模拟器应用数据与配置）。
- 无应用代码改动（项目安全规范禁止明文存储凭据）。

---

## 本会话：Emby 服务输入 https://emby.bangumi.ca 显示 “invalid server URL” 的原因与修复

### 用户问题
在 Emby 服务里输入 `https://emby.bangumi.ca`，为什么会显示 “invalid server URL”？寻找原因并修复。

### 结论（实时日志 + TCP 实测确认）
**应用代码没有问题，URL 也是有效的。真正原因是「模拟器（AVD `vibe_test`）无法联网」。**

- 应用会把该地址正确存储为 `baseUrl = "https://emby.bangumi.ca"`（直查
  `files/datastore/services.preferences_pb` 确认，无隐藏字符），并拼接出
  `https://emby.bangumi.ca/Users/AuthenticateByName`（`MediaServerClientBase.makeUrl`）。
  OkHttp 对它是**可以正常解析**的有效 URL（单独用 OkHttp 4.12 复现：解析 OK）。
- `emby.bangumi.ca` 由 Cloudflare 托管，域名同时有 A(172.67.212.223) 与 AAAA 记录；
  主机 curl 访问 `https://emby.bangumi.ca` 返回 302、`/Users/AuthenticateByName` 返回 **401**
  → API 就在根路径（**无需 /emby 前缀**），服务器本身正常。

### 根因：模拟器网络没起来
刚重建的 AVD `vibe_test` 冷启动后：
- `eth0` 处于 **DOWN，qdisc noop**（虚拟网卡没被拉起）；
- `ip route` **没有默认网关路由**（缺 `default via 10.0.2.2`）；
- 结果 App 发出登录请求时报 **`UnknownHostException: Unable to resolve host "emby.bangumi.ca"`**，
  即连 DNS 都解析不了 → 表现为连不上服务器 / （旧版本可能显示为）invalid server URL。

（该 AVD 连 `google.com` 都解析不了，且 System 自带的连通性探测也超时，
进一步证明是模拟器整体网络问题，而非本应用或该域名的问题。）

### 修复（已实机验证有效）
恢复 eth0 并补齐默认路由（需要 root，每次冷启动后都要重新执行）：

```bash
adb root
adb wait-for-device
adb shell ip link set eth0 up
adb shell ip route add default via 10.0.2.2 dev eth0
adb shell ip route add 10.0.2.0/24 dev eth0 scope link src 10.0.2.15
```

**验证**：修复后 App 实机登录，logcat 出现
```
--> POST https://emby.bangumi.ca/Users/AuthenticateByName (54-byte body)
<-- 401 https://emby.bangumi.ca/Users/AuthenticateByName (1096ms, 37-byte body)
```
即 App 成功把请求 POST 到该服务器并收到 HTTP 401（凭据错误 → 服务器可达、URL 有效）。
输入错误密码时返回 401 是**正确**表现，不再出现连不上的错误。

### 复现/使用说明
- 一键恢复脚本：`./scripts/emu_netfix.sh`（`--boot` 可先启动模拟器再打补丁）。
- 说明：Android 模拟器的网络配置在每次冷启动后会重置，因此每次冷启动后都需重新打补丁。
- 本问题与「为什么还要密码」的问题是**两个独立问题**（后者是 Keystore 损坏，前者是模拟器网络），
  均已分别确认。

---

## 追加：在「本机」上也安装后，仍然出现 invalid server URL —— 真正的代码级根因与修复

### 用户问题
「我在本机也安装了，依旧出现的是 invalid server URL」。即不止在模拟器上，
在正常联网的真机/桌面端，输入 `https://emby.bangumi.ca` 依然看到
`Invalid server URL: ...`。

### 关键结论：还有一个独立的、代码可修复的根因
之前的"模拟器连不上"只解释了**模拟器**上的现象。但"本机也报 invalid server URL"
说明存在一个**应用代码层面**的真 bug。已定位并用**真实 OkHttp 实测**复现/验证：

- 全代码里唯一的 `"Invalid server URL: ..."` 文案来自
  `MediaNetworkClient.kt:72` 的 `IllegalArgumentException` catch（OkHttp 组请求时抛出）。
- 用项目实际使用的 OkHttp 4.12 实测：
  - 带 scheme 的 `https://emby.bangumi.ca/Users/AuthenticateByName` → **正常解析，不抛异常**。
  - **不带 scheme** 的 `emby.bangumi.ca/Users/AuthenticateByName` →
    `IllegalArgumentException: Expected URL scheme 'http' or 'https' but no scheme was found ...`
    → 被 catch 成 **"Invalid server URL: Expected URL scheme ..."**（与用户所见一致）。
- 根因：`makeUrl(baseUrl, path)` 之前**不做 scheme 规范化**，而 `normalizeScheme`
  只在 `addServer`/`editServer` 保存时调用。因此：
  - 旧版本保存的**无 scheme**的服务器（如 `emby.bangumi.ca`），登录时
    `makeUrl` 会拼出无 scheme 的地址并抛上述异常 → 一直报 invalid server URL；
  - 即便 `normalizeScheme` 已存在，`login(server,...)` 用的是**已存起来的 baseUrl**，
    对旧数据不会在运行时修复。

### 修复（代码已改、已编译、已回归）
在 `MediaServerClientBase.makeUrl()`（所有媒体服务器请求 URL 的统一入口）里做
**scheme 规范化**：无 scheme 的 base 自动补 `http://`（空值不动，保持原有守卫行为）。

```kotlin
protected fun makeUrl(baseUrl: String, path: String): String {
    var base = baseUrl.trim().trimEnd('/')
    if (base.isNotEmpty() && !base.contains("://")) {
        base = "http://$base"
    }
    return base + path
}
```

### 验证
- `assembleDebug`/`assembleRelease` 编译通过。
- 真实 OkHttp 4.12 实测：`emby.bangumi.ca/...` → 报错；`http://emby.bangumi.ca/...` → OK。
- 模拟器回归：新包里登录 `https://emby.bangumi.ca` 仍是 `HTTP 401`（正常 https 不受影响）。
- 已重新打包签名 release APK：`app/build/outputs/apk/release/app-release.apk`
  （含本修复；注：本机临时构建用的是调试证书签名，正式发版需在 CI 用真实 release.jks 重新签名）。
- 请用**包含本修复的新包**安装；若手动重输地址，务必带 `https://`（或不带 scheme 由应用自动补全）。

---

## 本会话：播放本地视频进入播放器后一直「加载中」的根因与修复

### 用户问题
播放本地视频时，进入播放器后一直处于加载中（不开始播放）。

### 根因（代码级确认）
`PlayerManager` 构建 ExoPlayer 时：
```kotlin
.setMediaSourceFactory(
    DefaultMediaSourceFactory(context).setDataSourceFactory(headerFactory)
)
```
其中 `headerFactory`（`AuthHeaderDataSourceFactory`）的 `createDataSource()` **只返回 `DefaultHttpDataSource`（仅 HTTP）**。
把整个数据源栈替换成了「仅 HTTP」的实现。而本地媒体使用 SAF，文件 URI 是 `content://...`，
HTTP 专用 factory 根本无法读取 → ExoPlayer 一直停留在 `STATE_BUFFERING`（无限加载）。

这正是「进入播放器后一直加载中」的**决定性根因**：在线 http(s) 流能放（有 header 注入），
本地 `content://` 却永远读不了。

### 修复（`PlayerManager.kt`）
用 `DefaultDataSource.Factory(context, headerFactory)` 包裹 `headerFactory`：
```kotlin
DefaultMediaSourceFactory(context).setDataSourceFactory(
    DefaultDataSource.Factory(context, headerFactory)
)
```
Media3 的 `DefaultDataSource` 会把非 HTTP scheme（`content://`、`file://`、`asset://`…）
路由到内置的 ContentDataSource / FileDataSource（从而能读本地 SAF 文件），
而 `http(s)://` 仍委托给 `headerFactory`（保留 WebDAV/Emby 认证头注入）。
在线路径行为不变，只新增了本地路径的支持。

### 验证
- ✅ `assembleDebug` / `assembleRelease` 编译通过（`DefaultDataSource.Factory` 为 Media3
  DataSource API，`androidx.media3:media3-exoplayer` 1.5.0 已包含；无需新增依赖）。
- ✅ 在线路径不变：`DefaultDataSource` 对 http(s) 委托给 `headerFactory`（代码核对）。
- ⚠️ **未在本模拟器完成端到端实播验证**：尝试走「本地播放 → SAF 选目录 → 进入播放器」时，
  该模拟器上**本地媒体根列表不渲染**（Room DB 中根已持久化 `available=1`，但 Compose 列表空白，
  uiautomator 也看不到任何列表项）。这是与本次修复无关的、独立的模拟器/自动化环境问题；
  用户在本机可正常进入播放器（只是卡加载），说明其根列表正常。此环境限制挡住了实播覆盖，
  但修复本身是 Media3 播放 `content://` 的标准且必需的机制（修复前「仅 HTTP」factory 必然失败）。
- 修复已提交：`15a0778`。

---

## 本会话：修复用户复测提出的 5 个问题

> 用户反馈（复测后仍存在）：
> 1. 「添加服务器」顶部类型选择框大小不一致、不美观，希望用图标代替文字；
> 2. 服务页的「本地播放 / 链接播放 / 全局历史 / M3U8S视频管理」四个固定卡片，在没有添加任何外部服务时不显示；
> 3. 添加 Emby 服务并输入密码保存后，再次进入该服务仍然要求输入密码；
> 4. 本地播放页添加文件夹后，文件夹卡片在顶部超出实际显示区域；
> 5. 播放本地视频进入播放器后一直卡在「加载中」。

### 1. 类型选择框改为等尺寸纯图标选择器
- 原因：`SingleChoiceSegmentedButtonRow` + `SegmentedButton { Text(displayName) }`，5 个按钮宽度按权重等分，
  但「Jellyfin / WebDAV」等文字在窄按钮里换行数不同 → 各按钮高度不一致（用户所见的“大小不一致”）。
- 修改：`ui/services/ServiceForms.kt`
  - 新增 `ServiceTypePicker`：每个 `ServiceType` 一个 `weight(1f)` + 固定 `height(52.dp)` 的圆角图标块，
    内部只放 24dp 图标（`Modifier.selectable(..., Role.RadioButton)` 保留可访问性），
    选中态用 `secondaryContainer`，未选中用 `surfaceContainerHighest`；
  - 图标统一由新文件 `ui/services/ServiceTypeIcons.kt` 提供（`ServiceType.pickerIcon`）：
    Emby=PlayCircle、Jellyfin=WaterDrop、WebDAV=Cloud、IPTV=LiveTv、Link=Link；
  - 选中类型名以一行小字（`labelMedium`）显示在图标行下方，服务卡片也改用同一套图标；
  - 三个对话框（添加/编辑/登录）内容列改为 `verticalScroll`，小屏不再被裁切。

### 2. 固定卡片始终显示
- 原因：`ServicesScreen` 用 `when { items.isEmpty() -> EmptyServices(...) else -> 列表(带 header) }`，
  而 4 个固定卡片写在列表的 `header` 里 → 没有任何服务器时整个列表（含 header）不渲染。
- 修改：`ui/services/ServicesScreen.kt` 去掉空态分支，**始终**渲染 `ReorderableLazyColumn`（固定卡片在 header），
  无服务器时在 header 内追加 `EmptyServicesHint`（提示 + “添加服务器”按钮）；
  `contentPadding` 同时补上 `innerPadding.calculateBottomPadding()`，不再被底部导航遮挡。

### 3. 保存密码后仍要求输入密码（两处真实缺陷）
**(a) 会话/密码读取路径整体失效且不自治**
- `SecureSessionStore` 原本基于已废弃的 `EncryptedSharedPreferences` + `MasterKeys`：
  创建时会一次性解密全部条目，只要 Keystore 主密钥不可用（AVD/部分机型常见 `VERIFICATION_FAILED`），
  `create()` 抛异常 → `prefs by lazy { ... }.getOrNull()` **把 null 永久缓存**，
  于是本进程内所有 token/密码「写入无效、读取恒空」，用户表现为「保存了却每次还要输入密码」，
  并且没有任何提示（旧版模拟器数据里连 `vibeplayer_secure_sessions.xml` 都未生成，即此原因）。
- 修改：新增 `security/KeystoreSecretStore.kt`，直接基于 AndroidKeyStore：
  - 非导出 AES-256-GCM 密钥（`KeyGenParameterSpec`，不绑定锁屏/生物识别），
    值以 `v1:base64(iv||密文)` 存入普通 SharedPreferences，明文与密钥均不落日志；
  - **逐条容错**：某条解密失败只丢弃该条，不影响其他服务；
  - **自愈**：密钥存在但无法读取时删除并重建条目（旧实现永远不会恢复）；
  - Keystore 暂时不可用时只做退避重试（15s），不永久缓存失败状态；
  - `SecureSessionStore` 改为使用该 store，并保留一次性、best-effort 的旧数据迁移
    （旧文件存在且能解密时搬入新存储，随后删除旧文件；失败则下次再试，绝不抛异常）。
  - 现在密码/token 读取失败不再静默：`MediaServerRepository.savePassword` 返回 `Boolean`，
    写入被拒时 `ServicesUiState.passwordWarning` → 服务页 Snackbar 提示「无法安全保存密码…」（`save_password_failed`）。

**(b) 卡片状态标记过期（即使存储正常也会“还要输密码”）**
- `hasSession/hasSavedPassword` 只在 services DataStore 发射时计算；而 DataStore 只在**添加服务器**时发射一次，
  此时登录与密码写入还没完成 → 卡片永远停在“登录”图标，点进去当然还要输密码。
- 修改：`ServicesViewModel`
  - 抽出 `publishItems()/refreshItems()`，并在 `Dispatchers.Default` 上计算这些标记（避免主线程做解密）；
  - `addServer` 成功后、`loginServer` 成功后、`editServer`、`removeServer` 之后均调用 `refreshItems()`；
  - 新增 `refresh()`，`ServicesScreen` 进入时 `LaunchedEffect(Unit) { viewModel.refresh() }`；
  - 勾选/取消「保存密码」会同步回写 `ServerConfig.autoLogin`，取消勾选时清除已存密码
    （`MediaServerRepository.clearSavedPassword`），编辑/登录对话框的复选框初值取服务器实际 `autoLogin`，
    不再默认勾上（避免“点了保存其实没保存”的错觉）。

### 4. 本地播放列表顶部超出显示区域
- 原因：`MainActivity` 调用 `enableEdgeToEdge()`，Material3 `Scaffold` 只把 `innerPadding` 作为参数交给内容，
  **不会自动应用**；`LocalBrowseScreen` 的根目录列表 `RootsList` 完全忽略了它（`fillMaxSize().padding(16.dp)`），
  目录卡片因此从屏幕顶端（状态栏/顶栏之下）开始绘制，看起来“超出实际显示区域”；
  进入目录后的列表同样只加了 8dp，也没吃 top inset。
- 修改：`ui/local/LocalBrowseScreen.kt`
  - `RootsList` 接收 `topPadding/bottomPadding`，改用 `contentPadding = top + 16 / bottom + 96`；
  - 目录浏览 `LazyColumn` 同样用 `innerPadding.calculateTopPadding()/calculateBottomPadding()`；
  - 空态/加载/错误分支保留 `padding(innerPadding)`；无媒体文案改用 `local_no_media` 资源（中英文）。

### 5. 本地视频「一直加载中」
排除过程与根因（三处叠加）：
1. `content://` 数据源栈（上一提交已修：`DefaultDataSource.Factory(context, headerFactory)` 包裹，
   保留 http(s) 认证头注入的同时让 Media3 内置 ContentDataSource 生效）——但**报错时界面仍不可见**；
2. **失败被当成“加载中”**：`PlayerManager` 只在 READY/ENDED 清 `buffering`，
   出错时 ExoPlayer 进入 `STATE_IDLE`（旧代码没有该分支）→ `buffering` 永远为 true；
   而 5 个播放页只画了一个 `CircularProgressIndicator`，从不显示 `state.error`
   → 任何失败（权限丢失、文件不存在、容器/编码不支持）在用户眼里都是「一直卡顿在加载中」；
3. 无硬件解码器时（HEVC/10bit 等）解码器初始化失败同样落在上述静默路径。

修改：
- `player/PlayerManager.kt`
  - `STATE_IDLE` → `buffering = false, isPrepared = false`；`STATE_ENDED`/`onIsPlayingChanged(true)` 也清 `buffering`；
  - `onPlayerError` → 发布 `errorCodeName`（**只发错误码，不发 exception.message**，避免把带凭据的流地址显示到界面上），
    并清 buffering；
  - `DefaultRenderersFactory(context).setEnableDecoderFallback(true)`：硬解失败自动回落软解；
  - 新增 `clearError()`，播放页 `play()` 开头调用，避免上一个源的错误残留；
  - `AuthHeaderDataSourceFactory.createDataSource()` 改为无条件 `setDefaultRequestProperties(headers)`
    （空 map 也会清空），彻底杜绝上次 WebDAV Basic 头被带到无关请求。
- `ui/components/PlaybackStatus.kt`（新）：`PlaybackStatusOverlay(buffering, error, onBack)`
  - 加载：转圈 + 12 秒后追加「仍在加载，请检查网络或文件…」，不再出现无解释的转圈；
  - 失败：标题「播放失败」+ 错误码对应的可读文案（`playbackErrorText`：文件不存在 / 无权限 /
    连不上 / 服务器拒绝 / 需要 HTTPS / 格式不支持 / 超时）+「返回」按钮；
  - 已替换 PlayerScreen、LocalPlayerScreen、WebDavPlayerScreen、LinkPlayerScreen、IptvPlayerScreen
    五处旧的「仅转圈」实现。
- `ui/local/LocalPlayerViewModel.kt`
  - 播放前在 `Dispatchers.IO` 做一次 SAF 可读性预检（仅 `openAssetFileDescriptor` 取句柄，不读数据）：
    `FileNotFoundException / SecurityException / IOException` 分别给出
    `local_file_missing / local_permission_lost / local_file_unreadable`；
    权限丢失时先尝试对所属 tree 重新 `takePersistableUriPermission` 并复检；
  - 加密 HLS 分支错误文案资源化（`local_unsupported_location`）。
- `data/repository/LocalMediaRepository.kt`：修复进入子目录时列错内容的缺陷——
  子文件夹 URI 是 `…/tree/<treeId>/document/<docId>`，`getTreeDocumentId()` 返回的是**根**目录 id，
  所以进子目录看到的还是根目录内容（也解释了本地浏览“UI 不对”的观感）。
  现按 path 分段分别取 tree id 与 document id 再构造 children URI。

### 验证
- `./gradlew :app:assembleDebug` ✅（`:app:assembleRelease` 仍只因缺少签名 `KEYSTORE_FILE` 环境变量而失败，非代码问题）。
- 模拟器（emulator-5554 / vibe_test）实测：
  - 服务页在无服务器/有服务器两种数据下均显示 4 个固定卡片（uiautomator：本地播放/链接播放/全局历史/M3U8S视频管理 均在树中）；
  - 「添加服务器」对话框 5 个类型图标块 bounds 完全等尺寸（132x143，间距 22px），切换 WebDAV 后尺寸不变，
    选中类型名以单行小字显示，对话框可滚动；
  - 旧数据的 `shared_prefs/` 中只有 `_androidx_security_master_key_.xml`，
    印证了「EncryptedSharedPreferences 从未创建成功 → 密码根本没被保存」这一 #3 根因；新存储路径不再依赖该库。
- #5 的端到端实播（真正播起来）由用户在设备/真机上复测确认；若仍不播放，现在界面会直接显示原因
  （例如「没有读取该文件夹的权限」「本机不支持该视频格式或编码」），可据此继续定位。

### 遗留说明
- `PrivacyManager` 仍使用 `EncryptedSharedPreferences`（PIN 哈希）。未一并改动，避免让用户已设置的隐私 PIN 失效；
  若后续要求，可按同样方式迁移（需要 PIN 迁移策略）。

---

## 2026-09-10 WebDAV 返回层级 / M3U8S 元数据 / 加密 HLS 播放（三项复现后修复）

### A. WebDAV 浏览：系统返回键回到错误层级
- 现象：进入 `a → b → c` 后按系统返回，直接离开 WebDAV（或跳到根目录），而不是 `c → b`；从播放页返回也会掉回根目录。
- 根因：
  1. `WebDavBrowseScreen` 没有 `BackHandler`，系统返回由导航层直接 pop 整条 WebDAV 路由；
  2. `LaunchedEffect(serverId) { viewModel.load(serverId) }` 在从播放页返回重新进入组合时再次执行 `load()`，
     而 `load()` 无条件 `browse(server, "")`，把用户重置到根目录。
- 修改：
  - `ui/webdav/WebDavBrowseViewModel.kt`：`WebDavUiState` 增加 `backStack` / `canNavigateBack`；
    `navigateTo()` 入栈，新增 `navigateBack()`（出栈；已在根目录则返回 false 让界面退出）与栈感知的 `goUp()`；
    `load()` 记录 `loadedServerId`，重复进入只 `retryCurrent()`（刷新当前目录并保留栈），
    `retryCurrent()` 在等待密码时不发请求；上传/建目录后的刷新也保持原栈。
  - `ui/webdav/WebDavBrowseScreen.kt`：新增 `BackHandler(enabled = canNavigateBack && !needPassword)`，
    工具栏返回箭头与系统返回共用 `navigateBack()`。
- 实测（emulator-5554 + 本地 WebDAV）：`movies → a → b → c` 后连按返回依次为
  `c → b → a → movies → 根 → 退出到服务页`；从播放页返回仍停留在进入前的目录。

### B. `.m3u8s` / `.m3u8sp` 元数据抓取浪费流量且失败时无任何提示
- 现象：列目录时每个加密包都发大请求；`.m3u8sp` 每行按 16 MiB+512 预读（logcat 实测单次 1,024,512 字节 Range 体），
  慢且易超时；解析失败时「识别码 / 原始文件」两行直接消失，看不出任何原因。
- 根因：
  - `EncryptedHlsTarContainer` 读索引固定预读 `PREFIX_LIMIT`；
  - 元数据链路复用播放期的严格校验：没有本机 TSSL 时 `require` 直接抛异常 → 连识别码都不显示；
  - 每个加密行串行解析，一个目录里的包数量线性放大等待时间。
- 修改：
  - `player/hls/EncryptedHlsTarContainer.kt`：新增 `indexLength(header)`（只校验首个 512 字节 TAR 头并给出 CBOR 长度）
    与公开 `BLOCK_SIZE`；校验只作用于 512 字节块，不再对整个 Range 体求校验和。
  - `player/hls/M3u8spSources.kt`：`SeekableHlsContainerSource.readIndex()` = header(512) + index 本体，两次范围读。
  - `data/remote/webdav/WebDavClient.kt`、`data/repository/WebDavRepository.kt`：新增 `WebDavPrefix` / `downloadPrefix()`
    （`Range: bytes=0-(n-1)`；206 正常、200 也接受并按实际长度收敛，回报 `complete`）。
  - `player/hls/M3u8sManifestMetadata.kt`：新增宽松 `parseM3u8sIdentifierPrefix()`（Latin-1 容错，只接受唯一一行
    4096 字符 Base64URL identifier），被截断的前缀也能拿到识别码。
  - `player/hls/EncryptedHlsManager.kt`：识别码只依赖 manifest，本机 TSSL 仅用于「原始文件名」；
    `.m3u8s` 最多预读 `MANIFEST_METADATA_PREFIX_BYTES`(256 KiB)；prepare/resolve 全部走 `Dispatchers.IO`；
    `EncryptedHlsMetadata(identifierPreview, sourceFileName)` + `available` 表达「已读完但不可用」。
  - `ui/webdav/WebDavBrowseViewModel.kt`：并行解析（`Semaphore(METADATA_PARALLEL_READS=4)`）+ 本服务会话内按路径缓存 +
    目录代次校验（慢响应不能覆盖新目录）；`model/WebDavItem.kt` 增加 `metadataUnavailable`；
    `WebDavBrowseScreen.kt` 的 `EncryptedMetadataLine` 三态渲染（值 / 加载转圈 / 「不可用」），
    新增 `webdav_metadata_unavailable`（中英）。原始文件名依赖本机 TSSL，缺失时按桌面端习惯不显示。
- 实测：1 MB 的 `.m3u8sp` 包元数据从 1,024,512 字节降到 512 + 613 + 512 + 4435 ≈ 6 KB（约 170 倍）；
  识别码与解密后的原始文件名正常显示；故意放一个非 HLS 的 `broken.m3u8s`，该行显示「识别码： 不可用」而不是空白。

### C. 进入 `.m3u8s` / `.m3u8sp` 后无画面，随后报错
- 根因 1（解密代理并发）：`EncryptedHlsServer` 用 `Executors.newFixedThreadPool(4)` 且 `soTimeout=30s`。
  Media3 会保留空闲 keep-alive 连接，每个空闲连接占死一个工作线程，真正的 manifest/分片请求排在队尾直到超时
  （JVM 探针脚本稳定复现 `SocketTimeoutException`）。
  改为弹性 `newCachedThreadPool` + daemon 线程工厂 + `MAX_CONCURRENT_CONNECTIONS=32` 信号量 + `BACKLOG=64`；
  请求行读超时 `REQUEST_TIMEOUT_MS=10s`（快速回收空闲 socket），读到请求后提升到 `RESPONSE_TIMEOUT_MS=120s` 以容纳慢速远端取片。
- 根因 2（本机包选择）：`matchingDocument` 先按 identifier 命中本机 TSSL 再校验 digest，本机存在多个包时会被相邻包顶替，
  报「Root manifest digest mismatch (tampered or stale)」而根本不放画面。改为与 Qt 桌面端一致：
  先按 root manifest digest 选包（`localDocument` 摘要优先），identifier 只作交叉校验，
  并区分「本机 TSSL 与远端包不匹配」和「没有对应 TSSL」两种文案。
- 附带修正：`domain/tssl/EncryptedHlsPackager.kt` 生成的根 manifest 之前缺少 `#EXT-X-TARGETDURATION`
  （RFC 8216 必需项，ExoPlayer 比 libmpv 严格），现按分段时长上取整写入。
- 实测：`.m3u8sp` 与 `.m3u8s` 目录包都能起播（约 2–3 s 出画，标题为解密后的原始文件名）；
  播放器内向前/向后拖动进度条均能恢复播放，解密代理无超时报错。

### 验证方式
- 设备：`emulator-5554`；数据源：本机 Python WebDAV（PROPFIND / GET / Range / ETag，`adb reverse tcp:8080 tcp:8080`），
  内容为 ffmpeg 生成的真实 TSSL v3 目录包与 TSSL v4 `.m3u8sp` 容器包，TSSL 置于应用 `files/tssl/`。
- `./gradlew testDebugUnitTest assembleDebug lintDebug` 全部通过；
  调试期间使用的探针与夹具单元测试（复现代理饥饿、校验夹具）已按要求删除，未留下常驻测试。

---

## 2026-09-11 播放新视频时仍显示上一个视频的信息

### 现象
播放视频 A → 退出 → 播放视频 B，在 B 还在加载（或准备失败）的这段时间里，播放页顶栏仍显示 A 的片名与服务器名，
进度条也停在 A 的播放位置（实测 `00:08 / 00:24`）。

### 根因
1. `PlayerManager` 是 `@Singleton`，它的 `PlayerState` 跨播放页存活；退出时 `stopPlayback()` 只 `pause()`，不清状态；
2. `WebDavPlayerViewModel` / `LinkPlayerViewModel` / `IptvPlayerViewModel` 的 `play()` 从不调用 `beginLoading()`，
   只有等 `playerManager.play()` 才覆盖旧状态——加密 HLS 的 prepare、SAF 预检、链接校验都要跑几百毫秒到几秒，
   这段窗口就是用户看到的「残留」；
3. 五个播放页 ViewModel 的收集器都写成 `title = p.title ?: it.title`，即使上层把标题清空，界面也永远清不掉，
   旧片名会被永久钉住。

### 修改
- `ui/player/PlayerViewModel.kt`、`ui/webdav/WebDavPlayerViewModel.kt`、`ui/local/LocalPlayerViewModel.kt`、
  `ui/link/LinkPlayerViewModel.kt`、`ui/iptv/IptvPlayerViewModel.kt`
  - `init` 里先 `playerManager.beginLoading()` 再开始收集（新页面一创建就接管共享播放器，第一帧就不会带出上一个条目）；
  - 收集器改为 `p.title.orEmpty()` / `p.subtitle.orEmpty()`：`PlayerManager` 是标题/副标题的唯一来源，清空能真正反映到界面；
  - `play()` 在任何异步准备之前调用 `beginLoading(title = 本条目名, subtitle = …)`
    （WebDAV 用文件名、本地用 SAF 名、链接用 URL 末段、IPTV 用频道名），
    并取代原来的 `clearError()`——`beginLoading()` 本身会重置 error，旧错误也不可能先于本次尝试出现。
- `player/PlayerManager.kt`：给 `beginLoading()` 补上文档，写明「单例状态 + 页面接管即重置」的所有权约定，避免复发。

### 实测（emulator-5554 + 本地 WebDAV）
- 修复前（stash 掉上述改动后重编同一条用例）：A = `.m3u8sp`（`The.Matrix.1999.1080p.mkv`，播放中）→ 退出 →
  B = `broken.m3u8s`：顶栏 `The.Matrix.1999.1080p.mkv | LocalDAV`，进度 `00:08 / 00:24`，全是 A 的信息。
- 修复后同一操作序列：B 界面显示 `broken.m3u8s`、`00:00 / 00:00` 与 B 自己的失败文案，无任何 A 残留。
- 回归：`.m3u8s` 目录包与 `.m3u8sp` 均正常起播并显示解密恢复的原始文件名；WebDAV 返回栈 `c→b→a→root` 不受影响；
  `testDebugUnitTest` / `assembleDebug` / `lintDebug` 通过。

---

## 2026-09-12 首页「每日推荐」没有切换动画 / 「继续观看」与推荐位风格不一致

### 现象 / 需求
1. 「每日推荐」大图每 10 秒换一部片子，但内容是瞬间跳变的：`SuggestedHero` 只保存一个 `index`，
   换片时标题、简介、海报同时硬切，没有任何过渡动画；
2. 「继续观看」是一条 268dp 的横向小卡片列表（`LazyRow`），与上面的电影级大图推荐位风格完全脱节。
   经确认按「完全同款」处理：继续观看也改成自动轮播大图 + 圆点指示 + 断点进度条。

### 修改（`ui/home/HomeScreen.kt`）
- 新增共用的 `PosterHeroCarousel`，两个模块都用它，`SuggestedHero` / `ContinueWatchingHero` 只负责各自的叠加内容：
  - `HorizontalPager` + `graphicsLayer`（`scaleX/Y 0.90→1.0`、`alpha 0.45→1.0`，按 `currentPageOffsetFraction` 插值），
    换片时新海报滑入并放大/提亮，旧海报退出，替代原来的瞬间跳变；
  - 自动轮播改为「空闲计时」：每 `HERO_TICK_MS`(250ms) 累加，`isScrollInProgress` 期间直接跳过，
    并且任何一次落页（自己播到的或用户手滑的）都把计时清零 → 手动滑动后重新计满 10 秒，绝不打架；
    列表只有一项时不起定时器；
  - 到片尾不回头（`step` 触底反向 ping-pong），避免「倒着飞回第一页」的观感；
  - 右下角圆点用 `animateDpAsState`/`animateFloatAsState` 做胶囊宽度和透明度过渡（最多 8 个，仅 `count > 1` 时显示）；
  - `showResumeProgress` 时底部叠 4dp 观看进度条（`animateFloatAsState`，主题主色），
    并在 meta 行加 `NN%` 徽章，保留旧卡片列表里的百分比信息；
  - 点海报进详情页，动作按钮（播放 / 继续）进播放页；`firstAdvanceDelayMillis` 让两个轮播错开半个周期，不会同帧跳。
- 顺带修掉一个既有显示缺陷：推荐位对 `Series` 条目会把同一个名字打印两行
  （`itemName` 只判断 `!= seriesName`，而 Series 的 `seriesName` 为空）——现在只有在确实属于某个剧集时才打印第二行；
  `HeroMetaRow` 也不再因为缺少 ★/年份/时长就把分级和百分比徽章一起跳过。
- 删除已不用的 `ContinueWatchingRail`、`LazyRow` / `PagerState` 导入；字符串全部复用现有资源
  （`play` / `resume` / `continue_watching`），无新增文案。

### 实测（emulator-5554 + 本地假 Emby 服务器 8099，8 条 Suggestions + 8 条 IsResumable）
- 自动轮播：每 ~10.7s 换一部；两个轮播分别在 6.2/16.7/27.4/38.2/48.9s 与 13.2/23.8/34.5/45.2s 触发，永不同帧。
- 手动滑动：左右滑立即换页；滑动后 10 秒内不再自动跳（计时已清零重算），10 秒后恢复自动轮播。
- 过渡确为多帧动画而非瞬间跳变：静止时 1 秒内渲染 0 帧，包含换片的 1.2 秒窗口内渲染 13 帧（`dumpsys gfxinfo`）。
- 交互：点海报进详情（标题/元信息/简介/类型正常）→ 返回；「播放」「继续」都进播放页；返回后仍在首页。
- 继续观看显示片名 + 剧集名 + `第 N 季 第 N 集` + `TV-MA`/`34%` 徽章 + 底部进度条。
- 回归：WebDAV 返回栈 `c→b→a→root`、`.m3u8s` 目录包与 `.m3u8sp` 起播、播放页无上一条残留均不受影响；
  `testDebugUnitTest` / `assembleDebug` / `lintDebug` 通过，`lint-results-debug.html` 未涉及本文件。

---

## 2026-09-13 真机安装提示「没有证书 / 未签名」

### 现象
用官方 Android Debug 证书编出的 `app-debug.apk`，模拟器 `adb install` 正常，
但拷到真机（文件管理器 / 侧载工具）安装时报「不包含证书」「未签名」。

### 根因
`minSdk = 26`，而 AGP 8 在 `minSdk >= 24` 时默认**不写 v1（JAR）签名块**，产物只有 APK Signature Scheme v2/v3。
`unzip -l` 里看不到 `META-INF/MANIFEST.MF`、`CERT.SF`、`CERT.RSA`。
不少真机安装路径（OEM 自带文件管理器、部分侧载/第三方安装器、某些 `pm install` 分支）
先读 JAR 签名块，读不到就判定为无证书；v2/v3 只在系统安装器路径下生效，所以模拟器看不出问题。

### 修改
- `app/build.gradle.kts`：给 debug buildType 显式打开三种方案，v1/v2/v3 同时写，仍然只用共享的 debug key：
  ```kotlin
  getByName("debug") {
      signingConfig?.apply {
          enableV1Signing = true
          enableV2Signing = true
          enableV3Signing = true
          enableV4Signing = false   // v4 需要旁挂 .idsig，独立安装包用不到
      }
  }
  ```
  （`enableV1Signing` 等是 AGP 8.x `ApkSigningConfig` 上的可空 Boolean 属性，会覆盖 minSdk 推断。）
- 不改 minSdk、不换证书、不影响 release 签名流程。

### 实测
- 干净重编（删 `app/build` + `--offline assembleDebug`）后 APK 内含 `MANIFEST.MF`(498 条摘要) / `CERT.SF`(498) / `CERT.RSA`；
- `apksigner verify --min-sdk-version 18 --max-sdk-version 23`（强制走只认 v1 的路径）→ `Verified using v1 scheme (JAR signing): true`；
- `jarsigner -verify -strict` → 「jar 已验证」，唯一提示是预期的自签证书链告警；
- `apksigner verify --print-certs` 默认路径 → v2 true、v3 true，证书仍是
  `C=US, O=Android, CN=Android Debug`（SHA-256 `cb18b2f4…4fca3561`，与 `~/.android/debug.keystore` 一致）；
- `zipalign -c 4` 通过；emulator-5554 `install -r` 成功、启动无 `FATAL EXCEPTION`、界面正常。
- 唯一 apksigner 警告是 `META-INF/services/*` 不受 JAR 签名保护——JVM 元数据条目的固有提示，Android 不加载，非缺陷。
