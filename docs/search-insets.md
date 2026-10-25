# 搜索页系统安全区

Emby / Jellyfin 搜索页使用自定义圆角搜索栏。应用启用了 edge-to-edge，根导航明确把顶部系统安全区交给子页面；`Scaffold.topBar` 中的普通 `Row` 不会像 Material 3 `TopAppBar` 一样自动避开状态栏。此前只有固定 `8.dp` 上边距，导致时间、电量等系统内容覆盖搜索栏顶部。

搜索栏现复用 `topEdgeInsets()`，在绘制圆角背景和固定间距之前应用状态栏与屏幕切口 inset，覆盖竖屏顶部和横屏侧边切口。该 Compose inset modifier 会消费已应用的区域，因此祖先已经处理安全区时不会再次插入空白。结果区域继续使用 `Scaffold` 测量得到的 `innerPadding`，并消费这部分 inset；键盘的原有 `imePadding()` 保持由页面外层处理。

仅修改页面布局，不改变关键词提交、服务端查询、分页与返回行为；这些语义已对照 Qt 的 `Main.qml/MediaServerSearchBar`、`EmbyClient::searchVideoItems` 与 `VIBEDOCS/EmbyJellyfinApi.md`。

## 验证

`com.vibeplayer.app.search.SearchInsetsLayoutTest` 在真实 `MainActivity` 中渲染完整 `SearchScreen`，不提交查询、不依赖媒体服务账号：

- 竖屏、横屏读取实际 `rootWindowInsets`，验证输入框和返回按钮位于状态栏/切口安全区内，并精确检查顶部间距只有一次。
- 祖先先消费相同 inset 后重新布局，验证搜索框位置不变。
- 保存包含系统状态栏的页面截图至测试应用的 `externalFilesDir/search-insets/`。

2026-10-25 在华为 TAS-AL00 / Android 12（API 31）的独立 `.regression` 验证包上，两项用例通过，
并人工检查了竖屏和横屏最终截图：搜索栏完整位于系统状态栏下方，横屏侧边安全区也保留。
截图前等待系统旋转动画结束，避免把配置已切换但合成器仍在旋转的中间画面当成最终布局。
本轮不提交搜索请求，验证的是截图中对应的页面布局；搜索接口和账号逻辑未改动。

正常包名的 debug APK、AndroidTest APK、164 项 JVM 测试和 lint 均通过（0 error，3 项既有 warning）。
设备日志位于忽略目录 `build/speed-search-device-20261025/final-device-results.log`，
最终截图为同目录下 `search-insets/search-insets-1.png` 和 `search-insets-2.png`。

官方依据：[Material 3 的 inset 处理](https://developer.android.com/develop/ui/compose/system/material-insets)、[Compose inset 消费规则](https://developer.android.com/develop/ui/compose/system/insets-ui#inset-consumption)。
