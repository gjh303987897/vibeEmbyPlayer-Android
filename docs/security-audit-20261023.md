# 第三方安全审计复核与修复

复核基线为 `12dd890`，第三方报告基线 `4394cd2` 加 seek 工作区改动已包含在其中。
六项报告均有实际代码依据。报告的风险级别不等同于远程可无条件利用：PIN/排序问题需要
应用内操作，环回端口问题需要同机其他进程和有效播放会话，重定向问题需要服务端或链路
提供恶意目标。允许用户指定 HTTP 媒体源属于兼容性需求；缺失授权和凭据边界才是本轮修复点。

## 逐项处理

| 报告项 | 修复与边界 |
| --- | --- |
| 1：PIN 可覆盖、明文输入 | `setPin` 只允许首次设置；`changePin` 必须验证原 PIN，并沿用失败次数及冷却。写入成功才报告成功，不自动解锁。设置页遮蔽新旧 PIN，计算与写入放到 IO 调度器。 |
| 2：排序删除私密服务 | DataStore 的一次原子 `edit` 基于最新完整列表，仅替换本次选择的服务槽位。隐藏、并发新建和已编辑的配置均保留，过时/重复 ID 不会删除配置。新增、删除同样在原子事务中完成，避免与排序互相覆盖。 |
| 3：Link 丢失隐私身份 | 路由、队列、历史、续播和统计携带实际服务 ID。播放前检查服务和隐私锁，回锁取消加载并停止播放。相同 URL 在不同服务中保留独立记录。Link 首页跟随回锁清除输入与列表；历史回放再次检查锁，播放器检查服务状态。私密播放统一设置 `privatePlayback`，禁止 PiP，并从通知及 MediaSession 隐去标题。 |
| 4：TSSL 环回服务匿名访问 | 每个 `EncryptedHlsServer` 使用独立随机令牌路径前缀，根清单、子清单、分片、字幕、HEAD/Range 共用该边界，并校验 Host。未提供/猜测/其他会话的令牌返回 404，不读取或解密源数据。清单内验证过的相对 URI 自然继承前缀；关闭和 seek 取消逻辑保持不变。 |
| 5：重定向泄露凭据 | 公共 OkHttp 客户端记录初始 scheme/host/port，对每个网络请求（含重定向、认证重试）检查边界。跨源残留 token、Cookie、Authorization 或请求体会被拒绝，HTTPS 降级会被拒绝。Digest 只响应配置源的挑战；跨源字幕不附加媒体服务器凭据。Coil 使用相同工厂。播放代理先移除跨源请求头，再执行公共边界检查，允许匿名 CDN GET。 |
| 6：HTTP 默认值与密钥备份 | 新建服务默认 HTTPS；旧无 scheme 地址不再被静默补成 HTTP，需要编辑确认。远程 HTTP 登录/带凭据请求默认拒绝，只有用户在该服务编辑器确认明文风险后才允许；改变地址会清除授权。旧配置默认为未授权。TSSL WebDAV 备份/恢复及旧单文件上传 API 在业务和传输层均要求 HTTPS，不受 HTTP 授权豁免。 |

Android XML 仍保留动态媒体源所需的 cleartext 平台许可，不能据此宣称全局禁用了 HTTP。
实际凭据限制在公共传输层执行；IPTV、用户明确输入的 HTTP 公共链接、环回播放仍兼容。
HTTP 授权不提供加密保护；获准发送的明文凭据仍可被监听，因此 HTTPS 仍是推荐配置。
S3 原有 HTTPS 要求和仅供本机开发的 localhost 例外保持原样。

## 旧 Link 数据迁移

旧版统一 Link 历史的 `service_id` 为空，无法判断其源服务是否私密，不能可靠还原来源。
Room 3→4 保留这些记录和进度，统一归到 `legacy-link-playback` 并标为私密；镜像历史同样
隐藏。旧内置 Link 流量按日期汇总后转为私密，合并时保留两个隐私分区的总量。
这部分历史可在解锁后的全局历史中回放，合成旧来源始终要求隐私解锁。新记录使用真实来源。
本次升级没有破坏性数据库回退，也不会删除历史或服务。

## 验证

- `CredentialBoundaryTest`：真实 MockWebServer 验证跨端口 token/Cookie、307/308
  请求体、同源重定向、公共 CDN GET、Digest 同源与跨源挑战、远程 HTTP 授权。
  使用本机 HTTPS 服务验证 HTTPS→HTTP 降级被拒绝、自签名未授权时失败，以及授权后
  仍拒绝证书主机名不匹配的请求。两个 TLS 模式保留 OkHttp 默认的主机名校验器。
- `ServiceOrderTest`：可见子集排序、隐藏/新增服务、最新配置、过时/重复 ID。
- `EncryptedHlsAccessTest`：无令牌、错误/跨会话令牌、错误 Host、合法清单与 Range、路径逃逸。
  原有慢读取取消测试使用带令牌地址，继续检查 seek 关闭旧连接并可建立后续请求。
- `HistoryReplayRouteTest`：私密记录回锁后不能生成回放路由，Link 路由携带来源身份。
- `PrivacyPinTest`、`LinkPrivacyStorageTest` 为设备测试：真实加密 PIN 存储、原 PIN 校验、冷却，
  以及不同 Link 来源的同 URL 记录和续播隔离。本轮按用户要求不运行实机，仅检查编译。
- 原有 JVM/Android WebDAV 备份流式传输及取消测试改为 HTTPS；HTTP/1.1 分块流仍逐字节核对，
  大小上限检查保留。TLS 样本是公开的合成 localhost 测试密钥，仅属于测试资源，
  不进入应用正式 APK。新增测试明确检查即使服务已授权 HTTP，TSSL 备份也不会发出明文请求。
- 本地 SQLite 验证直接提取 Kotlin 迁移 SQL，在版本 3 导出 schema 上执行，检查旧 Link
  隐藏、公开/私密流量合并、其他来源保留、完整性以及版本 4 schema 不变。

2026-10-24 本地验证完成：`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、
`:app:testDebugUnitTest`（164 项，0 失败、0 跳过）及 `:app:lint` 均通过，lint 为
0 error、3 项既有 warning。SQLite 迁移检查通过，版本 3/4 的实体 schema 相同。
未进行实机测试，不将 PIN/Room 设备测试的编译通过描述为设备验证成功。
日志与迁移检查脚本位于忽略目录 `build/security-audit-20261023/`，主要文件为
`final-local-check.log`、`migration-check.log`、`verify_migration.py`。

## 官方资料与 Qt 参考

- [Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)
- [Android 密码文本转换](https://developer.android.com/develop/ui/compose/text/user-input)
- [Room 数据迁移](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [OkHttp 4.12.0 重定向实现](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)
- [Coil 2.7.0 Call.Factory 接口](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/ImageLoader.kt)
- [HTTP Digest RFC 7616](https://www.rfc-editor.org/rfc/rfc7616.html)
- Qt `VIBEDOCS/LinkPlayback.md`、`TsslBackup.md`、`WebDAV.md`、`EmbyJellyfinApi.md`；
  `src/services/link/LinkPlaybackService.cpp` 的 URL/凭据边界和 `ServiceCardListModel.cpp` 的私密标记。
