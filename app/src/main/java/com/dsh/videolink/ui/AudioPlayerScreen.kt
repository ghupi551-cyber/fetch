package com.dsh.videolink.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dsh.videolink.R
import com.dsh.videolink.extract.Http
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.ui.glass.ActionChip
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.ui.glass.LiquidSlider
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 音频预览：像音乐播放器那样，转盘 + 进度条 + 播放控制。
 */
@OptIn(UnstableApi::class)
@Composable
fun AudioPlayerScreen(
    item: VideoItem,
    title: String?,
    poster: String?,
    keepScreenOn: Boolean,
    backdrop: Backdrop,
    onClose: () -> Unit,
    onDownload: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val activity = context as? Activity

    val player = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(item.headers)
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(0) }
    val speeds = remember { listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.5f) }

    LaunchedEffect(item.url) {
        player.setMediaItem(MediaItem.Builder().setUri(item.url).build())
        player.prepare()
        player.playWhenReady = true
    }

    // 轮询播放进度（500ms 一次，足够跟手也不费电）
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.let { if (it > 0) it else 0L }
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    LaunchedEffect(player, speeds[speedIndex]) {
        player.setPlaybackSpeed(speeds[speedIndex])
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    DisposableEffect(keepScreenOn) {
        val window = activity?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (keepScreenOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 唱片缓慢转动（只在播放时转）
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            angle = (angle + 0.7f) % 360f
            delay(50L)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (colors.dark) Color(0xFF14121F) else Color(0xFFEDF0FA),
                        if (colors.dark) Color.Black else Color(0xFFF7F8FC)
                    )
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircleAction(R.drawable.ic_back, "返回", colors.glassStrongSurface) { onClose() }
                AppText(
                    text = "音频播放",
                    style = AppText.label,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                CircleAction(R.drawable.ic_download, "保存音频", colors.glassStrongSurface, onDownload)
            }

            Spacer(Modifier.size(28.dp))

            // 唱片
            Box(
                Modifier
                    .size(248.dp)
                    .graphicsLayer { rotationZ = angle }
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(colors.accent, colors.accentSecondary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!poster.isNullOrBlank()) {
                    RemoteImage(
                        url = poster,
                        headers = item.headers,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.18f))
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.ic_audio),
                        contentDescription = null,
                        modifier = Modifier.size(84.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                // 中心小孔，像唱片
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (colors.dark) Color(0xFF101018) else Color.White)
                )
            }

            Spacer(Modifier.size(30.dp))

            AppText(
                text = title?.takeIf { it.isNotBlank() } ?: "音频",
                style = AppText.title,
                color = colors.text,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(4.dp))
            AppText(
                text = listOfNotNull(item.quality.takeIf { it.isNotBlank() }, item.label).joinToString(" · "),
                style = AppText.caption,
                color = colors.textSecondary,
                maxLines = 1
            )

            Spacer(Modifier.size(22.dp))

            // 进度
            LiquidSlider(
                value = {
                    if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                },
                onValueChange = { fraction ->
                    if (duration > 0L) {
                        val target = (fraction * duration).toLong()
                        player.seekTo(target)
                        position = target
                    }
                },
                valueRange = 0f..1f,
                visibilityThreshold = 0.002f,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppText(text = formatTime(position), style = AppText.caption, color = colors.textSecondary)
                AppText(text = formatTime(duration), style = AppText.caption, color = colors.textSecondary)
            }

            Spacer(Modifier.size(20.dp))

            // 控制
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CircleAction(R.drawable.ic_back, "后退 10 秒", colors.glassSurface) {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable {
                            if (player.isPlaying) player.pause() else player.play()
                            isPlaying = player.isPlaying
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(34.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                ActionChip(
                    label = "${speeds[speedIndex]}x",
                    onClick = { speedIndex = (speedIndex + 1) % speeds.size },
                    tint = colors.accent
                )
            }

            Spacer(Modifier.size(18.dp))

            ActionChip(
                iconRes = R.drawable.ic_download,
                label = "保存到本地",
                onClick = onDownload,
                tint = colors.text
            )
        }
    }
}

@Composable
private fun CircleAction(
    iconRes: Int,
    description: String,
    background: Color,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(
                if (iconRes == R.drawable.ic_back) colors.text else colors.text
            )
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = (ms / 1000.0).roundToInt()
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format("%d:%02d", m, s)
}
