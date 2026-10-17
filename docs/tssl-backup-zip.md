# TSSL 压缩备份与恢复

Android 对齐 Qt `35e9b5e` 的 `TSSL-BACKUP` v1 格式。原有 TSSL v2/v3/v4 文档格式和媒体加密方式不变，新增的是保存多个密钥包的 ZIP 容器。

## 使用方式

在「TSSL 管理」的备份设置中选择本地文件夹、WebDAV 或 S3，再选择备份格式：

- **ZIP（推荐，默认）**：每次备份生成一个唯一命名的 `tssl-backup-<UTC 时间>-<UUID>.zip` 全量快照，不删除此前的备份。
- **独立文件**：按 `<rootManifestSha256>.tssl` 保存，供旧客户端直接恢复。

「从备份恢复」自动读取目标文件夹中的新 ZIP 和旧 `.tssl`，格式选项仅影响写入。无关 ZIP 不会被扫描。「导入/恢复」也支持通过系统文件选择器直接选择一个 ZIP 或 `.tssl`，按文件内容识别，不依赖提供方的 MIME 类型。

本地文件夹使用 Android Storage Access Framework，保存系统授予的持久 URI 权限，无需申请全部文件访问权限。若提供方权限失效，需要重新选择文件夹。已有同名散装文件只有内容完全相同时才跳过；内容不同时报错并保留旧文件。新 ZIP 使用唯一文件名，失败时删除本次未写完的新文件。

恢复按根 manifest 摘要去重，已有包不覆盖。一个 ZIP 必须整体校验成功，才开始将其中的包导入本机；损坏 ZIP 计为一个失败项，并继续处理其他备份。用户取消时清理临时数据，已成功导入本机的包保留。界面显示当前阶段并提供取消操作。

## ZIP 合约与边界

ZIP 只允许根目录下的 `manifest.json` 和小写 64 位十六进制摘要命名的 `.tssl` 文件，支持 Stored（0）和 Deflate（8）；写入使用 Deflate 级别 3。

```json
{
  "format": "TSSL-BACKUP",
  "version": 1,
  "count": 1,
  "createdAt": "2026-10-17T00:00:00Z",
  "packages": [{
    "file": "<rootManifestSha256>.tssl",
    "rootManifestSha256": "<64 lowercase hex>",
    "sha256": "<SHA-256 of the original TSSL bytes>",
    "size": 12345
  }]
}
```

TSSL 按原始字节保存，保留未知字段、空白及 v4 容器绑定；不通过 JSON 重序列化后备份。恢复校验 ZIP CRC、中央目录与本地头一致性、清单计数、文件名、根摘要、字节长度、SHA-256 以及完整 TSSL schema。

| 资源 | 上限 |
| --- | --- |
| 单个 `.tssl` | 64 MiB |
| ZIP 文件 | 2 GiB |
| ZIP 全部展开内容（含清单） | 2 GiB |
| 密钥包数量 | 10,000（另加一个清单） |
| 清单 JSON | 4 MiB |
| ZIP 中央目录 | 8 MiB |
| 单次远程列表响应 | 4 MiB |

在打开 `ZipFile` 之前检查中央目录边界，拒绝 ZIP64、多卷、路径穿越、子目录、链接、设备、加密条目、未知条目、重复文件名及不支持的压缩方式。ZIP 本身不增加密码加密，所含密钥与散装 TSSL 具有同等敏感性。

网络上传/下载使用临时文件流式传输，不将整个 ZIP 放进内存。WebDAV 使用原有 Basic/Digest 认证与按服务器选择的 TLS 策略；S3 对文件流计算 SHA-256 并沿用 SigV4 签名。传输空闲读写超时 60 秒，单次请求总超时 15 分钟。取消会中止 OkHttp 请求，并等流关闭后清理私有缓存。

## 实现与验证

- `TsslBackupArchive`：文件级压缩、校验、暂存与无覆盖发布。
- `TsslImportWorkflow`：单文件/ZIP、本地/远程统一导入及计数。
- `TsslBackupService`：备份目标、混合发现、流式传输和取消。
- `TsslLocalBackup`：SAF 文件夹及单文件读写。
- `TsslBackupSettingsStore`：格式与本地文件夹设置；S3 secret 继续保存在 Keystore 中。

