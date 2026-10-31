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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
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
    Box {
        IconButton(onClick = onOpen, backgroundColor = Color.Transparent, minWidth = 58.dp, minHeight = 48.dp,
            modifier = Modifier.semantics { contentDescription = "选择已保存账号" }) {
            AccountPickerArrow(color)
        }
        ListPopup(show = show, onDismissRequest = { show.value = false }, maxHeight = 300.dp,
            enableWindowDim = false) {
            ListPopupColumn {
                key(show.value) {
                    AccountList(accounts, fetching, feedback, onRefresh, onSelect, onNew, onDelete)
                }
            }
        }
    }
}

@Composable
internal fun AccountList(accounts: List<SavedAccountBook.Account>, fetching: Boolean, feedback: String?,
                         onRefresh: () -> Unit, onSelect: (SavedAccountBook.Account) -> Unit,
                         onNew: () -> Unit, onDelete: (SavedAccountBook.Account) -> Unit,
                         selectedUsername: String? = null, showHeader: Boolean = true) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantActions
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Column {
        if (showHeader) AccountListHeader(fetching, feedback,
            onRefresh = { pendingDelete = null; onRefresh() },
            onNew = { pendingDelete = null; onNew() })
        if (accounts.isEmpty()) {
            Text("暂无保存账号", fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(16.dp))
        }
        accounts.sortedBy { it.bundled }.forEach { account ->
            key(account.username) {
            Row(Modifier.fillMaxWidth()
                .background(if (account.username == selectedUsername) MiuixTheme.colorScheme.primary.copy(alpha = .1f)
                    else Color.Transparent, RoundedCornerShape(12.dp))
                .then(if (selectedUsername != null) Modifier.semantics { selected = account.username == selectedUsername } else Modifier)
                .clickable(role = Role.Button) { pendingDelete = null; onSelect(account) }
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

/** Shared by the expanded list and the input card; title geometry stays identical. */
@Composable
internal fun AccountListHeader(fetching: Boolean, feedback: String?, onRefresh: () -> Unit,
                               onNew: () -> Unit, onSelectPresets: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(feedback ?: "使用其他账号", fontSize = 14.sp, fontWeight = FontWeight.Normal,
            color = if (onSelectPresets != null) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
                .then(if (onSelectPresets == null) Modifier.clickable(role = Role.Button, onClick = onNew) else Modifier)
                .padding(vertical = 12.dp))
        if (onSelectPresets != null) {
            Button(onClick = onSelectPresets, minWidth = 40.dp, minHeight = 40.dp,
                insideMargin = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(color = Color.Transparent)) {
                Text("选择预设", fontSize = 14.sp, fontWeight = FontWeight.Normal,
                    color = MiuixTheme.colorScheme.primary)
            }
        } else {
            val rotation = if (fetching) {
                val transition = rememberInfiniteTransition(label = "presetRefresh")
                val angle by transition.animateFloat(0f, 360f,
                    infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rotation")
                angle
            } else 0f
            IconButton(onClick = onRefresh, enabled = !fetching,
                backgroundColor = Color.Transparent, minWidth = 40.dp, minHeight = 40.dp) {
                Icon(MiuixIcons.Useful.Refresh, contentDescription = "获取最新预设",
                    modifier = Modifier.size(18.dp).rotate(rotation),
                    tint = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AccountPickerArrow(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val a = Offset(size.width * .15f, size.height * .35f)
        val b = Offset(size.width * .5f, size.height * .7f)
        val c = Offset(size.width * .85f, size.height * .35f)
        drawLine(color, a, b, 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, b, c, 2.dp.toPx(), StrokeCap.Round)
    }
}
