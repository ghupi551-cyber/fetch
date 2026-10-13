package com.dsh.videolink.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dsh.videolink.R
import com.dsh.videolink.data.AppSettings
import com.dsh.videolink.data.FavoritesStore
import com.dsh.videolink.data.HistoryStore
import com.dsh.videolink.download.DownloadStore
import com.dsh.videolink.extract.ExtractResult
import com.dsh.videolink.extract.MediaKind
import com.dsh.videolink.extract.VideoExtractor
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.extract.YtDlpEngine
import com.dsh.videolink.ui.glass.ActionChip
import com.dsh.videolink.ui.glass.AppIconButton
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.ui.glass.GlassCard
import com.dsh.videolink.ui.glass.GlassPill
import com.dsh.videolink.ui.glass.GlassTextField
import com.dsh.videolink.ui.glass.LiquidButton
import com.dsh.videolink.ui.glass.PlainCard
import com.dsh.videolink.ui.glass.Spinner
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun durationLabel(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

@Composable
fun HomeScreen(
    backdrop: Backdrop,
    settings: AppSettings,
    history: HistoryStore,
    favorites: FavoritesStore,
    input: String,
    onInputChange: (String) -> Unit,
    autoExtractUrl: String?,
    onAutoExtractConsumed: () -> Unit,
    onPlay: (VideoItem, String?, String?) -> Unit,
    onPlayAudio: (VideoItem, String?, String?) -> Unit,
    onOpenImages: (List<VideoItem>, Int, String?) -> Unit,
    onOpenDownloads: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (settings.autoClipboard && input.isBlank()) {
            val text = clipboard.getText()?.text
            val url = text?.let { VideoExtractor.findUrl(it) }
            if (!url.isNullOrBlank()) onInputChange(url)
        }
    }

    // 从历史点进来时直接开始解析
    LaunchedEffect(autoExtractUrl) {
        val url = autoExtractUrl
        if (!url.isNullOrBlank()) {
            onAutoExtractConsumed()
            ExtractStore.start(context, url, history)
        }
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
            AppText(text = "Fetch", style = AppText.hero, color = colors.text)
            AppText(
                text = "粘贴链接即可提取；别处解析出的 .mp4 直链也能直接粘进来下载",
                style = AppText.body,
                color = colors.textSecondary
            )
        }

        GlassTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = "粘贴视频链接：抖音 / 快手 / 小红书 / B 站 / 微博 / 直链 等",
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiquidButton(
                onClick = {
                    val text = clipboard.getText()?.text
                    if (text.isNullOrBlank()) {
                        Toast.makeText(context, "剪贴板是空的", Toast.LENGTH_SHORT).show()
                    } else {
                        onInputChange(text.trim())
                    }
                },
                backdrop = backdrop,
                modifier = Modifier.weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_paste),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(colors.text)
                )
                AppText(text = "粘贴", style = AppText.label, color = colors.text, maxLines = 1)
            }

            LiquidButton(
                onClick = {
                    onInputChange("")
                    ExtractStore.clear()
                },
                backdrop = backdrop,
                modifier = Modifier.weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(colors.text)
                )
                AppText(text = "清空", style = AppText.label, color = colors.text, maxLines = 1)
            }
        }

        LiquidButton(
            onClick = { ExtractStore.start(context, input.trim(), history) },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            tint = colors.accent
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bolt),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(Color.White)
            )
            AppText(text = "提取视频", style = AppText.label, color = Color.White, maxLines = 1)
        }

        when (val current = ExtractStore.state) {
            ExtractUiState.Idle -> Unit

            is ExtractUiState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spinner()
                AppText(
                    text = current.phase.ifBlank { "正在解析链接…" },
                    style = AppText.body,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                ActionChip(
                    label = "取消",
                    onClick = { ExtractStore.cancel() },
                    tint = colors.warning
                )
            }

            is ExtractUiState.Error -> GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppText(text = "没能提取到视频", style = AppText.title, color = colors.warning)
                    AppText(text = current.message, style = AppText.body, color = colors.textSecondary)
                    if (!current.detail.isNullOrBlank()) {
                        ActionChip(
                            iconRes = R.drawable.ic_copy,
                            label = "复制错误详情",
                            onClick = {
                                clipboard.setText(AnnotatedString(current.detail))
                                Toast.makeText(context, "已复制，可以发给开发者", Toast.LENGTH_SHORT).show()
                            },
                            tint = colors.accent
                        )
                    }
                }
            }

            is ExtractUiState.BatchSuccess -> {
                current.results.forEach { result ->
                    ResultPanel(
                        result = result,
                        backdrop = backdrop,
                        favorites = favorites,
                        onPlayAudio = onPlayAudio,
                        onOpenDownloads = onOpenDownloads,
                        onAudioOnly = {
                            DownloadStore.enqueueAudio(context, result.inputUrl, result.title)
                            Toast.makeText(context, "正在提取音频，去「下载」看进度", Toast.LENGTH_SHORT).show()
                            onOpenDownloads()
                        },
                        onPlay = onPlay,
                        onOpenImages = onOpenImages,
                        onDownload = { item ->
                            DownloadStore.enqueue(context, item, result.title)
                            Toast.makeText(context, "已开始下载，去「下载」看进度", Toast.LENGTH_SHORT).show()
                            onOpenDownloads()
                        },
                        onDownloadAll = { items ->
                            items.forEach { DownloadStore.enqueue(context, it, result.title) }
                            Toast.makeText(context, "已加入 ${items.size} 个下载任务", Toast.LENGTH_SHORT).show()
                            onOpenDownloads()
                        }
                    )
                }
                if (current.failures.isNotEmpty()) {
                    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppText(text = "有 ${current.failures.size} 条没成功", style = AppText.label, color = colors.warning)
                            current.failures.forEach { failure ->
                                AppText(text = "· $failure", style = AppText.caption, color = colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(
    result: ExtractResult,
    backdrop: Backdrop,
    favorites: FavoritesStore,
    onPlay: (VideoItem, String?, String?) -> Unit,
    onPlayAudio: (VideoItem, String?, String?) -> Unit,
    onOpenImages: (List<VideoItem>, Int, String?) -> Unit,
    onDownload: (VideoItem) -> Unit,
    onDownloadAll: (List<VideoItem>) -> Unit,
    onAudioOnly: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showAll by remember(result) { mutableStateOf(false) }
    var showUnfavoriteConfirm by remember(result) { mutableStateOf(false) }

    val images = result.items.filter { it.kind == MediaKind.IMAGE }
    val playable = result.items.filter { it.kind != MediaKind.IMAGE }
    // 图文作品优先把「图片」作为推荐项，避免下面冒出一个播放按钮
    val recommended = images.firstOrNull() ?: playable.firstOrNull() ?: result.items.firstOrNull()
    val others = result.items.filter { it !== recommended }

    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppText(
                    text = "提取结果",
                    style = AppText.label,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                if (result.items.size > 1) GlassPill(text = "${result.items.size} 个格式")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val saved = favorites.contains(result.inputUrl)
                ActionChip(
                    iconRes = if (saved) R.drawable.ic_star else R.drawable.ic_star_border,
                    label = if (saved) "已收藏" else "收藏",
                    onClick = {
                        if (saved) {
                            showUnfavoriteConfirm = true
                        } else {
                            favorites.toggle(result.inputUrl, result.title)
                            Toast.makeText(context, "已加入收藏", Toast.LENGTH_SHORT).show()
                        }
                    },
                    tint = if (saved) colors.warning else colors.text
                )
                if (playable.isNotEmpty() || result.items.any { it.kind == MediaKind.AUDIO }) {
                    ActionChip(
                        iconRes = R.drawable.ic_audio,
                        label = "只存音频",
                        onClick = { onAudioOnly() },
                        tint = colors.accent
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                result.poster?.let { poster ->
                    Box(
                        Modifier
                            .width(104.dp)
                            .height(62.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        RemoteImage(
                            url = poster,
                            headers = result.items.firstOrNull()?.headers ?: emptyMap(),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.title?.let { title ->
                        AppText(text = title, style = AppText.title, color = colors.text, maxLines = 3)
                    }
                    val meta = listOfNotNull(
                        result.uploader?.takeIf { it.isNotBlank() },
                        durationLabel(result.durationSeconds).takeIf { it.isNotBlank() },
                        "${result.items.size} 个地址"
                    ).joinToString(" · ")
                    AppText(text = meta, style = AppText.caption, color = colors.textSecondary, maxLines = 2)
                }
            }

            if (recommended == null) {
                AppText(
                    text = "没有拿到可播放地址，看看下面的提示。",
                    style = AppText.body,
                    color = colors.textSecondary
                )
            } else {
                AppText(
                    text = if (others.isEmpty()) "推荐" else "推荐（已自动选最优）",
                    style = AppText.caption,
                    color = colors.accent
                )
                MediaRow(
                    item = recommended,
                    backdrop = backdrop,
                    highlighted = true,
                    onPlay = {
                        if (recommended.kind == MediaKind.IMAGE) {
                            onOpenImages(images, images.indexOf(recommended).coerceAtLeast(0), result.title)
                        } else {
                            onPlay(recommended, result.title, result.poster)
                        }
                    },
                    onPlayAudio = { onPlayAudio(recommended, result.title, result.poster) },
                    onDownload = { onDownload(recommended) },
                    onCopy = {
                        clipboard.setText(AnnotatedString(recommended.url))
                        Toast.makeText(context, "已复制地址", Toast.LENGTH_SHORT).show()
                    },
                    onShare = { shareUrl(context, recommended.url, result.title) }
                )
            }

            val bestAudio = result.items.firstOrNull { it.kind == MediaKind.AUDIO }
            if (bestAudio != null && playable.any { it.kind != MediaKind.AUDIO }) {
                PlainCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppText(
                            text = "只想听声音？可以直接存音频",
                            style = AppText.caption,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(iconRes = R.drawable.ic_audio, label = "下音频", onClick = {
                            onDownload(bestAudio)
                        })
                    }
                }
            }

            if (images.isNotEmpty() && playable.isNotEmpty()) {
                PlainCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppText(
                            text = "共 ${images.size} 张图片",
                            style = AppText.caption,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(iconRes = R.drawable.ic_palette, label = "查看", onClick = {
                            onOpenImages(images, 0, result.title)
                        })
                        ActionChip(iconRes = R.drawable.ic_download, label = "全下", onClick = {
                            onDownloadAll(images)
                        })
                    }
                }
            }

            if (others.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAll = !showAll }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppText(
                        text = if (showAll) "收起其他格式" else "查看其他 ${others.size} 个格式与清晰度",
                        style = AppText.label,
                        color = colors.accent,
                        modifier = Modifier.weight(1f)
                    )
                    Image(
                        painter = painterResource(
                            if (showAll) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        colorFilter = ColorFilter.tint(colors.accent)
                    )
                }

                if (showAll) {
                    others.forEach { item ->
                        PlainCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                MediaRow(
                                    item = item,
                                    backdrop = null,
                                    highlighted = false,
                                    onPlay = {
                                        if (item.kind == MediaKind.IMAGE) {
                                            onOpenImages(images, images.indexOf(item).coerceAtLeast(0), result.title)
                                        } else {
                                            onPlay(item, result.title, result.poster)
                                        }
                                    },
                                    onPlayAudio = { onPlayAudio(item, result.title, result.poster) },
                                    onDownload = { onDownload(item) },
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(item.url))
                                        Toast.makeText(context, "已复制地址", Toast.LENGTH_SHORT).show()
                                    },
                                    onShare = { shareUrl(context, item.url, result.title) }
                                )
                            }
                        }
                    }
                }
            }

            if (result.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.notes.forEach { note ->
                        AppText(text = "· $note", style = AppText.caption, color = colors.warning)
                    }
                }
            }
        }
    }

    if (showUnfavoriteConfirm) {
        ConfirmDialog(
            title = "取消收藏？",
            message = "会把「${result.title?.take(24) ?: result.inputUrl}」从收藏里移除。",
            confirmText = "取消收藏",
            onConfirm = {
                favorites.remove(result.inputUrl)
                showUnfavoriteConfirm = false
                Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showUnfavoriteConfirm = false }
        )
    }
}

