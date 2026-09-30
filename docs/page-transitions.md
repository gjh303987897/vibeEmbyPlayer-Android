# 页面转场

Android 导航转场集中在 `VibePlayerNavHost.kt`，由设置页的“页面转场”开关控制。保留 Qt 版 `VIBEDOCS/SettingsAppearance.md` 中短暂、有方向感、可关闭的行为，同时按移动端页面类型处理：

| 切换 | 效果 |
| --- | --- |
| 底部标签之间 | 短暂淡出，再淡入新标签内容 |
| 进入浏览、搜索、详情等内容页 | 新页面从右侧轻微进入，旧页面略向左退出 |
| 返回上一级 | 与进入方向相反 |
| 进入或离开播放器 | 即时切换，避免移动原生视频画面 |

转场时长约 220–260 毫秒；关闭“页面转场”后四种导航过渡均为 `None`。动画只作用于导航容器，不改变页面状态、播放器控制或返回栈。

实现依据：[Navigation Compose 的转场参数](https://developer.android.com/reference/kotlin/androidx/navigation/compose/package-summary) 和 [Compose 动画指南](https://developer.android.com/develop/ui/compose/animation/quick-guide)。
