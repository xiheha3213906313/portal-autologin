package local.portal.autologin

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.*
import java.io.File

class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var login: AppLoginController
    private lateinit var accountStore: AccountStore
    private var moduleAvailable = false
    private var showSettings by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private var pendingLog: String? = null
    private var pendingInstallation: File? = null
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingInstallation
        pendingInstallation = null
        if (file != null && packageManager.canRequestPackageInstalls()) launchInstaller(file)
        else Toast.makeText(this, "允许安装后可再次点击安装更新", Toast.LENGTH_SHORT).show()
    }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingLog
        pendingLog = null
        if (uri != null && text != null) {
            Thread {
                val ok = runCatching {
                    val stream = contentResolver.openOutputStream(uri) ?: error("No output")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }.isSuccess
                runOnUiThread { Toast.makeText(this, if (ok) "日志已导出" else "导出失败", Toast.LENGTH_SHORT).show() }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        prefs = try {
            getSharedPreferences("portal", Context.MODE_WORLD_READABLE).also { moduleAvailable = true }
        } catch (_: SecurityException) { getSharedPreferences("portal", Context.MODE_PRIVATE) }
        accountStore = AccountStore(this, prefs)
        login = AppLoginController.get(this)
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val updates = remember { AppUpdates.get(this@SettingsActivity) }
                val showUpdate = rememberSaveable { mutableStateOf(false) }
                val version = remember {
                    runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("")
                }
                LaunchedEffect(updates.ready, foreground) {
                    if (foreground && updates.consumeInstallationPrompt()) showUpdate.value = true
                }
                val state = login.state
                val accountPage = showSettings || state.stage == AppLoginController.Stage.CONFIGURATION
                SideEffect {
                    if (accountPage) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                if (accountPage) {
                    BackHandler(enabled = showSettings) { showSettings = false }
                    SettingsPage(prefs, updates, showUpdate, version)
                } else {
                    LoginResultPage(state, login,
                        onSettings = { showSettings = true },
                        onRetry = { login.retry() },
                        onWifi = {
                            val action = if (android.os.Build.VERSION.SDK_INT >= 29)
                                Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                            runCatching { startActivity(Intent(action)) }
                        })
                }
                UpdateCard(show = showUpdate, updates = updates, currentVersion = version,
                    install = ::installUpdate, openBrowser = ::openBrowser)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        login.enterForeground(prefs, moduleAvailable)
    }
    override fun onPause() {
        foreground = false
        login.leaveForeground()
        super.onPause()
    }

    @Composable
    private fun SettingsPage(prefs: SharedPreferences, updates: AppUpdates,
                             showUpdate: MutableState<Boolean>, version: String) {
        var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
        var password by remember { mutableStateOf(prefs.getString("password", "") ?: "") }
        var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
        var fast by remember { mutableStateOf(prefs.getBoolean("fast", true)) }
        var silent by remember { mutableStateOf(prefs.getBoolean("silent", false)) }
        var debugLog by remember { mutableStateOf(prefs.getBoolean("debug_log", false)) }
        var showPassword by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(if (accountStore.initializationFailed) "账号列表读取失败，请重试" else null) }
        var savedAccounts by remember { mutableStateOf(accountStore.accounts) }
        var selectedPreset by remember {
            mutableStateOf(accountStore.accounts.firstOrNull { it.bundled && it.username == username }?.username)
        }
        var savedUsername by remember { mutableStateOf(username.trim()) }
        var savedPassword by remember { mutableStateOf(password) }
        var savedEnabled by remember { mutableStateOf(enabled) }
        var savedFast by remember { mutableStateOf(fast) }
        var savedSilent by remember { mutableStateOf(silent) }
        val accountPicker = remember { mutableStateOf(false) }
        val confirmClear = remember { mutableStateOf(false) }
        val about = remember { mutableStateOf(false) }
        var sharing by remember { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        val loginOptionsEnabled = enabled
        val valid = username.isNotBlank() && password.isNotEmpty()
        val credentialsChanged = username.trim() != savedUsername || password != savedPassword
        val changed = credentialsChanged || enabled != savedEnabled || fast != savedFast || silent != savedSilent
        val save = {
            if (username.isBlank() || password.isEmpty()) {
                error = "请输入账号和密码"
            } else if (!accountStore.remember(username.trim(), password)) {
                error = "账号保存失败，请重试"
            } else {
                savedAccounts = accountStore.accounts
                val ok = prefs.edit().putString("username", username.trim())
                    .putString("password", password).putBoolean("enabled", enabled)
                    .putBoolean("fast", fast).putBoolean("silent", silent).commit()
                if (ok) {
                    savedUsername = username.trim(); savedPassword = password
                    savedEnabled = enabled; savedFast = fast; savedSilent = silent
                    notifyConfigChanged()
                    error = null
                    focus.clearFocus()
                    showSettings = false
                    login.saved()
                } else error = "保存失败，请重试"
            }
        }
        LaunchedEffect(showUpdate.value) {
            if (showUpdate.value) { about.value = false; focus.clearFocus() }
        }
        if (sharing) {
            ShareAccountsPage(accounts = accountStore.accounts, onBack = { sharing = false; about.value = true }, openBrowser = ::openBrowser)
            return
        }
        LaunchedEffect(updates.checkResult) {
            updates.consumeResult()?.let { result ->
                if (result.available) { about.value = false; showUpdate.value = true }
                else Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_SHORT).show()
            }
        }
        Scaffold(topBar = {
            Surface(color = MiuixTheme.colorScheme.background) {
                // Match the previous expanded title: native 56dp top spacing, raised by 12dp.
                Row(modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = 26.dp, end = 14.dp, top = 44.dp, bottom = 12.dp)
                    .height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Text("门户登录", fontSize = MiuixTheme.textStyles.title1.fontSize,
                        fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxHeight().widthIn(min = 48.dp)
                        .clickable(role = Role.Button) { focus.clearFocus(); about.value = true }
                        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text("关于", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (error != null) {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                        Text(error ?: "")
                    }
                }
                if (moduleAvailable) Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "自动登录", checked = enabled,
                        onCheckedChange = { enabled = it })
                    SuperSwitch(title = "快速填写", checked = fast, enabled = loginOptionsEnabled,
                        onCheckedChange = { fast = it })
                    SuperSwitch(title = "静默登录", checked = silent, enabled = loginOptionsEnabled,
                        summary = if (silent) "实验功能：勾选系统框架作用域后重启" else null,
                        onCheckedChange = { silent = it })
                }
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(value = username, onValueChange = { username = it; error = null }, readOnly = selectedPreset != null,
                            label = "账号", singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = {
                                focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            }), trailingIcon = {
                                AccountPicker(show = accountPicker, accounts = savedAccounts,
                                    onOpen = {
                                        focus.clearFocus(); savedAccounts = accountStore.accounts
                                        accountPicker.value = !accountPicker.value
                                    },
                                    onSelect = { account ->
                                        username = account.username; password = account.password
                                        selectedPreset = account.username.takeIf { account.bundled }
                                        showPassword = false; error = null; accountPicker.value = false
                                    },
                                    onNew = {
                                        selectedPreset = null; username = ""; password = ""
                                        showPassword = false; error = null; accountPicker.value = false
                                    },
                                    onDelete = { account ->
                                        if (!account.bundled) {
                                        val active = prefs.getString("username", "") == account.username
                                        val canDelete = !active || prefs.edit().remove("username").remove("password").commit()
                                        if (active && canDelete) {
                                            notifyConfigChanged(); login.clear()
                                        }
                                        if (canDelete && accountStore.remove(account.username)) {
                                            savedAccounts = accountStore.accounts
                                            if (username.trim() == account.username) {
                                                username = ""; password = ""; showPassword = false
                                                selectedPreset = null
                                                if (active) { savedUsername = ""; savedPassword = "" }
                                            }
                                            error = null
                                            if (savedAccounts.isEmpty()) accountPicker.value = false
                                        } else error = "删除失败，请重试"
                                        }
                                    })
                            })
                        TextField(value = password, onValueChange = { password = it; error = null }, readOnly = selectedPreset != null,
                            label = "密码", singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (valid && changed) save() }),
                            trailingIcon = {
                                TextButton(text = if (showPassword) "隐藏" else "显示",
                                    onClick = { showPassword = !showPassword },
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent),
                                    minHeight = 48.dp, insideMargin = PaddingValues(horizontal = 12.dp))
                            })
                    }
                }
                TextButton(text = when {
                    !valid -> "请输入账号和密码"
                    !changed -> "已保存"
                    credentialsChanged -> "保存并登录"
                    else -> "保存修改"
                }, enabled = valid && changed, onClick = { save() },
                    modifier = Modifier.fillMaxWidth(), minHeight = 54.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary())
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "诊断日志", checked = debugLog,
                        summary = if (debugLog) "即时生效，复现问题后导出" else null,
                        onCheckedChange = { value ->
                            if (prefs.edit().putBoolean("debug_log", value).commit()) {
                                debugLog = value
                                DiagnosticLog.setEnabled(value)
                                sendBroadcast(Intent(SilentPortal.LOG_CONFIG).setPackage("android"))
                            } else error = "日志设置保存失败"
                        })
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "导出日志", onClick = { requestLogExport() },
                            modifier = Modifier.weight(1f), minHeight = 48.dp,
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
                        TextButton(text = "清空日志", onClick = { clearLogs() },
                            modifier = Modifier.weight(1f), minHeight = 48.dp,
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
                    }
                }
                TextButton(text = "清除账号", onClick = { focus.clearFocus(); confirmClear.value = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    minHeight = 48.dp, colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
            }
        }
        SuperDialog(show = confirmClear, title = "清除账号？", summary = "清除自存账号与登录配置，保留内置预设",
            onDismissRequest = { confirmClear.value = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = { confirmClear.value = false }, modifier = Modifier.weight(1f))
                TextButton(text = "清除", modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                        if (prefs.edit().clear().commit()) {
                            username = ""; password = ""; enabled = false; fast = true; silent = false; debugLog = false
                            selectedPreset = null; savedUsername = ""; savedPassword = ""
                            savedEnabled = false; savedFast = true; savedSilent = false
                            notifyConfigChanged()
                            DiagnosticLog.setEnabled(false)
                            login.clear()
                            showSettings = false
                            showPassword = false; error = null
                            val historyCleared = accountStore.clear()
                            if (historyCleared) savedAccounts = accountStore.accounts
                            else error = "账号列表清除失败，请重试"
                            accountPicker.value = false
                            confirmClear.value = false
                            Toast.makeText(this@SettingsActivity, if (historyCleared) "已清除" else "部分条目未能清除", Toast.LENGTH_SHORT).show()
                        } else {
                            confirmClear.value = false; error = "清除失败，请重试"
                        }
                    })
            }
        }
        SuperDialog(show = about, title = "关于",
            summary = "门户自动登录 $version\n作者：xiheha\n源码已公开",
            onDismissRequest = { about.value = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    AboutLink("项目地址", PROJECT_URL) { openBrowser(PROJECT_URL) }
                    AboutLink("帮助我们", "分享你的账号密码") { about.value = false; sharing = true }
                }
                TextButton(text = when {
                    updates.checking -> "检查中…"
                    updates.downloading -> "查看下载进度"
                    updates.ready != null -> "安装更新"
                    else -> "检查更新"
                }, enabled = !updates.checking, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (updates.downloading || updates.ready != null) { about.value = false; showUpdate.value = true }
                        else updates.check(version)
                    })
                TextButton(text = "关闭", onClick = { about.value = false },
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }

    @Composable
    private fun AboutLink(label: String, value: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp))
        }
    }

    private fun openBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show() }
    }

    private fun installUpdate(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallation = file
            runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"))) }.onFailure {
                pendingInstallation = null
                Toast.makeText(this, "请在系统设置中允许此应用安装更新", Toast.LENGTH_LONG).show()
            }
        } else launchInstaller(file)
    }

    private fun launchInstaller(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "无法启动安装，请使用浏览器下载", Toast.LENGTH_LONG).show() }
    }
    private fun notifyConfigChanged() {
        sendBroadcast(Intent(SilentPortal.ACTION).setPackage("android"))
    }
    private fun requestLogExport() {
        sendOrderedBroadcast(Intent(SilentPortal.LOG_EXPORT).setPackage("android"), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (isFinishing || isDestroyed) return
                    pendingLog = "Application\n" + DiagnosticLog.snapshot() +
                        if (resultCode == -1 && resultData != null) "\nSystem module\n" + resultData else ""
                    exportLog.launch("portal-debug-${System.currentTimeMillis()}.txt")
                }
            }, null, 0, null, null)
    }
    private fun clearLogs() {
        DiagnosticLog.clear()
        sendOrderedBroadcast(Intent(SilentPortal.LOG_CLEAR).setPackage("android"), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Toast.makeText(this@SettingsActivity, "日志已清空", Toast.LENGTH_SHORT).show()
                }
            }, null, 0, null, null)
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/xiheha3213906313/portal-autologin"
    }
}
