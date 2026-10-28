# 门户自动登录 0.3.0

为用户已验证的访客门户编写的 LSPosed 传统 API 模块。测试目标：小米 14、Android 16、小米浏览器门户 Activity。

## 安装

1. 安装 `portal-autologin-0.3.0.apk`。
2. 在 LSPosed 中启用“门户自动登录”，作用域勾选“小米浏览器 / com.android.browser”。不要勾选系统框架；已安装的 `com.android.captiveportallogin` 包不是本机实际宿主。
3. 重启手机一次，打开模块配置应用，填写自己的账号密码，打开开关并保存。
4. Wi-Fi 下次要求认证时打开系统登录通知，让登录页面留在前台。模块识别指定页面后自动逐字填写并点击 Login。
5. 已认证时不会强制注销，不需要重复试登录。完全不显示门户窗口不是此版本承诺的功能。

## 0.3.0 界面更新

配置页采用真正的 Miuix Compose 0.5.1 组件（MiuixTheme、TopAppBar、Card、TextField、SuperSwitch、TextButton、SuperDialog），跟随系统浅色/深色模式。主界面只保留账号、密码、两个开关、保存和清除账号。仅在未启用 LSPosed 或输入错误时显示提示。清除账号需二次确认；支持密码显示切换。

沿用 0.2.0 的登录流程、配置键、包名及签名，可覆盖安装。没有重新实现或修改自动登录逻辑。

## 0.2.0 加速更新

默认开启快速填写：字符间隔由 80ms 缩短到 8ms，焦点等待 30ms、每个字段失焦后 60ms、提交前等待 120ms；保留原有输入事件顺序。账号密码共 30 字符时，设置的输入流程等待总计约 540ms，不包含网页加载、系统调度和网关认证时间。配置页可关闭“快速填写”恢复原来的已验证速度。门户首次检查由 800ms 缩短为 150ms；仅在等待表单时每 400ms 检查，开始输入后恢复每秒检查。无独立后台服务。

0.1.0 已由用户在小米 14 / Android 16 上验证自动登录成功；0.2.0 的加速时序仍需实机验证。覆盖安装保持原有配置和签名。更新后重启浏览器进程或手机。

## 行为和限制

- Hook 限定 `com.android.browser` 内 `com.android.captiveportallogin.CaptivePortalLoginActivity` 的生命周期。
- JavaScript 同时验证 HTTPS 域名、1026 端口、同源 iframe、POST `/login`、输入框和按钮属于同一个表单。
- 沿用实机验证的逐字 beforeinput/input、change、blur、等待、按钮 click 流程。保留门户原有表单脚本和隐藏字段，不直接构造认证请求。
- 页面可见且准备就绪才开始。切到后台时暂停，交由用户手动完成，避免后台网页计时器造成不确定行为。
- 发现未勾选的可见复选框、空的可见访问码或表单校验失败时不提交。
- 每个文档只尝试一次，每个浏览器进程设 5 分钟冷却。进程被重启后冷却会重置；同一个会话加载新文档也不会自动再次提交。
- “已提交”不代表认证成功。仅当 Intent 提供了对应 Network，且该 Wi-Fi 具备系统 VALIDATED 能力时才提示联网验证通过。Intent 无 Network 或权限不可用时不声称验证成功。
- 模块没有 INTERNET 权限或独立后台服务，不主动启动认证流程；网络行为通过浏览器现有门户完成。

## 本地配置

账号密码使用 LSPosed 新 XSharedPreferences 支持存入本地共享首选项，供浏览器进程读取，不是加密密码库。没有上传、分析服务、账号预置或凭据日志。配置页禁止截图和系统自动备份。设备 Root / LSPosed 环境中的其他高权限组件仍可访问本地配置。

修改配置后关闭并重新打开门户。关闭开关不会撤销已经提交的请求；正在输入时应关闭门户停止当前流程。删除配置按钮会清除保存的账号密码。

## 验证

- `node tests/login.test.cjs`：模拟 DOM，验证逐字输入/失焦/单次点击、同源 iframe、冷却、额外条件及后台停止。
- Java 编译、DEX 转换、APK 对齐与签名校验。
- 0.1.0 已在用户设备验证；0.3.0 界面完成编译与 Android Lint 检查，未在真实手机上运行或截图验证。登录 JS 保持 0.2.0 的实现。

出现兼容性问题时，提供 LSPosed 日志中以 `PortalAuto:` 开头的行即可。日志不包含账号密码、JS 源码或认证参数。

## 构建

使用 JDK 21、Gradle 8.11.1、Android SDK Platform 35、Build Tools 35.0.0、AGP 8.10.1、Kotlin / Compose Compiler 2.2.10、Miuix 0.5.1。Xposed API 82 jar 仅作编译桩，不打包。

1. 配置 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir`。
2. 将 `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar` 下载到 `toolchain/xposed.jar`。
3. 可选：在 `signing/module.p12` 放置签名密钥，口令保存于 `signing/password`，别名 `module`。未提供密钥时生成未签名 APK，需要签名后才能安装；自建密钥不能覆盖原发行版。私钥及口令不上传仓库。
4. 执行 `./gradlew :app:assembleRelease :app:lintRelease`，或 `bash build.sh`。

JS 会在构建时生成 Java 常量；不要手工编辑生成文件。发布 APK 由 R8 缩减，保留 LSPosed Hook 入口。第三方组件通知和 Apache 2.0 协议放在 APK assets 中。

LSPosed 新共享配置要求 API 93 支持（模块元数据 minversion 93）。
