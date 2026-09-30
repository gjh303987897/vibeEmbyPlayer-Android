# 播放历史列表与重播

历史页沿用 Qt 版 `VIBEDOCS/GlobalPlaybackHistory.md` 的统一历史语义：按日期分组，展示来源、服务、标题、进度与重播操作。Android 端用 Material 3 卡片展示每项；未知时长显示 `--:--`，不可重播的记录禁用重播按钮。

## 重播路由

历史库中的 `replayTarget` 含义随来源变化，必须交给对应播放器：

| 来源 | 保存的目标 | 路由 |
| --- | --- | --- |
| Emby / Jellyfin | 媒体项 ID | `Routes.player` |
| WebDAV | 文件路径 | `Routes.webdavPlayer` |
| IPTV | 频道流地址 | `Routes.iptvPlayer` |
| 本地媒体 | 内容 URI | `Routes.localPlayer` |
| 链接播放 | URL | `Routes.linkPlayer` |

WebDAV 路径与 IPTV URL 会由各自的路由方法编码成安全的单段参数。此前历史页将 WebDAV、IPTV 也送进 Emby/Jellyfin 路由，包含斜杠的目标会使导航参数不匹配，点击重播可能闪退。现在路由选择集中在 `HistoryReplayRoute.kt`，缺少目标、服务 ID 或标记为不可用的记录没有重播路由。

Emby/Jellyfin 播放页使用历史项中的服务 ID。当前内存会话与该 ID 不一致或尚未建立时，从安全存储恢复该服务的会话；无法恢复时在播放器显示错误。

## 验证

`HistoryReplayRouteTest` 覆盖六种来源及不可重播记录。构建和静态检查命令：

```text
./gradlew assembleDebug lint testDebugUnitTest
```

界面实现参考 [Android Material 3 Card](https://developer.android.com/develop/ui/compose/components/card) 与 [Compose 列表](https://developer.android.com/develop/ui/compose/lists)。
