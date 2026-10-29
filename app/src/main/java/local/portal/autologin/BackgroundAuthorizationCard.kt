package local.portal.autologin

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BackgroundAuthorizationCard(show: MutableState<Boolean>, context: Context, revision: Int,
    needsWifiName: Boolean, requestNotifications: () -> Unit, requestLocation: () -> Unit,
    requestAutoStart: () -> Unit, openSettings: (List<Intent>) -> Unit) {
    if (!show.value) return
    // Refresh after returning from system settings, without a timer or a permission polling loop.
    val grants = remember(revision, needsWifiName) {
        listOf(BackgroundAuthorization.notificationsVisible(context), BackgroundAuthorization.fineLocation(context),
            BackgroundAuthorization.locationEnabled(context), BackgroundAuthorization.batteryExempt(context),
            BackgroundAuthorization.accessibilityEnabled(context))
    }
    SuperDialog(show = show, title = "后台授权", onDismissRequest = { show.value = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(BackgroundLoginService.stateText(), fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                AuthorizationGroup("必选授权") {
                    AuthorizationRow("通知", "显示后台运行状态", grants[0], requestNotifications)
                    AuthorizationRow("自启动", "在系统设置中允许自启动", false,
                        requestAutoStart, pendingText = "去设置")
                    if (needsWifiName) {
                        AuthorizationRow("精确定位", "匹配 Wi-Fi 名称，不读取坐标", grants[1], requestLocation)
                        AuthorizationRow("系统定位", "读取 Wi-Fi 名称需要开启", grants[2],
                            { openSettings(listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))) }, grantedText = "已开启")
                    }
                }
                Spacer(Modifier.height(6.dp))
                AuthorizationGroup("可选授权") {
                    AuthorizationRow("电源策略", "取消电池优化；厂商后台策略需手动确认", grants[3],
                        { openSettings(BackgroundAuthorization.batterySettings(context)) }, grantedText = "已豁免")
                    AuthorizationRow("无障碍辅助", "接入时尝试恢复监听，不读取屏幕，不保证保活", grants[4],
                        { openSettings(BackgroundAuthorization.accessibilitySettings(context)) })
                }
                Text("自启动和辅助授权不能替代前台服务。需先保存登录账号。", fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(text = "完成", onClick = { show.value = false }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun AuthorizationGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp))
        Card(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer),
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Column(content = content)
        }
    }
}

@Composable
private fun AuthorizationRow(title: String, summary: String, granted: Boolean, onClick: () -> Unit,
    grantedText: String = "已授权", pendingText: String = "去授权") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp)
            Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        TextButton(text = if (granted) grantedText else pendingText, enabled = !granted, onClick = onClick,
            minHeight = 40.dp, insideMargin = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
    }
}
