package local.portal.autologin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AccountPicker(show: MutableState<Boolean>, accounts: List<SavedAccountBook.Account>,
                           fetching: Boolean, feedback: String?, onRefresh: () -> Unit,
                           onOpen: () -> Unit, onSelect: (SavedAccountBook.Account) -> Unit,
                           onNew: () -> Unit,
                           onDelete: (SavedAccountBook.Account) -> Unit) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantActions
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(show.value) { if (!show.value) pendingDelete = null }
    Box {
        IconButton(onClick = { pendingDelete = null; onOpen() }, backgroundColor = Color.Transparent, minWidth = 58.dp, minHeight = 48.dp,
            modifier = Modifier.semantics { contentDescription = "选择已保存账号" }) {
            Canvas(Modifier.size(16.dp)) {
                val a = Offset(size.width * .15f, size.height * .35f)
                val b = Offset(size.width * .5f, size.height * .7f)
                val c = Offset(size.width * .85f, size.height * .35f)
                drawLine(color, a, b, 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, b, c, 2.dp.toPx(), StrokeCap.Round)
            }
        }
        ListPopup(show = show, onDismissRequest = { pendingDelete = null; show.value = false }, maxHeight = 300.dp,
            enableWindowDim = false) {
            ListPopupColumn {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(feedback ?: "使用其他账号", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { pendingDelete = null; onNew() }
                            .padding(vertical = 12.dp))
                    val rotation = if (fetching) {
                        val transition = rememberInfiniteTransition(label = "presetRefresh")
                        val angle by transition.animateFloat(0f, 360f,
                            infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rotation")
                        angle
                    } else 0f
                    IconButton(onClick = { pendingDelete = null; onRefresh() }, enabled = !fetching,
                        backgroundColor = Color.Transparent, minWidth = 40.dp, minHeight = 40.dp) {
                        Icon(MiuixIcons.Useful.Refresh, contentDescription = "获取最新预设",
                            modifier = Modifier.size(18.dp).rotate(rotation),
                            tint = MiuixTheme.colorScheme.primary)
                    }
                }
                if (accounts.isEmpty()) {
                    Text("暂无保存账号", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp))
                }
                accounts.sortedBy { it.bundled }.forEach { account ->
                    key(account.username) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { pendingDelete = null; onSelect(account) }
                        .padding(start = 14.dp, end = 6.dp).heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(account.displayName(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        Box(Modifier.width(64.dp).height(40.dp), contentAlignment = Alignment.CenterEnd) {
                        if (account.bundled) {
                            Text("预设", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 4.dp))
                        } else if (pendingDelete == account.username) {
                            Box(Modifier.size(width = 56.dp, height = 32.dp)
                                .background(Color(0xFFD9363E), RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { pendingDelete = null; onDelete(account) },
                                contentAlignment = Alignment.Center) {
                                Text("确认", fontSize = 12.sp, color = Color.White)
                            }
                        } else IconButton(onClick = { pendingDelete = account.username }, minWidth = 36.dp, minHeight = 36.dp,
                            backgroundColor = Color.Transparent,
                            modifier = Modifier.semantics { contentDescription = "删除" + account.displayName() }) {
                            Canvas(Modifier.size(16.dp)) {
                                val w=size.width;val h=size.height;val stroke=1.5.dp.toPx()
                                drawLine(color,Offset(.2f*w,.28f*h),Offset(.8f*w,.28f*h),stroke,StrokeCap.Round)
                                drawLine(color,Offset(.4f*w,.12f*h),Offset(.6f*w,.12f*h),stroke,StrokeCap.Round)
                                drawLine(color,Offset(.28f*w,.4f*h),Offset(.32f*w,.86f*h),stroke,StrokeCap.Round)
                                drawLine(color,Offset(.32f*w,.86f*h),Offset(.68f*w,.86f*h),stroke,StrokeCap.Round)
                                drawLine(color,Offset(.68f*w,.86f*h),Offset(.72f*w,.4f*h),stroke,StrokeCap.Round)
                                drawLine(color,Offset(.5f*w,.46f*h),Offset(.5f*w,.7f*h),stroke,StrokeCap.Round)
                            }
                        }
                        }
                    }
                    }
                }

            }
        }
    }
}
