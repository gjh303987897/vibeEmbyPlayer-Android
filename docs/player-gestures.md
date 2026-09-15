# 播放器手势（双击暂停 / 长按倍速 / 亮度与音量）

## 功能

五个视频入口（Emby/Jellyfin、WebDAV、本地、链接播放、IPTV）在画面上共用同一套手势，实现集中在
`ui/player/PlayerGestures.kt`：

| 手势 | 行为 |
| --- | --- |
| 单击画面 | 显示 / 隐藏控制条（保留原有行为） |
| 双击画面任意位置 | 暂停 / 继续播放 |
| 长按画面 | 倍速播放（默认 2x），松手恢复到原来的倍速 |
| 左半区域上下滑动 | 调节亮度（上亮下暗） |
| 右半区域上下滑动 | 调节播放器音量（上大下小） |

手势反馈是画面中央的半透明卡片：亮度/音量显示图标 + 百分比 + 进度条，倍速只显示倍率。
长按触发时有轻微触感反馈。

## 实现要点

### 手势层放在控件之下

每个播放页新增一个透明的 `Box(Modifier.matchParentSize().playerGestureSurface(...))`，
插在 `AndroidView(PlayerView)` 之后、控制条 `AnimatedVisibility` 之前。Compose 的指针事件先交给
命中节点中最上层的那个，因此控制条（返回键、播放键、进度条、倍速/音量滑条）仍然优先拿到触控；
手势循环用 `PointerEventPass.Final` 观察事件，一旦发现 `PointerInputChange.isConsumed` 立即放弃本次手势。
原先根 `Box` 上的 `.clickable { controlsVisible = !controlsVisible }` 已删除，改由手势层统一处理，
避免同一次点击被两个处理器消费。

### 单个 pointerInput 循环

拆分多个 `detectTapGestures` / `detectVerticalDragGestures` 会互相抢占（谁先认领谁就吞掉其它手势），
所以双击 / 长按 / 竖滑都写在同一个 `awaitEachGesture` 里，并且只有在手势明确时才认领：

- 竖向位移超过 `ViewConfiguration.touchSlop` 且 `|dy| > |dx|` → 亮度或音量；
- 横向位移先超过 slop → 判定为进度条意图，直接放弃（不触发任何手势）；
- 按下后在 `longPressTimeoutMillis` 内没有任何决定性的事件 → 长按。这里必须用
  `withTimeoutOrNull(remaining)` 作为兜底：手指按住完全不动时不会再产生 pointer event，
  只靠事件循环里的时间差判断永远不会进入倍速。
- 抬手且没有形成滑动/长按 → 在 `doubleTapTimeoutMillis` 内等第二根手指：
  有则双击，没有则单击。因此单击会晚一个双击超时（约 300ms）才生效，这是区分
  「单击」和「双击的第一下」的必要代价。第二下的抬起也会被消费掉，避免被再识别成一次单击。

长按倍速走 `PlayerManager.beginTemporarySpeed()` / `endTemporarySpeed()`：第一次调用记下用户原本的
倍速，松手恢复；重复调用不会覆盖已记录的原始倍速。用户若在倍速期间手动选了倍速，
`setPlaybackSpeed()` 会先结束临时倍速，避免松手时把手动选择改回去。离开播放页时
`DisposableEffect.onDispose` 同时负责恢复亮度与结束倍速。

### 亮度

第三方 App 无法读写系统亮度滑块（`WRITE_SETTINGS` 是系统级权限），只能通过
`WindowManager.LayoutParams.screenBrightness` 给本 App 窗口叠加亮度，因此：

- 滑到最顶端（≥99%）写回 `BRIGHTNESS_OVERRIDE_NONE`，即"跟随系统亮度"；
- 其余值按 0.01~1.0 设置窗口亮度；
- `restore()` 只在真正改过窗口属性时写回，退出播放页必然调用，不会把其它页面留在变暗状态。
- 拿不到 `Activity`（预览等场景）时 `canControl=false`，左半区域退回音量手势。

音量是播放器自身的 `player.volume`（0..1），因此不会影响系统媒体音量，与既有音量滑条一致。

## 已知取舍

- 单击切控制条有一个双击超时的延迟（见上）。
- 亮度只作用于本 App 窗口，不会修改系统亮度设置。
- IPTV 等直播流没有时长，进度条本来就无法拖动；手势层的横向"放弃"逻辑同样适用。

## 官方参考

- Compose pointer input / `PointerEventPass`: <https://developer.android.com/develop/ui/compose/touchinput/pointerinput>
- `ViewConfiguration`（`getLongPressTimeout` / `getDoubleTapTimeout` / `getScaledTouchSlop`）:
  <https://developer.android.com/reference/android/view/ViewConfiguration>
- 窗口亮度 `WindowManager.LayoutParams.screenBrightness`:
  <https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness>
- 系统亮度（需要 `WRITE_SETTINGS`，本项目不使用）:
  <https://developer.android.com/reference/android/provider/Settings.System#SCREEN_BRIGHTNESS>

## 验证

```text
./gradlew :app:compileDebugKotlin :app:lintDebug
```
