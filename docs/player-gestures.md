# 播放器手势（横滑进度 / 双击暂停 / 长按倍速 / 亮度与音量）

## 功能

五个视频入口（Emby/Jellyfin、WebDAV、本地、链接播放、IPTV）在画面上共用同一套手势，实现集中在
`ui/player/PlayerGestures.kt`：

| 手势 | 行为 |
| --- | --- |
| 单击画面 | 显示 / 隐藏控制条 |
| 双击画面任意位置 | 暂停 / 继续播放 |
| 长按画面 | 倍速播放（默认 2x），松手恢复到原来的倍速 |
| 在画面上向右 / 向左滑动 | 预览并前进 / 后退视频进度，松手时跳转 |
| 左半区域上下滑动 | 调节亮度（上亮下暗） |
| 右半区域上下滑动 | 调节播放器音量（上大下小） |

手势反馈是半透明卡片：亮度/音量显示图标、百分比和进度条，倍速显示倍率；横滑显示方向、相对时长、目标时间和进度条。长按触发时提供轻微触感反馈。倍速提示先在中央出现，手指持续按压满 2 秒后，平滑移到右下方，以免持续遮挡视频中央；松手即隐藏。

播放器左侧在控制条显示时提供锁定按钮。锁定后仅保留左侧解锁按钮：画面点击、双击、长按、亮度/音量滑动、进度条等播放控件和系统返回操作均不响应；播放继续。点击解锁按钮后恢复控制条与手势。锁定状态在屏幕旋转后保留，离开播放页后重置。

## 实现要点

### 手势层放在控件之下

每个播放页使用透明的 `Box(Modifier.matchParentSize().playerGestureSurface(...))`，插在
`AndroidView(PlayerView)` 之后、控制条之前。控制条按钮、进度条和选择器优先拿到触控；手势循环在
`PointerEventPass.Final` 观察事件，一旦触控被控件消费就放弃本次手势。
锁定时手势层和控制条立即移除，共用的 `PlayerScreenLock` 在视频表面之上消费其余触控，并保留解锁按钮。

### 单个 pointerInput 循环

双击、长按、横滑和竖滑都在同一个 `awaitEachGesture` 中判定：

- 竖向位移超过 `ViewConfiguration.touchSlop` 且 `|dy| > |dx|` 后才开始亮度或音量调节；
- 横向位移先超过 slop 时开始预览跳转，不触发其他手势；
- 按下后达到 `longPressTimeoutMillis` 才启动临时倍速；
- 抬手后在 `doubleTapTimeoutMillis` 内等待第二次点击，单击会因此延迟一个双击判定窗口。

长按倍速通过 `PlayerManager.beginTemporarySpeed()` / `endTemporarySpeed()` 管理，松手或离开页面时恢复原倍速。
2 秒计时从手指按下开始，由同一个 pointer 循环等待超时；只切换提示位置，不重复设置播放倍速。提示卡片在共用 `PlayerGestureOverlay` 内使用 Compose 动画移动，距离底边保留 72 dp，避免贴住屏幕边缘。

### 横滑跳转

以手势开始时的播放位置为基准，向右前进、向左后退；整屏宽度对应视频时长的 10%，最少 30 秒、最多 5 分钟，并将目标时间限制在视频开头和结尾之间。拖动中只更新预览，松手后调用一次 `seekTo()`，避免反复请求播放源。已有进度条仍可单独拖动。无固定时长的直播流不响应横滑跳转。

### 相对亮度和音量

亮度和音量以拖动开始时捕获的值为基准，按相对垂直位移增减，不再用手指落点直接决定数值，因此落点不会造成“一按就跳”。
窗口亮度下限为 8%，滑到顶端时恢复系统亮度；退出播放页会恢复窗口属性。播放器音量是自身的 0..1 音量，不修改系统媒体音量。

## 已知取舍

- 单击切换控制条有一个双击判定延迟。
- 亮度只作用于本 App 窗口，不修改系统亮度设置。
- IPTV 等直播流没有固定时长时，横滑不触发跳转。

## 官方参考

- Compose pointer input / `PointerEventPass`: <https://developer.android.com/develop/ui/compose/touchinput/pointerinput>
- Compose 手势事件与消费：<https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures>
- Compose 动画位置：<https://developer.android.com/develop/ui/compose/animation/quick-guide>
- `ViewConfiguration`: <https://developer.android.com/reference/android/view/ViewConfiguration>
- Media3 `Player.seekTo()` 与未知时长：<https://developer.android.com/reference/androidx/media3/common/Player>
- 窗口亮度 `WindowManager.LayoutParams.screenBrightness`: <https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness>

## 验证

```text
./gradlew assembleDebug lint testDebugUnitTest
```
