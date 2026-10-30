# 静默登录 EOF 排查

2026-10-30 的日志显示系统监听和门户拦截均已挂载；失败时已读取新表单并写出一次认证请求，但响应在 HTTP 状态返回前以 EOF 结束。用户确认较早的联网成功来自手动打开门户后的填写。之后一次静默请求返回 302，约 0.25 秒后收到系统联网验证。打开应用后恢复与该成功时间接近，但这些记录不足以证明原因是配置同步。

1.4.2 隔离一个可能影响结果的因素：Android 的 `Network` 对象缓存自己的 URL 连接工厂和连接池。认证保留首页与表单 GET 的正常连接方式，唯一一次 POST 用同一网络句柄重建的 Network 对象打开，避免使用 GET 已用过的连接池。Cookie、表单、浏览器请求特征、TLS 验证和目标 Wi-Fi 均保持原样；失败后不回退到旧连接池重发。普通模式前台、后台共用控制器，与 LSP 静默认证一同使用这个传输实现。

系统静默路径只接受真实 Wi-Fi，排除携带 Wi-Fi transport 的 VPN 网络；不使用移动数据或默认网络代替目标 Wi-Fi。普通模式的联网复查、冷却与提交保护维持原逻辑。

测试版的后续真机日志在 13:00、13:09 两次均记录 POST 返回 302，随后同一网络收到系统 VALIDATED，外网探测也返回 204 / 200。系统先确认联网会结束当前任务，因此随后探测返回 CANCELLED 不代表认证失败。13:20 之后网络既无 CAPTIVE 也无 VALIDATED，系统静默路径不会据此盲目提交；13:25 的前台尝试在获取首页时 EOF，尚未提交账密，两项外网检测不可达。用户报告当时上游断网，这些记录与之相符，但不能单独证明原因。连接池调整已有两次成功记录，仍不足以证明所有间歇性 EOF 均已消除；普通模式使用该调整尚需真机验证。

认证响应正常或提交结果未知后，在原工作线程执行最多一次现有互联网检查，两项 HTTPS 探测均绑定原目标 Wi-Fi，不携带门户凭据。断网、被取消或超过等待期限时停止检查；不定时轮询，也不根据探测失败重发账号密码。实际互联网探测只是额外证据，原系统验证、45 秒等待和 5 分钟提交记录规则保留。

新增日志：

- `attempt.network` / `network.id` / `validation.internet_network`：本次开机内的 Android 网络编号，不是 SSID、MAC 或 IP。能力日志仅记录状态变化，避免重复已联网事件淹没关键记录。
- `submit.fresh_network_transport`：本次 POST 使用独立连接池。
- `validation.internet_result`：0 ONLINE，1 CAPTIVE，2 UNAVAILABLE，3 CANCELLED；仍应结合该次网络的系统验证记录判断。
- `module.local_config_flags` / `config.native_flags`：本地与系统端配置的存在性标志，位 1 为自动登录，2 为静默登录，4 为有账号，8 为有密码；不包含内容、长度或凭据摘要，不能据此证明具体凭据完全相同。
- `network.active_vpn`：系统进程默认网络是否为 VPN，仅作为辅助信息。

复测时覆盖安装并重启，先打开应用保存配置、开启日志。之后连接一个确实需要认证的访客 Wi-Fi，等待结果期间不手动打开门户，也不打开本应用触发前台检查；记录连接与手动操作的时间，最后再导出日志。若失败后需要手动登录，先记下时间再操作，避免把手动兜底误归为静默成功。

Android 实现依据：[Network.java（Android 16）](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/framework/src/android/net/Network.java)，`openConnection` 按 Network 对象保存连接工厂，`fromNetworkHandle` 保留相同的网络与 DNS 标志。
