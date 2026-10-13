package com.dsh.videolink.ui

import android.app.Activity
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dsh.videolink.R
import com.dsh.videolink.extract.Http
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.download.DownloadStore
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    item: VideoItem,
    title: String?,
    keepScreenOn: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var speedIndex by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = context as? Activity
    val speeds = remember { listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.5f) }

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val exoPlayer = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(item.headers)
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        // 用 DefaultDataSource 分发：本机合成好的 file:// 文件也能直接播
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    PlayerSpeedEffect(exoPlayer, speeds[speedIndex])

    // 自己读播放进度，用来画底部进度条
    LaunchedEffect(exoPlayer) {
        while (true) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.let { if (it > 0) it else 0L }
            isPlaying = exoPlayer.isPlaying
            delay(500L)
        }
    }

    // 横屏进来时默认收起来（点一下画面就能调出来，和系统控件联动）
    LaunchedEffect(isLandscape) {
        controlsVisible = !isLandscape
        if (isLandscape) playerViewRef?.hideController() else playerViewRef?.showController()
    }

    // 点顶部区域：自己和系统控件**同时**切换，避免「下面先消失、上面才消失」的延迟
    fun toggleControls() {
        val next = !controlsVisible
        controlsVisible = next
        if (next) playerViewRef?.showController() else playerViewRef?.hideController()
    }

    // 横屏时按返回：先回到竖屏（不退出播放）
    BackHandler(enabled = isLandscape) {
        fullscreen = false
        controlsVisible = true
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    LaunchedEffect(item.url) {
        exoPlayer.setMediaItem(MediaItem.Builder().setUri(item.url).build())
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (fullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (keepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    // 用回系统自带控件（进度条/暂停键就是原来的样子）
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    // 系统控件显示/隐藏时，同步我们顶部那一排 ——
                    // 这样「点一下全部收起、再点一下全部出来」是一次动作
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        }
                    )
                    playerViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部区域点一下：全部收起 / 全部出现（和系统控件同一时刻切换）
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.TopCenter)
                .pointerInput(Unit) {
                    detectTapGestures { toggleControls() }
                }
        )

        if (controlsVisible) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAction(R.drawable.ic_back, "关闭") { onClose() }
            Column(Modifier.weight(1f)) {
                AppText(
                    text = title?.takeIf { it.isNotBlank() } ?: "正在播放",
                    style = AppText.label,
                    color = Color.White,
                    maxLines = 1
                )
                AppText(
                    text = item.label,
                    style = AppText.caption,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            PlayerAction(
                iconRes = R.drawable.ic_bolt,
                description = "倍速",
                label = "${speeds[speedIndex]}x"
            ) {
                speedIndex = (speedIndex + 1) % speeds.size
                Toast.makeText(context, "播放速度 ${speeds[speedIndex]}x", Toast.LENGTH_SHORT).show()
            }
            PlayerAction(R.drawable.ic_fullscreen, "全屏") {
                fullscreen = !fullscreen
                activity?.requestedOrientation = if (fullscreen) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            PlayerAction(R.drawable.ic_copy, "复制链接") {
                clipboard.setText(AnnotatedString(item.url))
                Toast.makeText(context, "已复制播放地址", Toast.LENGTH_SHORT).show()
            }
            PlayerAction(R.drawable.ic_download, "下载") {
                DownloadStore.enqueue(context, item, title)
                Toast.makeText(context, "已开始下载，去「下载」看进度", Toast.LENGTH_SHORT).show()
            }
        }
        }

    }
}


@Composable
private fun PlayerAction(
    iconRes: Int,
    description: String,
    label: String? = null,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(if (label == null) 40.dp else 44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label == null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(Color.White)
            )
        } else {
            AppText(text = label, style = AppText.caption, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun PlayerSpeedEffect(player: ExoPlayer, speed: Float) {
    LaunchedEffect(player, speed) {
        player.setPlaybackSpeed(speed)
    }
}
