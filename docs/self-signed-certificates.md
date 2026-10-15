# Emby / Jellyfin 自签发证书信任选项

## 行为

- 添加或编辑 Emby、Jellyfin（以及共用服务表单的 WebDAV）服务时，可显式启用“信任自签发证书”。
- 该设置按服务保存在 `ServerConfig.trustSelfSignedCertificate` 中，默认关闭；升级前保存的服务也按关闭处理。
- 关闭时，API 请求和 IJK 播放的远程请求均使用 Android/OkHttp 的标准证书链与主机名验证。
- 开启时，仅该服务的 API 请求及其播放流使用独立的证书链客户端；其他服务不受影响。
- 播放通过 `PlaybackHttpProxy` 将 OkHttp 响应流送至 IJK；HLS URI 与 DASH BaseURL/绝对资源 URL 重写到环回地址，Range 和 HEAD 请求转发到上游。
- 不依赖原生 FFmpeg 的 `tls_verify` 选项；服务信任策略沿用同一个 `OkHttpClientFactory`。

## 安全说明

此兼容选项接受自签发证书链，但仍保留主机名验证；它不等同于导入并固定信任某一张证书。界面会显示风险警告，用户只应为自己控制的内网服务器启用。推荐方案仍是为服务器配置受信任证书，或将私有 CA 安装到受控设备。

实现参考 Android 官方 Network Security Configuration 文档：
<https://developer.android.com/privacy-and-security/security-config>

## 验证

- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- `./gradlew lint`
- 手工验证：分别添加默认与启用选项的 Emby/Jellyfin 服务，确认默认服务拒绝无效证书，启用项可登录、浏览并播放，同时另一服务仍保持严格验证。
