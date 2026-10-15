# 播放器全屏与音轨切换

## 功能

Android 的所有视频入口（Emby/Jellyfin、WebDAV、本地、链接播放和 IPTV）共用以下能力：

- 播放器顶部提供全屏按钮；再次点击退出全屏。
- 全屏通过 `WindowInsetsControllerCompat` 隐藏状态栏与导航栏，并允许边缘滑动临时显示系统栏。
- 离开播放器页面时强制恢复系统栏，避免影响应用其他页面。
- 当媒体包含两个或更多受支持音轨时显示音轨按钮。
- 音轨对话框展示 IJK 发现的音轨和当前选择；点击条目通过 `selectTrack()` 切换。
- 开始播放新媒体时重置 IJK 原生实例，让新媒体重新执行默认音轨选择；同时重新绑定视频 Surface。
- 所有入口提供字幕选择；内嵌文本及 PGS/DVD/DVB 图片字幕、媒体服务器外挂字幕共用同一选择状态。
- 图片字幕按字幕画布映射到实际视频区域，随横竖屏、画面宽高比与黑边调整；seek、换轨、关闭字幕、停止或重置时清除旧字幕。
- 播放前申请音频焦点；临时丢失后可恢复，用户暂停、停止或耳机断开会取消自动恢复。

音轨标题优先使用容器内的 `title`，其次使用语言，再回退到 IJK codec 名称与 `Audio`。

## 官方参考

- Android immersive mode: <https://developer.android.com/develop/ui/views/layout/immersive>
- IJK track API: <https://github.com/bilibili/ijkplayer/blob/k0.8.8/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java>

## 验证

```text
./gradlew testDebugUnitTest assembleDebug lint
```

可选手工检查：使用至少包含两个音轨的视频，分别从 Emby/Jellyfin 与一个非服务器来源打开，确认音轨切换和全屏行为；用 PGS/DVD/DVB 字幕检查定位、换轨及 seek 清屏。此编码交付不以设备执行为完成条件。
