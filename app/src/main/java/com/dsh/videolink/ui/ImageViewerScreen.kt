package com.dsh.videolink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.dsh.videolink.R
import com.dsh.videolink.download.DownloadStore
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.ui.glass.Spinner
import com.dsh.videolink.ui.glass.AppText
import com.kyant.backdrop.Backdrop

/**
 * 图片查看器：左右滑动切换、双指缩放、双击缩放，右上角可保存。
 * 抖音图集之前只能看地址，这里补上真正的看图能力。
 */
@Composable
fun ImageViewerScreen(
    items: List<VideoItem>,
    initialIndex: Int,
    title: String?,
    backdrop: Backdrop,
    onClose: () -> Unit
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )
    var scale by remember(pagerState.currentPage) { mutableFloatStateOf(1f) }
    var offset by remember(pagerState.currentPage) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            val item = items[page]
            val image = rememberRemoteImage(item.url, item.headers, maxDimension = 4096)

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1.05f) 1f else 2.5f
                                if (scale == 1f) offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(page) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                val pressed = event.changes.count { it.pressed }
                                // 只有双指缩放，或已经放大后的拖动才消费事件，避免抢走翻页手势
                                if (pressed > 1 || scale > 1.05f) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (zoom != 1f || pan != Offset.Zero) {
                                        scale = (scale * zoom).coerceIn(1f, 6f)
                                        offset = if (scale > 1.05f) offset + pan else Offset.Zero
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                event = awaitPointerEvent()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    androidx.compose.foundation.Image(
                        bitmap = image,
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Spinner(color = Color.White)
                        AppText(
                            text = "图片加载中…",
                            style = AppText.caption,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // 底部页码指示
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val total = items.size
            if (total in 2..12) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(total) { index ->
                        Box(
                            Modifier
                                .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) Color.White
                                    else Color.White.copy(alpha = 0.38f)
                                )
                        )
                    }
                }
            }
            AppText(
                text = "第 ${pagerState.currentPage + 1} / $total 页",
                style = AppText.caption,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewerAction(R.drawable.ic_back, "关闭") { onClose() }
            Column(Modifier.weight(1f)) {
                AppText(
                    text = title?.takeIf { it.isNotBlank() } ?: "图片",
                    style = AppText.label,
                    color = Color.White,
                    maxLines = 1
                )
                AppText(
                    text = "${pagerState.currentPage + 1} / ${items.size} · 双指或双击可缩放",
                    style = AppText.caption,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            ViewerAction(R.drawable.ic_download, "保存这张") {
                DownloadStore.enqueue(context, items[pagerState.currentPage], title)
                Toast.makeText(context, "已开始保存，去「下载」看进度", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun ViewerAction(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(iconRes),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(Color.White)
        )
    }
}
