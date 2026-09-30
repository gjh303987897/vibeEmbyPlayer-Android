# 隐私模式生物识别解锁

隐私模式仍默认锁定；设置应用内 PIN 后，用户可在“输入 PIN”对话框中选择“使用生物识别”。仅当设备已录入且当前可使用强生物识别（Class 3）时显示该入口。认证由 AndroidX BiometricPrompt 的系统界面完成，只有成功回调会使 `PrivacyManager` 解锁，原有私密内容过滤与回锁逻辑不变。

没有可用生物识别、用户取消或认证失败时，应用内 PIN 仍可用于解锁。系统锁屏凭据不会替代应用内 PIN；应用不会读取或保存指纹、面容数据。Android 8/8.1 使用兼容库支持的指纹认证。

实现依据：[Android 生物识别认证指南](https://developer.android.com/identity/sign-in/biometric-auth)、[AndroidX Biometric 版本说明](https://developer.android.com/jetpack/androidx/releases/biometric)。设备验收需覆盖已录入、未录入、无硬件、取消、失败后 PIN 回退，以及重新锁定后私密内容再次隐藏。
