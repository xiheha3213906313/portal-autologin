package local.portal.autologin

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.*

class SettingsActivity : ComponentActivity() {
    private var pendingLog: String? = null
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
        val prefs = try {
            getSharedPreferences("portal", Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) { null }
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsPage(prefs)
            }
        }
    }

    @Composable
    private fun SettingsPage(prefs: SharedPreferences?) {
        var username by remember { mutableStateOf(prefs?.getString("username", "") ?: "") }
        var password by remember { mutableStateOf(prefs?.getString("password", "") ?: "") }
        var enabled by remember { mutableStateOf(prefs?.getBoolean("enabled", false) ?: false) }
        var fast by remember { mutableStateOf(prefs?.getBoolean("fast", true) ?: true) }
        var silent by remember { mutableStateOf(prefs?.getBoolean("silent", false) ?: false) }
        var debugLog by remember { mutableStateOf(prefs?.getBoolean("debug_log", false) ?: false) }
        var showPassword by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val confirmClear = remember { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        val usable = prefs != null
        val save = {
            if (prefs == null) {
                error = "请先在 LSPosed 启用模块"
            } else if (enabled && (username.isBlank() || password.isEmpty())) {
                error = "请输入账号和密码"
            } else {
                val ok = prefs.edit().putString("username", username.trim())
                    .putString("password", password).putBoolean("enabled", enabled)
                    .putBoolean("fast", fast).putBoolean("silent", silent).commit()
                if (ok) {
                    notifyConfigChanged()
                    error = null
                    focus.clearFocus()
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                } else error = "保存失败，请重试"
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = "门户登录", largeTitle = "门户登录",
                modifier = Modifier.offset(y = (-12).dp))
        }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!usable || error != null) {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                        Text(error ?: "请先在 LSPosed 启用模块，再重新打开此应用")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "自动登录", checked = enabled, enabled = usable,
                        onCheckedChange = { enabled = it })
                    SuperSwitch(title = "快速填写", checked = fast, enabled = usable,
                        onCheckedChange = { fast = it })
                    SuperSwitch(title = "静默登录", checked = silent, enabled = usable,
                        summary = if (silent) "实验功能：勾选系统框架作用域后重启" else null,
                        onCheckedChange = { silent = it })
                }
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(value = username, onValueChange = { username = it; error = null },
                            label = "账号", singleLine = true, enabled = usable,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = {
                                focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            }))
                        TextField(value = password, onValueChange = { password = it; error = null },
                            label = "密码", singleLine = true, enabled = usable,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            trailingIcon = {
                                TextButton(text = if (showPassword) "隐藏" else "显示",
                                    enabled = usable, onClick = { showPassword = !showPassword },
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent),
                                    minHeight = 48.dp, insideMargin = PaddingValues(horizontal = 12.dp))
                            })
                    }
                }
                TextButton(text = "保存", onClick = { save() }, enabled = usable,
                    modifier = Modifier.fillMaxWidth(), minHeight = 54.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary())
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(title = "诊断日志", checked = debugLog, enabled = usable,
                        summary = if (debugLog) "即时生效，复现问题后导出" else null,
                        onCheckedChange = { value ->
                            if (prefs?.edit()?.putBoolean("debug_log", value)?.commit() == true) {
                                debugLog = value
                                sendBroadcast(Intent(SilentPortal.LOG_CONFIG).setPackage("android"))
                            } else error = "日志设置保存失败"
                        })
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "导出日志", onClick = { requestLogExport() }, enabled = usable,
                            modifier = Modifier.weight(1f), minHeight = 48.dp,
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
                        TextButton(text = "清空日志", onClick = { clearLogs() }, enabled = usable,
                            modifier = Modifier.weight(1f), minHeight = 48.dp,
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
                    }
                }
                TextButton(text = "清除账号", onClick = { focus.clearFocus(); confirmClear.value = true },
                    enabled = usable, modifier = Modifier.align(Alignment.CenterHorizontally),
                    minHeight = 48.dp, colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
            }
        }
        SuperDialog(show = confirmClear, title = "清除账号？", summary = "同时关闭自动登录",
            onDismissRequest = { confirmClear.value = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = { confirmClear.value = false }, modifier = Modifier.weight(1f))
                TextButton(text = "清除", modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                        if (prefs?.edit()?.clear()?.commit() == true) {
                            username = ""; password = ""; enabled = false; fast = true; silent = false; debugLog = false
                            notifyConfigChanged()
                            showPassword = false; error = null
                            confirmClear.value = false
                            Toast.makeText(this@SettingsActivity, "已清除", Toast.LENGTH_SHORT).show()
                        } else {
                            confirmClear.value = false; error = "清除失败，请重试"
                        }
                    })
            }
        }
    }
    private fun notifyConfigChanged() {
        sendBroadcast(Intent(SilentPortal.ACTION).setPackage("android"))
    }
    private fun requestLogExport() {
        sendOrderedBroadcast(Intent(SilentPortal.LOG_EXPORT).setPackage("android"), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (isFinishing || isDestroyed) return
                    if (resultCode != -1 || resultData == null) {
                        Toast.makeText(this@SettingsActivity, "系统日志未连接，请检查系统框架作用域并重启", Toast.LENGTH_LONG).show()
                        return
                    }
                    pendingLog = resultData
                    exportLog.launch("portal-debug-${System.currentTimeMillis()}.txt")
                }
            }, null, 0, null, null)
    }
    private fun clearLogs() {
        sendOrderedBroadcast(Intent(SilentPortal.LOG_CLEAR).setPackage("android"), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Toast.makeText(this@SettingsActivity,
                        if (resultCode == -1) "日志已清空" else "系统日志未连接，请重启后重试", Toast.LENGTH_SHORT).show()
                }
            }, null, 0, null, null)
    }
}
