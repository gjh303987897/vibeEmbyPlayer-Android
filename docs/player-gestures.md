# 播放器手势（双击暂停 / 长按倍速 / 亮度与音量）

## 功能

五个视频入口（Emby/Jellyfin、WebDAV、本地、链接播放、IPTV）在画面上共用同一套手势，实现集中在
`ui/player/PlayerGestures.kt`：

| 手势 | 行为 |
| --- | --- |
| 单击画面 | 显示 / 隐藏控制条 |
| 双击画面任意位置 | 暂停 / 继续播放 |
| 长按画面 | 倍速播放（默认 2x），松手恢复到原来的倍速 |
| 左半区域上下滑动 | 调节亮度（上亮下暗） |
| 右半区域上下滑动 | 调节播放器音量（上大下小） |

手势反馈是画面中央的半透明卡片：亮度/音量显示图标、百分比和进度条，倍速显示倍率；长按触发时提供轻微触感反馈。

## 实现要点

### 手势层放在控件之下

每个播放页使用透明的 `Box(Modifier.matchParentSize().playerGestureSurface(...))`，插在
`AndroidView(PlayerView)` 之后、控制条之前。控制条按钮、进度条和选择器优先拿到触控；手势循环在
`PointerEventPass.Final` 观察事件，一旦触控被控件消费就放弃本次手势。

### 单个 pointerInput 循环

双击、长按和竖滑都在同一个 `awaitEachGesture` 中判定：

- 竖向位移超过 `ViewConfiguration.touchSlop` 且 `|dy| > |dx|` 后才开始亮度或音量调节；
- 横向位移先超过 slop 时交给进度条，不触发其他手势；
- 按下后达到 `longPressTimeoutMillis` 才启动临时倍速；
- 抬手后在 `doubleTapTimeoutMillis` 内等待第二次点击，单击会因此延迟一个双击判定窗口。

长按倍速通过 `PlayerManager.beginTemporarySpeed()` / `endTemporarySpeed()` 管理，松手或离开页面时恢复原倍速。

### 相对亮度和音量

亮度和音量以拖动开始时捕获的值为基准，按相对垂直位移增减，不再用手指落点直接决定数值，因此落点不会造成“一按就跳”。
窗口亮度下限为 8%，滑到顶端时恢复系统亮度；退出播放页会恢复窗口属性。播放器音量是自身的 0..1 音量，不修改系统媒体音量。

## 已知取舍

- 单击切换控制条有一个双击判定延迟。
- 亮度只作用于本 App 窗口，不修改系统亮度设置。
- IPTV 等直播流没有时长，横向移动仍交给进度条逻辑处理。

## 官方参考

- Compose pointer input / `PointerEventPass`: <https://developer.android.com/develop/ui/compose/touchinput/pointerinput>
- `ViewConfiguration`: <https://developer.android.com/reference/android/view/ViewConfiguration>
- 窗口亮度 `WindowManager.LayoutParams.screenBrightness`: <https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness>

## 验证

```text
./gradlew :app:compileDebugKotlin :app:lintDebug
```
