package local.portal.autologin

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Uses the result page layout, with the same account list as Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickStartPage(store: AccountStore, presets: AccountPresetUpdates,
                            moduleActive: Boolean, xiaomiSystem: Boolean,
                            onStart: (String, String) -> String?, onSettings: () -> Unit) {
    var accounts by remember { mutableStateOf(store.accounts) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var selectedUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(if (store.initializationFailed) "配置读取失败，请重试" else null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selected = accounts.firstOrNull { it.username == selectedUsername }
    val valid = if (manual) username.isNotBlank() && password.isNotEmpty() else selected != null
    val start = {
        if (valid) {
            focus.clearFocus()
            val user = if (manual) username else selected!!.username
            val pass = if (manual) password else selected!!.password
            error = onStart(user, pass)
        }
    }
    val selectPresets = {
        focus.clearFocus()
        keyboard?.hide()
        showPassword = false
        manual = false
        error = null
    }
    BackHandler(enabled = manual) { selectPresets() }
    LaunchedEffect(presets.revision) { accounts = store.accounts }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeBottom = imeInsets.getBottom(density)
    val imeMotionHeight = maxOf(WindowInsets.imeAnimationSource.getBottom(density),
        WindowInsets.imeAnimationTarget.getBottom(density))
    // Follow the system keyboard's own progress in both directions, without a second animation.
    val compact = if (imeMotionHeight > 0) (imeBottom.toFloat() / imeMotionHeight).coerceIn(0f, 1f) else 0f
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    // Resolve both edges in the same layout pass. Mixing composition-time IME height with
    // imePadding's newer layout-time value can move the button below its resting position.
    val contentInsets = remember(systemInsets, imeInsets) {
        systemInsets.only(WindowInsetsSides.Bottom).add(WindowInsets(bottom = 104.dp))
            .union(imeInsets.only(WindowInsetsSides.Bottom).add(WindowInsets(bottom = 16.dp)))
    }
    val listScroll = rememberScrollState()
    val formScroll = rememberScrollState()

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
        .windowInsetsPadding(systemInsets)) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 488.dp).fillMaxSize()
            .windowInsetsPadding(contentInsets).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height((32f - 20f * compact).dp))
            QuickStartHeader(compact)
            Spacer(Modifier.height((24f - 8f * compact).dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Keep one background. Animate its measured content, so its bottom edge never
                // jumps between independently animated outgoing and incoming cards.
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    Column {
                        AccountListHeader(fetching = presets.showRefreshProgress,
                            feedback = if (manual) null else presets.feedback,
                            onRefresh = { presets.refresh() },
                            onNew = { manual = true; error = null },
                            onSelectPresets = if (manual) ({ selectPresets() }) else null)
                        Box(Modifier.fillMaxWidth().animateContentSize(
                            tween(220, easing = FastOutSlowInEasing), alignment = Alignment.TopStart)) {
                            if (manual) {
                                Column(Modifier.verticalScroll(formScroll)
                                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextField(value = username, onValueChange = { username = it; error = null },
                                        label = "账号", singleLine = true, modifier = Modifier.fillMaxWidth(),
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
                                    TextField(value = password, onValueChange = { password = it; error = null },
                                        label = "密码", singleLine = true, modifier = Modifier.fillMaxWidth(),
                                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { start() }),
                                        trailingIcon = {
                                            TextButton(text = if (showPassword) "隐藏" else "显示",
                                                onClick = { showPassword = !showPassword }, minHeight = 48.dp,
                                                colors = ButtonDefaults.textButtonColors(color = Color.Transparent),
                                                insideMargin = PaddingValues(horizontal = 12.dp))
                                        })
                                }
                            } else {
                                Column(Modifier.verticalScroll(listScroll)) {
                                    AccountList(accounts = accounts,
                                        fetching = presets.showRefreshProgress, feedback = presets.feedback,
                                        onRefresh = { presets.refresh() },
                                        onSelect = { account ->
                                            selectedUsername = account.username; error = null
                                        },
                                        onNew = { manual = true; error = null },
                                        onDelete = { account ->
                                            if (!account.bundled && store.remove(account.username)) {
                                                accounts = store.accounts
                                                if (selectedUsername == account.username) selectedUsername = null
                                                error = null
                                            } else error = "删除失败，请重试"
                                        }, selectedUsername = selectedUsername, showHeader = false)
                                }
                            }
                        }
                    }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error.orEmpty(), fontSize = 14.sp, textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(text = "保存并登录", onClick = { start() }, enabled = valid,
                modifier = Modifier.fillMaxWidth(), minHeight = 54.dp,
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(text = "设置", onClick = { focus.clearFocus(); onSettings() },
                minHeight = 48.dp, colors = ButtonDefaults.textButtonColors(color = Color.Transparent))
            ModuleStatusHint(moduleActive, xiaomiSystem)
        }
    }
}

/** Interpolates real positions, so the same artwork and text move into a compact row. */
@Composable
private fun QuickStartHeader(compact: Float) {
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Image(painterResource(R.drawable.ic_portal_monochrome), contentDescription = null,
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
            modifier = Modifier.background(MiuixTheme.colorScheme.primary.copy(alpha = .1f), CircleShape))
        Text("快速开始", fontSize = (32f - 6f * compact).sp, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground)
        Text("你可以选择已有预设", fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }) { items, constraints ->
        fun between(expanded: Int, collapsed: Int) = (expanded + (collapsed - expanded) * compact).roundToInt()
        val width = constraints.maxWidth
        val iconSize = between(128.dp.roundToPx(), 64.dp.roundToPx()).coerceAtMost(width)
        val textStart = 80.dp.roundToPx().coerceAtMost((width - 1).coerceAtLeast(0))
        val textWidth = (width - textStart * compact).roundToInt().coerceAtLeast(1)
        val icon = items[0].measure(Constraints.fixed(iconSize, iconSize))
        val title = items[1].measure(Constraints(maxWidth = textWidth))
        val subtitle = items[2].measure(Constraints(maxWidth = textWidth))
        val expandedTitleTop = 156.dp.roundToPx()
        val expandedGap = 10.dp.roundToPx()
        val compactGap = 6.dp.roundToPx()
        val compactHeight = maxOf(64.dp.roundToPx(), title.height + compactGap + subtitle.height)
        val height = between(expandedTitleTop + title.height + expandedGap + subtitle.height, compactHeight)
        val compactTitleTop = (compactHeight - title.height - compactGap - subtitle.height) / 2
        val titleTop = between(expandedTitleTop, compactTitleTop)
        val subtitleTop = titleTop + title.height + between(expandedGap, compactGap)
        layout(width, height) {
            icon.placeRelative(between((width - icon.width) / 2, 0), between(0, (compactHeight - 64.dp.roundToPx()) / 2))
            title.placeRelative(between((width - title.width) / 2, textStart), titleTop)
            subtitle.placeRelative(between((width - subtitle.width) / 2, textStart), subtitleTop)
        }
    }
}
