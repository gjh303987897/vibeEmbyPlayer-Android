# 新增服务表单

服务列表的“添加”入口打开完整表单。表单按服务类型、连接信息、账户信息和其他选项分区；服务类型直接显示名称与图标。内容可滚动，顶部关闭和底部保存操作固定，软键盘打开时表单及操作区随可用空间调整。

Emby、Jellyfin 和 WebDAV 需要有效的主机、端口、用户名与密码；名称可留空并使用主机名。IPTV 和 Link 只需先填写名称，后续在服务页面继续设置。切换服务类型时更新默认端口，切换 HTTP/HTTPS 时仅在端口仍为旧默认值时更新。保存期间禁用输入与重复提交，并在表单内显示保存错误。

原有的自动登录、自签发证书、隐私卡片和服务保存流程保持不变。设计参考 Qt 版 `VIBEDOCS/MediaServices.md` 的服务字段语义，以及 Android 的 [自定义 Dialog](https://developer.android.com/develop/ui/compose/quick-guides/content/display-user-input)、[Material 3 Scaffold 与内边距](https://developer.android.com/develop/ui/compose/system/material-insets)。
