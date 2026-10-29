package local.portal.autologin

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Centered, user-acknowledged setup; system settings remain the source of real permission grants. */
@Composable
internal fun BackgroundSetupPrompt(notificationsAllowed: Boolean, onDismiss: () -> Unit, onNotifications: () -> Unit,
    onAutoStart: () -> Unit, onComplete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("后台登录授权", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("请允许以下权限，完成后点击“我已授权”。", fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SetupPermissionRow("通知权限", onNotifications, granted = notificationsAllowed)
                    SetupPermissionRow("自启动权限", onAutoStart)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    TextButton(text = "我已授权", onClick = onComplete, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
private fun SetupPermissionRow(title: String, onAuthorize: () -> Unit, granted: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        TextButton(text = if (granted) "已授权" else "去授权", enabled = !granted,
            onClick = onAuthorize, minHeight = 40.dp,
            insideMargin = PaddingValues(horizontal = 12.dp))
    }
}
