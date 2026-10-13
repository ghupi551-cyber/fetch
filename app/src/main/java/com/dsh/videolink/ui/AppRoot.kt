package com.dsh.videolink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dsh.videolink.R
import com.dsh.videolink.data.AppSettings
import com.dsh.videolink.data.FavoritesStore
import com.dsh.videolink.data.HistoryStore
import com.dsh.videolink.download.DownloadStore
import com.dsh.videolink.extract.MediaKind
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.extract.RoutePlan
import com.dsh.videolink.extract.YtDlpEngine
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.ui.glass.LiquidBottomTab
import com.dsh.videolink.ui.glass.LiquidBottomTabs
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class NowPlaying(
    val item: VideoItem,
    val title: String?,
    val poster: String? = null
)

private data class ImageViewerRequest(
    val items: List<VideoItem>,
    val index: Int,
    val title: String?
)

private const val TAB_HOME = 0
private const val TAB_DOWNLOADS = 1
private const val TAB_HISTORY = 2
private const val TAB_SETTINGS = 3

@Composable
fun AppRoot(
    settings: AppSettings,
    history: HistoryStore,
    favorites: FavoritesStore,
    sharedLink: String?,
    onSharedLinkConsumed: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()

    // 引擎预热放在这里：不管切到哪个标签页都会继续，不会因为主页被销毁而重来
    LaunchedEffect(Unit) {
        // 等界面先渲染出来再后台准备引擎，避免抢启动资源
        delay(1200)
        withContext(Dispatchers.IO) {
            YtDlpEngine.ensureReady(context)
        }
        // 后台实时测线路：启动测一次，之后每 8 分钟复测，结果用于「最优路线排第一」
        RoutePlan.loadLastProbe(context)
        // 等用户第一次操作之后再测，避免和第一次解析抢资源
        delay(20_000L)
        while (true) {
            runCatching { RoutePlan.probe(context) }
            delay(8 * 60 * 1000L)
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var input by rememberSaveable { mutableStateOf("") }
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var audioPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var viewer by remember { mutableStateOf<ImageViewerRequest?>(null) }
    var autoExtractUrl by remember { mutableStateOf<String?>(null) }
    var showDonate by remember { mutableStateOf(false) }

    LaunchedEffect(sharedLink) {
        val link = sharedLink
        if (!link.isNullOrBlank()) {
            input = link.trim()
            tab = TAB_HOME
            onSharedLinkConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop = backdrop)

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    TAB_HOME -> HomeScreen(
                        backdrop = backdrop,
                        settings = settings,
                        history = history,
                        favorites = favorites,
                        input = input,
                        onInputChange = { input = it },
                        autoExtractUrl = autoExtractUrl,
                        onAutoExtractConsumed = { autoExtractUrl = null },
                        onPlay = { item, title, poster ->
                            if (item.kind == MediaKind.AUDIO) {
                                audioPlaying = NowPlaying(item, title, poster)
                            } else {
                                nowPlaying = NowPlaying(item, title, poster)
                            }
                        },
                        onPlayAudio = { item, title, poster ->
                            // 视频也能只放声音：直接把这个地址交给音频播放器
                            audioPlaying = NowPlaying(item, title, poster)
                        },
                        onOpenImages = { items, index, title ->
                            viewer = ImageViewerRequest(items, index, title)
                        },
                        onOpenDownloads = { tab = TAB_DOWNLOADS }
                    )

                    TAB_DOWNLOADS -> DownloadScreen(backdrop = backdrop)

                    TAB_HISTORY -> HistoryScreen(
                        backdrop = backdrop,
                        history = history,
                        favorites = favorites,
                        onReuse = { url ->
                            input = url
                            autoExtractUrl = url
                            tab = TAB_HOME
                        }
                    )

                    else -> SettingsScreen(
                        backdrop = backdrop,
                        settings = settings,
                        history = history,
                        onOpenDonate = { showDonate = true }
                    )
                }
            }

            LiquidBottomTabs(
                selectedTabIndex = { tab },
                onTabSelected = { tab = it },
                backdrop = backdrop,
                tabsCount = 4,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
            ) {
                BottomTab(
                    iconRes = R.drawable.ic_home,
                    label = "主页",
                    selected = tab == TAB_HOME,
                    onClick = { tab = TAB_HOME }
                )
                BottomTab(
                    iconRes = R.drawable.ic_download,
                    label = if (DownloadStore.activeCount > 0) "下载 ${DownloadStore.activeCount}" else "下载",
                    selected = tab == TAB_DOWNLOADS,
                    onClick = { tab = TAB_DOWNLOADS }
                )
                BottomTab(
                    iconRes = R.drawable.ic_history,
                    label = "历史",
                    selected = tab == TAB_HISTORY,
                    onClick = { tab = TAB_HISTORY }
                )
                BottomTab(
                    iconRes = R.drawable.ic_settings,
                    label = "设置",
                    selected = tab == TAB_SETTINGS,
                    onClick = { tab = TAB_SETTINGS }
                )
            }
        }

        nowPlaying?.let { playing ->
            BackHandler { nowPlaying = null }
            PlayerScreen(
                item = playing.item,
                title = playing.title,
                keepScreenOn = settings.keepScreenOn,
                onClose = { nowPlaying = null }
            )
        }

        // 隐藏的内置浏览器（只在解析 TikTok 时挂载）
        HiddenWebExtractor()

        audioPlaying?.let { playing ->
            BackHandler { audioPlaying = null }
            AudioPlayerScreen(
                item = playing.item,
                title = playing.title,
                poster = playing.poster,
                keepScreenOn = settings.keepScreenOn,
                backdrop = backdrop,
                onClose = { audioPlaying = null },
                onDownload = { DownloadStore.enqueue(context, playing.item, playing.title) }
            )
        }

        if (showDonate) {
            BackHandler { showDonate = false }
            DonateScreen(onClose = { showDonate = false })
        }

        viewer?.let { request ->
            BackHandler { viewer = null }
            ImageViewerScreen(
                items = request.items,
                initialIndex = request.index,
                title = request.title,
                backdrop = backdrop,
                onClose = { viewer = null }
            )
        }
    }
}

@Composable
private fun RowScope.BottomTab(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    LiquidBottomTab(onClick = onClick) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(if (selected) colors.text else colors.textSecondary)
        )
        AppText(
            text = label,
            style = AppText.caption,
            color = if (selected) colors.text else colors.textSecondary,
            maxLines = 1
        )
    }
}
