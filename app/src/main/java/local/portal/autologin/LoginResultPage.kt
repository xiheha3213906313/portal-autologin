package local.portal.autologin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginResultPage(state: AppLoginController.UiState, login: AppLoginController, moduleActive: Boolean,
                    onSettings: () -> Unit, onRetry: () -> Unit, onWifi: () -> Unit) {
    val success = state.stage == AppLoginController.Stage.SUCCESS
    val connecting = state.stage == AppLoginController.Stage.CONNECTING
    val accent = if (success) Color(0xFF36B76B) else MiuixTheme.colorScheme.primary
    val foreground = MiuixTheme.colorScheme.onBackground
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding()) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp).offset(y = (-24).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            when {
                success -> SuccessMark(state.successId, login, accent)
                connecting -> ConnectingMark(accent)
                else -> Canvas(Modifier.size(128.dp).semantics { contentDescription = "连接未完成" }) {
                    drawCircle(accent.copy(alpha = .1f))
                    val width = 7.dp.toPx()
                    drawLine(accent, Offset(size.width * .5f, size.height * .27f),
                        Offset(size.width * .5f, size.height * .56f), width, StrokeCap.Round)
                    drawCircle(accent, width / 2, Offset(size.width * .5f, size.height * .7f))
                }
            }
            Text(state.message, fontSize = if (success) 30.sp else 22.sp,
                fontWeight = FontWeight.Medium, color = foreground)
            if (!success && !connecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "连接 Wi-Fi", onClick = onWifi, minHeight = 48.dp)
                    TextButton(text = "重试", onClick = onRetry, minHeight = 48.dp,
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(text = "设置", onClick = onSettings,
                minHeight = 48.dp, colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
            if (!moduleActive) Text("LSPosed 未启动，启用后可使用完整功能", fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun SuccessMark(id: Long, login: AppLoginController, color: Color) {
    val play = remember(id) { login.consumeSuccessAnimation(id) }
    val progress = remember(id) { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(id) { if (play) progress.animateTo(1f, tween(650)) }
    Canvas(Modifier.size(144.dp).semantics { contentDescription = "成功" }) {
        val p = progress.value
        val radius = size.minDimension / 2
        drawCircle(color.copy(alpha = .1f * p), radius * (.8f + .2f * p))
        drawCircle(color, radius * .86f, style = Stroke(3.dp.toPx()), alpha = p)
        val a = Offset(size.width * .28f, size.height * .51f)
        val b = Offset(size.width * .44f, size.height * .66f)
        val c = Offset(size.width * .73f, size.height * .35f)
        val line1 = (p / .35f).coerceIn(0f, 1f)
        val line2 = ((p - .35f) / .65f).coerceIn(0f, 1f)
        if (p > 0) drawLine(color, a, a + (b - a) * line1, 8.dp.toPx(), StrokeCap.Round)
        if (line2 > 0) drawLine(color, b, b + (c - b) * line2, 8.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun ConnectingMark(color: Color) {
    val transition = rememberInfiniteTransition(label = "connecting")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
    Canvas(Modifier.size(128.dp).semantics { contentDescription = "正在连接" }) {
        val inset = 12.dp.toPx()
        val area = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(color.copy(alpha = .12f), 0f, 360f, false,
            topLeft = Offset(inset, inset), size = area, style = Stroke(5.dp.toPx()))
        drawArc(color, angle, 90f, false, topLeft = Offset(inset, inset), size = area,
            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    }
}
