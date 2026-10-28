package local.portal.autologin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@Composable
internal fun UpdateCard(show: MutableState<Boolean>, updates: AppUpdates, currentVersion: String,
                        install: (File) -> Unit, openBrowser: (String) -> Unit) {
    val release = updates.release ?: return
    SuperDialog(show = show, title = "发现新版本 ${release.version}",
        summary = "当前版本 $currentVersion", onDismissRequest = { show.value = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (release.notes.isNotBlank()) Text(release.notes, fontSize=14.sp,
                modifier=Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()))
            if (updates.downloading) {
                val primary=MiuixTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawRect(primary.copy(alpha=.12f))
                    drawRect(primary,size=Size(size.width*(updates.progress.coerceAtLeast(0)/100f),size.height))
                }
            }
            updates.downloadError?.let { Text(it,fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            if (release.apk == null) Text("此版本未提供可直接安装的 APK，请前往发布页下载。",fontSize=13.sp,
                color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TextButton(text=when {
                    updates.downloading -> if(updates.progress<0) "下载中…" else "下载 ${updates.progress}%"
                    updates.ready != null -> "安装更新"
                    else -> "应用内更新"
                }, enabled=!updates.downloading && release.apk!=null, modifier=Modifier.weight(1f),
                    colors=ButtonDefaults.textButtonColorsPrimary(), insideMargin=PaddingValues(horizontal=8.dp,vertical=12.dp), onClick={
                        updates.ready?.let(install) ?: updates.download()
                    })
                TextButton(text="浏览器下载",modifier=Modifier.weight(1f),
                    insideMargin=PaddingValues(horizontal=8.dp,vertical=12.dp),onClick={openBrowser(release.page)})
            }
            TextButton(text="关闭",modifier=Modifier.fillMaxWidth(),onClick={show.value=false})
        }
    }
}
