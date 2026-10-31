package local.portal.autologin

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.*
import java.io.File
import kotlinx.coroutines.delay

class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var login: AppLoginController
    private lateinit var accountStore: AccountStore
    private val moduleAvailable: Boolean get() = ModuleBridge.available
    private val xiaomiSystem = DeviceCompatibility.supportsModule(Build.BRAND, Build.MANUFACTURER)
    private var showSettings by mutableStateOf(false)
    private var pendingSavedCheck by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private var backgroundExit by mutableStateOf(false)
    private var pendingLog: String? = null
    private var pendingInstallation: File? = null
    private var askedForWifiPermission = false
    private var askedForNotifications = false
    private var authorizationRevision by mutableIntStateOf(0)
    private var showBackgroundSetup by mutableStateOf(false)
    private val backgroundSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshBackgroundAuthorization()
    }
    private val wifiNamePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshBackgroundAuthorization()
    }
    private val backgroundNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshBackgroundAuthorization()
    }
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
        val configuration = PortalPreferences.open(this)
        prefs = configuration.prefs
        showBackgroundSetup = savedInstanceState?.getBoolean("background_setup") == true &&
            !prefs.getBoolean("background_setup_completed", false)
        accountStore = AccountStore(this, prefs)
        login = AppLoginController.get(this)
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                LaunchedEffect(moduleAvailable) {
                    if (foreground) login.enterForeground(prefs, moduleAvailable && xiaomiSystem)
                    else login.initialize(prefs, moduleAvailable && xiaomiSystem)
                    refreshBackgroundAuthorization()
                }
                val updates = remember { AppUpdates.get(this@SettingsActivity) }
                val showUpdate = rememberSaveable { mutableStateOf(false) }
                val version = remember {
                    runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("")
                }
                LaunchedEffect(updates.ready, foreground) {
                    if (foreground && updates.consumeInstallationPrompt()) showUpdate.value = true
                }
                val state = login.state
                LaunchedEffect(state.internetConfirmed, updates.checking, foreground) {
                    if (foreground && state.internetConfirmed && !updates.checking)
                        updates.retryOnConnection(version)
                }
                LaunchedEffect(state.stage, state.offerForceLogin, pendingSavedCheck) {
                    if (pendingSavedCheck && state.stage == AppLoginController.Stage.ERROR && state.offerForceLogin) {
                        pendingSavedCheck = false
                        showSettings = false
                    }
                }
                BackHandler(enabled = backgroundExit) { hideTaskInBackground() }
                val quickStart = !showSettings &&
                    (prefs.getString("username", "").isNullOrBlank() || prefs.getString("password", "").isNullOrEmpty())
                val accountPage = showSettings || quickStart
                val presetUpdates = remember { AccountPresetUpdates.get(this@SettingsActivity) }
                var quickStartRefreshed by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(quickStart) {
                    if (quickStart && !quickStartRefreshed) {
                        quickStartRefreshed = true
                        presetUpdates.refresh(silent = true)
                    }
                }
                SideEffect {
                    if (accountPage) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                if (showSettings) {
                    BackHandler(enabled = showSettings) { showSettings = false }
                    SettingsPage(prefs, updates, showUpdate, version)
                } else if (quickStart) {
                    QuickStartPage(accountStore, presetUpdates, moduleAvailable, xiaomiSystem,
                        onStart = ::saveQuickStart,
                        onSettings = { showSettings = true })
                } else {
                    LoginResultPage(state, login, moduleAvailable, xiaomiSystem,
                        onSettings = { pendingSavedCheck = false; showSettings = true },
                        onRetry = { login.retry() },
                        onWifi = {
                            val action = if (android.os.Build.VERSION.SDK_INT >= 29)
                                Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                            runCatching { startActivity(Intent(action)) }
                        })
                }
                UpdateCard(show = showUpdate, updates = updates, currentVersion = version,
                    install = ::installUpdate, openBrowser = ::openBrowser)
                if (showBackgroundSetup) BackgroundSetupPrompt(
                    notificationsAllowed = remember(authorizationRevision) {
                        BackgroundAuthorization.notificationsVisible(this@SettingsActivity)
                    },
                    onDismiss = { showBackgroundSetup = false },
                    onNotifications = ::requestNotificationPermission,
                    onAutoStart = ::openAutoStartSettings,
                    onComplete = ::completeBackgroundSetup)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppUpdates.get(this).checkOnStart(DiagnosticLog.VERSION)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("background_setup", showBackgroundSetup)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        setTaskHidden(false)
        ModuleBridge.synchronize()
        login.enterForeground(prefs, moduleAvailable && xiaomiSystem)
        refreshBackgroundAuthorization()
    }
    override fun onPause() {
        foreground = false
        login.leaveForeground()
        super.onPause()
    }

    private fun saveQuickStart(username: String, password: String): String? {
        val account = username.trim()
        if (account.isBlank() || password.isEmpty()) return "请输入账号和密码"
        if (!accountStore.remember(account, password)) return "账号保存失败，请重试"
        if (!prefs.edit().putString("username", account).putString("password", password).commit())
            return "保存失败，请重试"
        notifyConfigChanged()
        pendingSavedCheck = false
        showSettings = false
        login.saved()
        BackgroundLoginService.sync(this)
        return null
    }

    @Composable
    private fun SettingsPage(prefs: SharedPreferences, updates: AppUpdates,
                             showUpdate: MutableState<Boolean>, version: String) {
        var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
        var password by remember { mutableStateOf(prefs.getString("password", "") ?: "") }
        var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
        var silent by remember { mutableStateOf(prefs.getBoolean("silent", false)) }
        var forceLogin by remember { mutableStateOf(prefs.getBoolean("force_login", false)) }
        var backgroundLogin by remember { mutableStateOf(prefs.getBoolean("background_login", false)) }
        var advancedExpanded by rememberSaveable { mutableStateOf(false) }
        var wifiFilter by remember { mutableStateOf(prefs.getString("background_wifi", "").orEmpty()) }
        var wifiFocused by remember { mutableStateOf(false) }
        var wifiHadIme by remember { mutableStateOf(false) }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        var debugLog by remember { mutableStateOf(prefs.getBoolean("debug_log", false)) }
        var showPassword by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(if (accountStore.initializationFailed ||
            (prefs as? UnifiedPreferences)?.initializationFailed == true) "配置读取失败，请重试" else null) }
        var savedFeedback by remember { mutableStateOf(false) }
        var savedAccounts by remember { mutableStateOf(accountStore.accounts) }
        val presetUpdates = remember { AccountPresetUpdates.get(this@SettingsActivity) }
        var selectedPreset by remember {
            mutableStateOf(accountStore.accounts.firstOrNull {
                it.bundled && it.username == username && it.password == password
            }?.username)
        }
        var savedUsername by remember { mutableStateOf(username.trim()) }
        var savedPassword by remember { mutableStateOf(password) }
        var savedEnabled by remember { mutableStateOf(enabled) }
        var savedSilent by remember { mutableStateOf(silent) }
        var savedForceLogin by remember { mutableStateOf(forceLogin) }
        var savedBackgroundLogin by remember { mutableStateOf(backgroundLogin) }
        var savedWifiFilter by remember { mutableStateOf(wifiFilter) }
        val accountPicker = remember { mutableStateOf(false) }
        val confirmClear = remember { mutableStateOf(false) }
        val about = remember { mutableStateOf(false) }
        val authorization = remember { mutableStateOf(false) }
        var sharing by remember { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        val hasUpdate = updates.hasUpdate(version)
        val loginOptionsEnabled = xiaomiSystem && enabled
        val valid = username.isNotBlank() && password.isNotEmpty()
        val credentialsChanged = username.trim() != savedUsername || password != savedPassword
        val ordinaryMode = !moduleAvailable || !xiaomiSystem
        val backgroundChanged = backgroundLogin != savedBackgroundLogin || wifiFilter != savedWifiFilter
        val optionsChanged = enabled != savedEnabled || silent != savedSilent || forceLogin != savedForceLogin || backgroundChanged
        val changed = credentialsChanged || optionsChanged
        val switchingSavedAccount = credentialsChanged && !optionsChanged && savedAccounts.any {
            it.username == username.trim() && it.password == password
        }
        val applyBackgroundOptions = { active: Boolean, name: String ->
            if (active && !WifiNameMatcher.validFilter(name)) {
                error = "请输入有效的 Wi-Fi 名称，或留空匹配全部"
                false
            } else if (!prefs.edit().putBoolean("background_login", active).putString("background_wifi", name).commit()) {
                error = "后台设置保存失败，请重试"
                false
            } else {
                backgroundLogin = active; savedBackgroundLogin = active
                wifiFilter = name; savedWifiFilter = name
                error = null
                DiagnosticLog.event("background.settings_saved", if (active) 1 else 0)
                refreshBackgroundAuthorization()
                true
            }
        }
        val onBackgroundToggle = { active: Boolean ->
            focus.clearFocus()
            if (applyBackgroundOptions(active, wifiFilter) && active &&
                !prefs.getBoolean("background_setup_completed", false)) {
                showBackgroundSetup = true
            }
        }
        val backgroundStatus = remember(authorizationRevision, savedUsername, savedPassword,
            backgroundLogin, savedWifiFilter, BackgroundLoginService.running) {
            BackgroundLoginService.statusLabel(this@SettingsActivity)
        }
        val save = {
            if (username.isBlank() || password.isEmpty()) {
                error = "请输入账号和密码"
            } else if (backgroundLogin && !WifiNameMatcher.validFilter(wifiFilter)) {
                error = "请输入有效的 Wi-Fi 名称，或留空匹配全部"
            } else if (!accountStore.remember(username.trim(), password)) {
                error = "账号保存失败，请重试"
            } else {
                savedAccounts = accountStore.accounts
                val ok = prefs.edit().putString("username", username.trim())
                    .putString("password", password).putBoolean("enabled", enabled)
                    .putBoolean("silent", silent).putBoolean("force_login", forceLogin)
                    .putBoolean("background_login", backgroundLogin).putString("background_wifi", wifiFilter).commit()
                if (ok) {
                    savedUsername = username.trim(); savedPassword = password
                    savedEnabled = enabled; savedSilent = silent
                    savedForceLogin = forceLogin
                    savedBackgroundLogin = backgroundLogin; savedWifiFilter = wifiFilter
                    notifyConfigChanged()
                    error = null
                    focus.clearFocus()
                    if ((!ordinaryMode || !forceLogin) && login.isWifiOnline()) {
                        showSettings = true
                        savedFeedback = true
                        pendingSavedCheck = true
                    } else {
                        savedFeedback = false
                        showSettings = false
                        pendingSavedCheck = false
                    }
                    login.saved()
                    BackgroundLoginService.sync(this@SettingsActivity)
                    if (ordinaryMode && backgroundLogin &&
                        !BackgroundAuthorization.requiredGranted(this@SettingsActivity, wifiFilter.isNotBlank())) {
                        showSettings = true
                        if (!prefs.getBoolean("background_setup_completed", false) &&
                            !BackgroundAuthorization.notificationsVisible(this@SettingsActivity)) showBackgroundSetup = true
                    }
                } else error = "保存失败，请重试"
            }
        }
        LaunchedEffect(imeVisible, wifiFocused) {
            if (!wifiFocused) wifiHadIme = false
            else if (imeVisible) wifiHadIme = true
            else if (wifiHadIme) focus.clearFocus()
        }
        LaunchedEffect(savedFeedback) {
            if (savedFeedback) { delay(1800); savedFeedback=false }
        }
        LaunchedEffect(presetUpdates.revision) {
            savedAccounts = accountStore.accounts
            if (selectedPreset != null && savedAccounts.none {
                it.bundled && it.username == username.trim() && it.password == password
            }) selectedPreset = null
        }
        LaunchedEffect(username,password,enabled,silent,forceLogin,backgroundLogin,wifiFilter) {
            savedFeedback=false
            // A late background result must not discard a configuration being edited.
            pendingSavedCheck=false
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
        Scaffold(contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout), topBar = {
            Surface(color = MiuixTheme.colorScheme.background) {
                // Match the previous expanded title: native 56dp top spacing, raised by 12dp.
                Row(modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = 26.dp, end = 14.dp, top = 44.dp, bottom = 12.dp)
                    .height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Text("门户登录", fontSize = MiuixTheme.textStyles.title1.fontSize,
                        fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxHeight().widthIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            focus.clearFocus()
                            about.value = true
                        }
                        .padding(start = 12.dp, end = if (hasUpdate) 4.dp else 12.dp), contentAlignment = Alignment.Center) {
                        Text(if (hasUpdate) "发现新版本！" else "关于", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }, bottomBar = {
            if (!moduleAvailable || !xiaomiSystem) Box(
                modifier = Modifier.fillMaxWidth()
                    // safeDrawing also includes IME; the footer must stay behind the keyboard.
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                contentAlignment = Alignment.BottomCenter
            ) { ModuleStatusHint(moduleAvailable, xiaomiSystem) }
        }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (error != null || ModuleBridge.syncFailed) {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                        Text(error ?: "模块配置同步失败，请重新打开应用重试")
                    }
                }
                if (moduleAvailable) Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "自动登录", checked = enabled,
                        enabled = xiaomiSystem,
                        onCheckedChange = { enabled = it })
                    SuperSwitch(title = "静默登录", checked = silent, enabled = loginOptionsEnabled,
                        summary = if (silent) "实验功能：勾选系统框架作用域后重启" else null,
                        onCheckedChange = { silent = it })
                }
                if (ordinaryMode) Card(modifier = Modifier.fillMaxWidth()) {
                    val arrowRotation by animateFloatAsState(if (advancedExpanded) 90f else 0f,
                        label = "advancedOptionsArrow")
                    Row(Modifier.fillMaxWidth()
                        .semantics { stateDescription = if (advancedExpanded) "已展开" else "已折叠" }
                        .clickable(role = Role.Button) {
                            focus.clearFocus()
                            advancedExpanded = !advancedExpanded
                        }
                        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("高级选项", fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
                            modifier = Modifier.size(20.dp).rotate(arrowRotation),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    AnimatedVisibility(visible = advancedExpanded) {
                        Column {
                            SuperSwitch(title = "强制登录", checked = forceLogin,
                                summary = if (forceLogin) "启动软件或保存配置时始终进行认证" else null,
                                onCheckedChange = { forceLogin = it })
                            Row(Modifier.fillMaxWidth().toggleable(value = backgroundLogin, role = Role.Switch,
                                onValueChange = onBackgroundToggle)
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("后台自动登录", fontSize = 17.sp)
                                        if (backgroundLogin) Text("($backgroundStatus)",
                                            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    if (backgroundLogin) Text("实验性功能，可能不生效", fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Switch(checked = backgroundLogin,
                                    onCheckedChange = onBackgroundToggle,
                                    modifier = Modifier.clearAndSetSemantics { })
                            }
                            if (backgroundLogin) Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.fillMaxWidth()) {
                                    TextField(value = wifiFilter, onValueChange = { wifiFilter = it; error = null },
                                        label = "", singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                            .semantics { contentDescription = "Wi-Fi名称(留空匹配全部)" }
                                            .onFocusChanged { state ->
                                                val lostFocus = wifiFocused && !state.isFocused
                                                wifiFocused = state.isFocused
                                                if (lostFocus && wifiFilter != savedWifiFilter)
                                                    applyBackgroundOptions(backgroundLogin, wifiFilter)
                                            },
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                                        trailingIcon = {
                                            TextButton(text = "授权", onClick = {
                                                focus.clearFocus()
                                                if (applyBackgroundOptions(backgroundLogin, wifiFilter)) authorization.value = true
                                            }, colors = ButtonDefaults.textButtonColors(color = Color.Transparent),
                                                minHeight = 48.dp, insideMargin = PaddingValues(horizontal = 12.dp))
                                        })
                                    if (wifiFilter.isEmpty()) Text("Wi-Fi名称(留空匹配全部)", fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 76.dp)
                                            .clearAndSetSemantics { })
                                }
                            }
                        }
                    }
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
                                    fetching = presetUpdates.showRefreshProgress, feedback = presetUpdates.feedback,
                                    onRefresh = { presetUpdates.refresh() },
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
                                            BackgroundLoginService.sync(this@SettingsActivity)
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
                    savedFeedback -> "已保存"
                    !valid -> "请输入账号和密码"
                    !changed -> "当前配置"
                    switchingSavedAccount -> "切换配置"
                    else -> "保存并登录"
                }, enabled = valid && changed && !savedFeedback, onClick = { save() },
                    modifier = Modifier.fillMaxWidth(), minHeight = 54.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary())
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "诊断日志", checked = debugLog,
                        summary = if (debugLog) "即时生效，复现问题后导出" else null,
                        onCheckedChange = { value ->
                            if (prefs.edit().putBoolean("debug_log", value).commit()) {
                                debugLog = value
                                DiagnosticLog.setEnabled(value)
                                if (value) {
                                    BackgroundLoginService.recordState(this@SettingsActivity)
                                    ModuleBridge.recordState()
                                }
                                sendBroadcast(Intent(SilentPortal.LOG_CONFIG).setPackage("android"))
                            } else error = "日志设置保存失败"
                        })
                    if (debugLog) Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
        BackgroundAuthorizationCard(show = authorization, context = this, revision = authorizationRevision,
            needsWifiName = wifiFilter.isNotBlank(), requestNotifications = ::requestNotificationPermission,
            requestLocation = ::requestWifiNamePermission, requestAutoStart = ::openAutoStartSettings,
            openSettings = ::openBackgroundSettings)

        SuperDialog(show = confirmClear, title = "清除账号？", summary = "清除自存账号与登录配置，保留预设",
            onDismissRequest = { confirmClear.value = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = { confirmClear.value = false }, modifier = Modifier.weight(1f))
                TextButton(text = "清除", modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                        if (prefs.edit().clear().commit()) {
                            username = ""; password = ""; enabled = false; silent = false; debugLog = false
                            forceLogin = false; savedForceLogin = false
                            backgroundLogin = false; savedBackgroundLogin = false; backgroundExit = false
                            wifiFilter = ""; savedWifiFilter = ""
                            selectedPreset = null; savedUsername = ""; savedPassword = ""
                            savedEnabled = false; savedSilent = false
                            notifyConfigChanged()
                            DiagnosticLog.setEnabled(false)
                            login.clear()
                            BackgroundLoginService.sync(this@SettingsActivity)
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
                    updates.downloading -> "查看下载进度"
                    updates.ready != null -> "安装更新"
                    hasUpdate -> "发现新版本"
                    updates.checking -> "检查中…"
                    else -> "检查更新"
                }, enabled = !updates.checking || hasUpdate || updates.downloading || updates.ready != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (updates.downloading || updates.ready != null || hasUpdate) { about.value = false; showUpdate.value = true }
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

    private fun refreshBackgroundAuthorization() {
        val configuration = PortalPreferences.open(this)
        backgroundExit = !configuration.moduleActive && configuration.prefs.getBoolean("background_login", false)
        authorizationRevision++
        BackgroundLoginService.sync(this)
    }

    private fun setTaskHidden(hidden: Boolean): Boolean = runCatching {
        val task = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            @Suppress("DEPRECATION")
            val id = if (Build.VERSION.SDK_INT >= 29) it.taskInfo.taskId else it.taskInfo.id
            id == taskId
        } ?: return@runCatching false
        task.setExcludeFromRecents(hidden)
        true
    }.onFailure { DiagnosticLog.failure(it) }.getOrDefault(false)

    private fun hideTaskInBackground() {
        BackgroundLoginService.sync(this)
        DiagnosticLog.event("background.hide_task", if (BackgroundLoginService.running) 1 else 0)
        if (!setTaskHidden(true) || !moveTaskToBack(true)) finishAndRemoveTask()
    }

    private fun openBackgroundSettings(candidates: List<Intent>) {
        for (intent in candidates) {
            if (runCatching { backgroundSettings.launch(intent) }.isSuccess) return
        }
        Toast.makeText(this, "请在系统设置中手动管理此应用", Toast.LENGTH_SHORT).show()
    }

    private fun openAutoStartSettings() {
        openBackgroundSettings(BackgroundAuthorization.autoStartSettings(this))
    }

    private fun completeBackgroundSetup() {
        // This is the user's acknowledgement, not an automatic reading of the OEM setting.
        if (prefs.edit().putBoolean("background_setup_completed", true).commit()) {
            showBackgroundSetup = false
            refreshBackgroundAuthorization()
        } else Toast.makeText(this, "授权配置保存失败，请重试", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !BackgroundAuthorization.notificationPermission(this) &&
            (!askedForNotifications || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            askedForNotifications = true
            backgroundNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else openBackgroundSettings(BackgroundAuthorization.notificationSettings(this))
    }

    private fun requestWifiNamePermission() {
        if (!BackgroundAuthorization.fineLocation(this)) {
            if (askedForWifiPermission && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                openBackgroundSettings(listOf(BackgroundAuthorization.appDetails(this)))
            } else {
                askedForWifiPermission = true
                wifiNamePermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
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
        ModuleBridge.synchronize()
    }
    private fun requestLogExport() {
        DiagnosticLog.event("diagnostic.export_begin", 0)
        BackgroundLoginService.recordState(this)
        ModuleBridge.recordState()
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