@Composable
private fun MediaRow(
    item: VideoItem,
    backdrop: Backdrop?,
    highlighted: Boolean,
    onPlay: () -> Unit,
    onPlayAudio: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.kind == MediaKind.IMAGE) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        RemoteImage(
                            url = item.url,
                            headers = item.headers,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (item.quality.isNotBlank()) QualityBadge(item.quality)
                        AppText(
                            text = item.label,
                            style = if (highlighted) AppText.label else AppText.caption,
                            color = colors.text,
                            maxLines = 1
                        )
                    }
                    if (item.size.isNotBlank()) {
                        AppText(
                            text = item.size,
                            style = AppText.caption,
                            color = colors.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
            AppText(
                text = item.url,
                style = AppText.mono,
                color = colors.textSecondary.copy(alpha = 0.75f),
                maxLines = 2
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (backdrop != null && highlighted) {
                LiquidButton(
                    onClick = onPlay,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    tint = colors.accentSecondary
                ) {
                    Image(
                        painter = painterResource(
                            if (item.kind == MediaKind.IMAGE) R.drawable.ic_palette else R.drawable.ic_play
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                    AppText(
                        text = if (item.kind == MediaKind.IMAGE) "查看图片" else "播放",
                        style = AppText.label,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            } else {
                AccentAction(
                    iconRes = if (item.kind == MediaKind.IMAGE) R.drawable.ic_palette else R.drawable.ic_play,
                    label = if (item.kind == MediaKind.IMAGE) "查看" else "播放",
                    onClick = onPlay,
                    modifier = Modifier.weight(1f)
                )
            }

            AppIconButton(iconRes = R.drawable.ic_download, onClick = onDownload, description = "下载")
            AppIconButton(iconRes = R.drawable.ic_share, onClick = onShare, description = "分享")
            AppIconButton(iconRes = R.drawable.ic_copy, onClick = onCopy, description = "复制地址")
        }

        // 「播放」下面再给一个只放声音的入口
        if (item.kind != MediaKind.IMAGE && (item.muxed || item.kind == MediaKind.AUDIO)) {
            Row(Modifier.fillMaxWidth()) {
                AccentAction(
                    iconRes = R.drawable.ic_audio,
                    label = "播放音频",
                    onClick = onPlayAudio,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QualityBadge(text: String) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .clip(Capsule())
            .background(colors.accent.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        AppText(text = text, style = AppText.caption, color = colors.accent, maxLines = 1)
    }
}

@Composable
private fun RowScope.AccentAction(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .clip(Capsule())
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(colors.accentSecondary)
        )
        Spacer(Modifier.size(6.dp))
        AppText(text = label, style = AppText.label, color = colors.accentSecondary, maxLines = 1)
    }
}

private fun shareUrl(context: Context, url: String, title: String?) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享链接")) }
}
