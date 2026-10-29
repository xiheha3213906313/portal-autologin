package local.portal.autologin

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
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
    val notes = remember(release.notes) { ReleaseNotesText.display(release.notes) }
    SuperDialog(show = show, title = if (updates.ready != null) "更新已就绪 ${release.version}" else "发现新版本 ${release.version}",
        summary = "当前版本 $currentVersion", onDismissRequest = { show.value = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (notes.isNotBlank()) Text(notes, fontSize=14.sp,
                modifier=Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()))
            if (!release.notesComplete) TextButton(text="部分记录未加载，查看全部更新",
                modifier=Modifier.fillMaxWidth(), onClick={openBrowser(ProjectLinks.RELEASES_PAGE)},
                colors=ButtonDefaults.textButtonColors(color=Color.Transparent))
            updates.downloadError?.let { Text(it,fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            if (release.apk == null) Text("此版本未提供可直接安装的 APK，请前往发布页下载。",fontSize=13.sp,
                color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                DownloadButton(text=when {
                    updates.downloading -> if(updates.progress<0) "下载中…" else "下载 ${updates.progress}%"
                    updates.ready != null -> "安装更新"
                    else -> "应用内更新"
                }, enabled=!updates.downloading && release.apk!=null, modifier=Modifier.weight(1f),
                    downloading=updates.downloading, progress=updates.progress, onClick={
                        updates.ready?.let(install) ?: updates.download()
                    })
                TextButton(text="浏览器下载",modifier=Modifier.weight(1f),
                    insideMargin=PaddingValues(horizontal=8.dp,vertical=12.dp),onClick={openBrowser(release.page)})
            }
            TextButton(text="关闭",modifier=Modifier.fillMaxWidth(),onClick={show.value=false})
        }
    }
}

@Composable
private fun DownloadButton(text: String, enabled: Boolean, downloading: Boolean, progress: Int,
                           modifier: Modifier = Modifier, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    val fraction by animateFloatAsState((progress.coerceIn(0, 100) / 100f), label = "downloadProgress")
    val sweep = if (downloading && progress < 0) {
        val transition = rememberInfiniteTransition(label = "downloadWaiting")
        transition.animateFloat(-.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep").value
    } else 0f
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .background(primary.copy(alpha = if (enabled || downloading) 1f else .35f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics {
            if (downloading) progressBarRangeInfo = if (progress < 0) ProgressBarRangeInfo.Indeterminate
                else ProgressBarRangeInfo(progress.coerceIn(0, 100).toFloat(), 0f..100f)
        }, contentAlignment = Alignment.Center) {
        if (downloading) Canvas(Modifier.matchParentSize()) {
            if (progress < 0) drawRect(Color.White.copy(alpha = .22f),
                topLeft = Offset(size.width * sweep, 0f), size = Size(size.width * .35f, size.height))
            else drawRect(Color.White.copy(alpha = .22f), size = Size(size.width * fraction, size.height))
        }
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
    }
}
