package com.dsh.videolink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dsh.videolink.CrashLog
import com.dsh.videolink.R
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.dsh.videolink.data.AppSettings
import com.dsh.videolink.data.HistoryStore
import com.dsh.videolink.extract.Http
import com.dsh.videolink.extract.RoutePlan
import com.dsh.videolink.extract.YtDlpEngine
import android.net.Uri
import android.content.Intent
import kotlinx.coroutines.launch
import com.dsh.videolink.ui.glass.Spinner
import com.dsh.videolink.ui.glass.GlassTextField
import androidx.compose.runtime.rememberCoroutineScope
import com.dsh.videolink.ui.glass.ActionChip
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.ui.glass.GlassCard
import com.dsh.videolink.ui.glass.GlassPill
import com.dsh.videolink.ui.glass.LiquidSlider
import com.dsh.videolink.ui.glass.LiquidToggle
import com.dsh.videolink.ui.glass.Spinner
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    settings: AppSettings,
    history: HistoryStore,
    onOpenDonate: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    var hasCrashLog by remember { mutableStateOf(CrashLog.exists(context)) }
    var updatingEngine by remember { mutableStateOf(false) }
    var scanningOnline by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.glassStrength) {
        delay(400)
        settings.persistGlassStrength()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText(text = "设置", style = AppText.hero, color = colors.text)
            AppText(text = "主题、玻璃强度与下载偏好", style = AppText.body, color = colors.textSecondary)
        }

        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AppText(text = "主题", style = AppText.label, color = colors.textSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeOption(
                        label = "跟随系统",
                        selected = settings.themeMode == AppSettings.THEME_SYSTEM,
                        onClick = { settings.setTheme(AppSettings.THEME_SYSTEM) },
                        backdrop = backdrop
                    )
                    ThemeOption(
                        label = "浅色",
                        selected = settings.themeMode == AppSettings.THEME_LIGHT,
                        onClick = { settings.setTheme(AppSettings.THEME_LIGHT) },
                        backdrop = backdrop
                    )
                    ThemeOption(
                        label = "深色",
                        selected = settings.themeMode == AppSettings.THEME_DARK,
                        onClick = { settings.setTheme(AppSettings.THEME_DARK) },
                        backdrop = backdrop
                    )
                }

                AppText(
                    text = "强调色",
                    style = AppText.label,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AccentOptions.forEachIndexed { index, option ->
                        val selected = settings.accentIndex == index
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(option.accent)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) colors.text else Color.White.copy(alpha = 0.35f),
                                    shape = CircleShape
                                )
                                .clickable { settings.updateAccentIndex(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        }

        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppText(
                        text = "液态玻璃强度",
                        style = AppText.label,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    GlassPill(text = "×" + String.format("%.1f", settings.glassStrength))
                }
                LiquidSlider(
                    value = { settings.glassStrength },
                    onValueChange = { settings.previewGlassStrength(it) },
                    valueRange = GLASS_STRENGTH_MIN..GLASS_STRENGTH_MAX,
                    visibilityThreshold = 0.005f,
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
                AppText(
                    text = "长按底部标签栏左右滑动，能看到玻璃透镜跟手折射。",
                    style = AppText.caption,
                    color = colors.textSecondary
                )
            }
        }

        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ToggleRow(
                    title = "自动读取剪贴板",
                    subtitle = "打开应用时自动填入剪贴板里的链接",
                    selected = settings.autoClipboard,
                    onSelect = { settings.updateAutoClipboard(it) },
                    backdrop = backdrop
                )
                ToggleRow(
                    title = "播放时保持屏幕常亮",
                    subtitle = "看视频时不会自动息屏",
                    selected = settings.keepScreenOn,
                    onSelect = { settings.updateKeepScreenOn(it) },
                    backdrop = backdrop
                )
            }
        }

        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppText(text = "关于", style = AppText.title, color = colors.text)
                AppText(
                    text = "Fetch 5.0.0\n解析和下载全部在本机完成，不会上传你的链接、视频或隐私数据。",
                    style = AppText.caption,
                    color = colors.textSecondary
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (updatingEngine || scanningOnline) {
                        Spinner(size = 16.dp, strokeWidth = 2.dp)
                    } else {
                        ActionChip(
                            iconRes = R.drawable.ic_bolt,
                            label = "引擎更新",
                            onClick = {
                                updatingEngine = true
                                scope.launch {
                                    val message = YtDlpEngine.updateEngine(context)
                                    updatingEngine = false
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            },
                            tint = colors.accent
                        )
                    }
                }
                AppText(
                    text = "站点突然解析不了？更新一下解析引擎再试。",
                    style = AppText.caption,
                    color = colors.textSecondary
                )

                // 只有真的崩过才显示，平时不打扰
                if (hasCrashLog) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip(
                            iconRes = R.drawable.ic_share,
                            label = "发送崩溃日志",
                            onClick = {
                                val log = CrashLog.read(context)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Fetch 崩溃日志")
                                    putExtra(Intent.EXTRA_TEXT, log.ifBlank { "（日志为空）" })
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(intent, "发送崩溃日志"))
                                }
                            },
                            tint = colors.warning
                        )
                        ActionChip(
                            iconRes = R.drawable.ic_delete,
                            label = "清除",
                            onClick = {
                                CrashLog.clear(context)
                                hasCrashLog = false
                            }
                        )
                    }
                }
            }
        }

        // 赞赏：单独一屏
        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    AppText(text = "赞赏支持", style = AppText.label, color = colors.text)
                    AppText(
                        text = "请我喝杯咖啡，二维码在下一页",
                        style = AppText.caption,
                        color = colors.textSecondary
                    )
                }
                ActionChip(
                    iconRes = R.drawable.ic_share,
                    label = "赞赏码",
                    onClick = onOpenDonate,
                    tint = colors.accent
                )
            }
        }
    }
}

