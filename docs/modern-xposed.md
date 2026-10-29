# Modern Xposed 迁移

1.4.1 将模块入口、Hook 及配置共享整体迁移到 libxposed，移除 Legacy Xposed 依赖、`assets/xposed_init`、`xposedsharedprefs` 及相关旧元数据，不再调用 `MODE_WORLD_READABLE` 或 New XSharedPreferences。普通登录与后台自动登录继续使用本地私有配置，无需 Root。

## 接口与作用域

使用 Maven Central 正式版 `api:101.0.1`（仅编译）及 `service:101.0.0`（应用端），最低 Modern API 101，兼容 API 102。未依赖 API 102 的热重载或其他新增功能，不要求测试版框架。Android 编译平台升级为 36，安装最低版本仍为 Android 9，targetSdk 保持 35。

模块声明位于 `META-INF/xposed/java_init.list`、`scope.list` 及 `module.prop`。`PortalHook` 继承 `XposedModule`，在 `onSystemServerStarting` 安装系统服务启动钩子，在 `onPackageReady` 为小米浏览器安装生命周期钩子。使用 `hook(...).intercept(...)`，未拦截的调用均通过 `chain.proceed()` 保留原始返回或异常。未启用热重载，更新模块后需重启手机。

仍只推荐并限定 `com.android.browser` 与 `android` 作用域，继续保留小米、Redmi、POCO 品牌判断。原静默认证、门户启动拦截、前台填写兜底及前后台认证协调逻辑保持不变。

## 本地数据与远程同步

应用私有 `UnifiedPreferences` 和原有稳定快照仍是配置来源，保留已保存的账号、历史及后台设置。进程不再通过能否打开世界可读偏好判断模块是否激活，而是通过 `XposedServiceHelper` 提供的真实框架服务绑定和断开事件更新状态；服务稍晚连接时，界面与后台服务会重新检查模式。

只将 `username`、`password`、`enabled`、`silent`、`debug_log` 这五项同步至框架的 `portal` 远程偏好组。历史账号、Wi-Fi 名称、普通模式后台授权和提交记录不进入该组。框架远程偏好面向本模块的作用域进程，不提供自建公开账号查询接口。

本地编辑先成功提交并写入原子快照，再通知同步。Binder 写入在单一、可空闲回收的工作线程中执行，同步期间到达的新保存或服务重连会合并后接续处理。没有周期性同步或持续轮询。同步失败显示提示，并在重新打开应用、后续保存或重新绑定时重试，不把失败写入造成的本地远程缓存变化当作成功。

远程写入按差异更新，避免重新打开应用或只改诊断日志就清空配置、重置认证记录。首次绑定读取框架快照，无变化时不写入；清除账号会同步空凭据与关闭开关，移除远程多余字段。系统端持有远程偏好监听器，并合并单次写入产生的事件，配置变化无需重新加载模块。仅日志开关变化不触发重新认证。

## 验证

单元测试覆盖共享字段限制、异常类型关闭功能、初次同步、重复连接不重写、仅日志变化、清除配置、失败但缓存已更新的重试、未知结果后恢复旧值及本地快照失败不发布。

真机升级检查：

1. 覆盖安装后，在 LSPosed 确认模块启用及小米浏览器、系统框架作用域，重启手机。
2. 打开应用，检查保存的配置仍在、模块选项出现；开启日志后保存或切换配置，再导出观察同步记录。
3. 模块详情不再显示 Legacy/New XSharedPreferences 废弃警告。
4. 测试系统静默认证、手动打开门户的快速填写兜底、关闭开关及清除当前账号后不再提交。
5. 停用模块并重启后，普通模式与后台自动登录仍能使用；重新启用并重启后，本地保存的当前配置同步至框架。

官方参考：

- https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API
- https://github.com/libxposed/api
- https://github.com/libxposed/service
- https://t.me/LSPosed/324
