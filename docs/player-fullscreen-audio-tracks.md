# 播放器全屏与音轨切换

## 功能

Android 的所有视频入口（Emby/Jellyfin、WebDAV、本地、链接播放和 IPTV）共用以下能力：

- 播放器顶部提供全屏按钮；再次点击退出全屏。
- 全屏通过 `WindowInsetsControllerCompat` 隐藏状态栏与导航栏，并允许边缘滑动临时显示系统栏。
- 离开播放器页面时强制恢复系统栏，避免影响应用其他页面。
- 当媒体包含两个或更多受支持音轨时显示音轨按钮。
- 音轨对话框展示 Media3 当前发现的受支持音轨和当前选择；点击条目通过 `TrackSelectionOverride` 立即切换。
- 开始播放新媒体时清除上一媒体的音轨覆盖，让 Media3 重新执行默认音轨选择，避免跨视频继承错误选择。

音轨标题优先使用容器内的 label，其次使用语言，均缺失时显示 `Audio N`。

## 官方参考

- Android immersive mode: <https://developer.android.com/develop/ui/views/layout/immersive>
- Media3 track selection: <https://developer.android.com/media/media3/exoplayer/track-selection>

## 验证

```text
./gradlew testDebugUnitTest assembleDebug lint
```

手工验证需要使用至少包含两个音轨的视频，分别从 Emby/Jellyfin 与一个非服务器来源打开：确认音轨列表、当前项标记和实际声音切换；确认全屏进入、边缘临时系统栏、退出全屏和返回页面后的系统栏恢复。