/** 二次确认弹窗。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(24.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.glassStrongSurface)
                .border(1.dp, colors.glassBorder, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppText(text = title, style = AppText.title, color = colors.text)
            AppText(text = message, style = AppText.body, color = colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(
                    label = "取消",
                    onClick = onDismiss,
                    background = colors.glassSurface,
                    textColor = colors.text
                )
                DialogButton(
                    label = confirmText,
                    onClick = onConfirm,
                    background = colors.warning.copy(alpha = 0.85f),
                    textColor = Color.White
                )
            }
        }
    }
}

@Composable
private fun RowScope.DialogButton(
    label: String,
    onClick: () -> Unit,
    background: Color,
    textColor: Color
) {
    Box(
        Modifier
            .weight(1f)
            .clip(Capsule())
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        AppText(text = label, style = AppText.label, color = textColor, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RowScope.ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    backdrop: Backdrop
) {
    val colors = LocalAppColors.current
    val glassStrength = LocalGlassStrength.current

    Box(
        Modifier
            .weight(1f)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(6f.dp.toPx() * glassStrength)
                    lens(6f.dp.toPx() * glassStrength, 12f.dp.toPx() * glassStrength)
                },
                highlight = { Highlight.Default.copy(alpha = 0.5f) },
                onDrawSurface = {
                    drawRect(
                        if (selected) colors.accent.copy(alpha = 0.6f) else colors.glassSurface
                    )
                }
            )
            .clip(Capsule())
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        AppText(
            text = label,
            style = AppText.label,
            color = if (selected) Color.White else colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop
) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            AppText(text = title, style = AppText.label, color = colors.text, maxLines = 1)
            AppText(text = subtitle, style = AppText.caption, color = colors.textSecondary, maxLines = 2)
        }
        LiquidToggle(
            selected = { selected },
            onSelect = onSelect,
            backdrop = backdrop
        )
    }
}