JVM 回归覆盖真实 Qt/miniz ZIP、v2/v3/v4 原样往返、Stored/Deflate、混合恢复、摘要与 CRC 错误、危险 ZIP 条目、元数据限额、取消清理、并发去重、流式 HTTP、Digest 重放和挂起下载取消。真实 Qt 样本来源见 `app/src/test/resources/tssl/README.md`。

双向 CLI 验证已通过：真实 Qt 生成的快照可由 Android 模块恢复；Android 生成的 ZIP 交给真实 Qt 客户端导入，TSSL v2/v3/v4 共 3 包全部成功（失败 0）。重复导入时跳过 3 包、失败 0。Qt CLI 的数据目录独立放在 Android 的 `build/tssl-compat/` 下，未使用桌面端真实密钥库。

验证命令：

```text
./gradlew :app:assembleDebug :app:lint :app:testDebugUnitTest
```

## 2026-10-17 实机验证

设备为华为 TAS-AL00，Android 12 / API 31。使用独立包名 `com.vibeplayer.app.regression`（「VibePlayer 修复验证」）和合成密钥；自动化用例使用独立私有临时存储，不清空已安装应用。

6 项 instrumentation 用例通过：

| 用例 | 结果 |
| --- | --- |
| Android 原生 JSON/ZIP 运行时读取 Qt ZIP、v2/v3/v4 原样往返、重复导入 | 通过 |
| ZIP 第二个包摘要损坏：整包不导入，继续处理其他有效文件 | 通过 |
| WebDAV 混合恢复、显示名与 href 不同、ZIP 流式上传 | 通过 |
| S3 分页与特殊字符 continuation token、混合恢复、上传负载摘要 | 通过 |
| 取消挂起的 WebDAV 下载，关闭流并清理临时文件 | 通过 |
| 真实 SAF 文件夹的 ZIP/散装备份、Qt 混合恢复、重复跳过、坏包与同名文件保护 | 通过 |

SAF 权限通过真实系统文件夹选择器授予，测试使用专用空目录。测试结束会删除该用例创建的文档，不删除输入密钥包。WebDAV/S3 用例的服务端是运行在手机上的 MockWebServer，覆盖 Android 网络与 XML 实现；没有连接真实云端账户，不能替代实际服务商兼容性测试。

从手机取回的运行时、WebDAV 上传、S3 上传、SAF 保存及手工界面备份的 5 个 ZIP，全部通过真实 Qt CLI 恢复（分别为 3、2、3、3、2 包，失败均为 0）。

真实界面另外验证了系统文件选择器导入 Qt ZIP（成功 1）、重复导入（已存在 1）、损坏 ZIP（失败 1 且没有部分导入）、旧 `.tssl` 导入、本地 ZIP 备份和恢复，以及系统返回后配置与包数量保持稳定。实机发现的备份目标文字截断已改为 Material 3 下拉选择；本地恢复按钮改为「从备份恢复」。

最终普通包名构建、lint 和 80 项 JVM 测试通过；lint 为 0 错误、3 项已有警告。本次临时启用的「USB 连接时保持亮屏」已恢复为原值 0。

实机测试命令（独立验证包，可使用相同源码另行构建）：

```text
adb install --user 0 -r -t <verification.apk>
adb install --user 0 -r -t <verification-test.apk>
adb shell am instrument --user 0 -w -r -e class com.vibeplayer.app.tssl.TsslDeviceInteropTest com.vibeplayer.app.regression.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument --user 0 -w -r -e class com.vibeplayer.app.tssl.TsslSafDeviceTest -e tsslTreeUri <system-picker-granted-empty-folder-uri> com.vibeplayer.app.regression.test/androidx.test.runner.AndroidJUnitRunner
```

多用户手机需让应用和测试组件安装在同一用户空间；不要依赖未指定用户的安装命令来选择当前机主空间。没有真实授权 URI 或专用空文件夹时，SAF 用例会跳过。实机截图、手机生成的合成 ZIP 和本次调试产物位于忽略的 `build/tssl-device/`，不包含真实媒体密钥或云端凭据。

## 参考

- Qt：`D:/workspace/vibeEmbyPlayerQT/VIBEDOCS/TsslBackupArchive.md`、`TsslBackup.md`、`TsslImportWorkflow.md` 及 `src/services/backup/`。
- [Android ZIP API](https://developer.android.com/reference/java/util/zip/ZipFile)
- [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files)
- [RFC 4918 WebDAV](https://www.rfc-editor.org/rfc/rfc4918.html)
- [S3 SigV4 Authorization](https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-auth-using-authorization-header.html)
