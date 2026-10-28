package local.portal.autologin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ShareAccountsPage(accounts: List<SavedAccountBook.Account>, onBack: () -> Unit,
                               openBrowser: (String) -> Unit) {
    val shareableAccounts = accounts.filterNot { it.bundled }
    var selected by remember { mutableStateOf<SavedAccountBook.Account?>(null) }
    var project by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val confirm = remember { mutableStateOf(false) }
    BackHandler { if (confirm.value) confirm.value = false else onBack() }
    val color = MiuixTheme.colorScheme.onBackground
    Scaffold(topBar = {
        SmallTopAppBar(title = "帮助我们", navigationIcon = {
            IconButton(onClick = onBack, minWidth = 52.dp, minHeight = 48.dp,
                modifier = Modifier.semantics { contentDescription = "返回" }) {
                Canvas(Modifier.size(20.dp)) {
                    val a=Offset(size.width*.65f,size.height*.15f)
                    val b=Offset(size.width*.3f,size.height*.5f)
                    val c=Offset(size.width*.65f,size.height*.85f)
                    drawLine(color,a,b,2.dp.toPx(),StrokeCap.Round);drawLine(color,b,c,2.dp.toPx(),StrokeCap.Round)
                }
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shareableAccounts.isEmpty()) item {
                Text("暂无保存的配置", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp))
            }
            items(shareableAccounts, key = { it.username }) { account ->
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(start=16.dp,end=8.dp,top=6.dp,bottom=6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(account.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        TextButton(text = "分享", onClick = {
                            selected = account; project = ""; error = null; confirm.value = true
                        }, minHeight = 40.dp, insideMargin = PaddingValues(horizontal=12.dp),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent,
                                textColor = MiuixTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
    val account = selected?.takeUnless { it.bundled }
    if (account != null) SuperDialog(show = confirm, title = "分享配置",
        onDismissRequest = { confirm.value = false }) {
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = project, onValueChange = { project = it.replace('\n',' ').replace('\r',' ').take(80); error=null },
                label = "项目名称（必填）", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = account.username, onValueChange = {}, label = "账号", readOnly = true,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = account.password, onValueChange = {}, label = "密码", readOnly = true,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("账号密码将填入公开 GitHub Issue，发布后所有人可见。", fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            error?.let { Text(it,fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(text="取消", onClick={confirm.value=false}, modifier=Modifier.weight(1f),
                    insideMargin=PaddingValues(horizontal=8.dp,vertical=12.dp))
                TextButton(text="确认并前往", enabled=project.isNotBlank(), modifier=Modifier.weight(1f),
                    colors=ButtonDefaults.textButtonColorsPrimary(), insideMargin=PaddingValues(horizontal=8.dp,vertical=12.dp), onClick={
                        runCatching { ProjectLinks.issueDraft(project,account.username,account.password) }
                            .onSuccess { url -> confirm.value=false;openBrowser(url) }
                            .onFailure { error="配置内容无效或过长，请检查后重试" }
                    })
            }
        }
    }
}
