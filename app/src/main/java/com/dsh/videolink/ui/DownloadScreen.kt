package com.dsh.videolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dsh.videolink.R
import com.dsh.videolink.download.DownloadStatus
import com.dsh.videolink.download.DownloadStore
import com.dsh.videolink.download.DownloadTask
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.ui.glass.GlassCard
import com.dsh.videolink.ui.glass.AppIconButton
import com.dsh.videolink.ui.glass.GlassPill
import com.dsh.videolink.ui.glass.PlainCard
import com.kyant.backdrop.Backdrop

@Composable
fun DownloadScreen(backdrop: Backdrop) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val tasks = DownloadStore.tasks

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText(text = "下载", style = AppText.hero, color = colors.text)
            AppText(
                text = "下载在应用内完成，会带上必要的请求头，保存到手机下载目录",
                style = AppText.body,
                color = colors.textSecondary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(text = "进行中 ${tasks.count { it.isActive }}", backdrop = backdrop)
            GlassPill(text = "已完成 ${tasks.count { it.status == DownloadStatus.DONE }}", backdrop = backdrop)
            if (tasks.any { !it.isActive }) {
                GlassPill(text = "清理已结束", backdrop = backdrop)
            }
        }

        if (tasks.isEmpty()) {
            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(text = "还没有下载任务", style = AppText.title, color = colors.text)
                    AppText(
                        text = "在主页提取出地址后，点结果里的下载按钮，进度会显示在这里。",
                        style = AppText.body,
                        color = colors.textSecondary
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                DownloadRow(
                    task = task,
                    onCancel = { DownloadStore.cancel(task.id) },
                    onRetry = { DownloadStore.retry(context, task.id) },
                    onRemove = { DownloadStore.remove(task.id) },
                    onOpen = { DownloadStore.openTask(context, task.id) }
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit
) {
    val colors = LocalAppColors.current

    PlainCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(
                    text = task.title,
                    style = AppText.label,
                    color = colors.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                GlassPill(text = statusLabel(task), tint = statusColor(task))
            }

            if (task.isActive) {
                ProgressBar(
                    progress = task.progress,
                    color = colors.accent,
                    track = colors.textSecondary.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = detailLabel(task),
                    style = AppText.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                when {
                    task.isActive -> AppIconButton(
                        iconRes = R.drawable.ic_close,
                        onClick = onCancel,
                        description = "取消",
                        buttonSize = 36.dp,
                        iconSize = 16.dp
                    )

                    task.status == DownloadStatus.DONE -> {
                        AppIconButton(
                            iconRes = R.drawable.ic_open,
                            onClick = onOpen,
                            description = "打开",
                            buttonSize = 36.dp,
                            iconSize = 16.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        AppIconButton(
                            iconRes = R.drawable.ic_delete,
                            onClick = onRemove,
                            description = "移除记录",
                            buttonSize = 36.dp,
                            iconSize = 16.dp
                        )
                    }

                    else -> {
                        AppIconButton(
                            iconRes = R.drawable.ic_refresh,
                            onClick = onRetry,
                            description = "重试",
                            buttonSize = 36.dp,
                            iconSize = 16.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        AppIconButton(
                            iconRes = R.drawable.ic_delete,
                            onClick = onRemove,
                            description = "移除记录",
                            buttonSize = 36.dp,
                            iconSize = 16.dp
                        )
                    }
                }
            }

            AppText(
                text = task.fileName,
                style = AppText.mono,
                color = colors.textSecondary.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

@Composable
fun ProgressBar(
    progress: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.height(6.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        val width = (size.width * progress.coerceIn(0f, 1f))
        if (width > 0f) {
            drawRoundRect(
                color = color,
                size = Size(width, size.height),
                cornerRadius = radius
            )
        }
    }
}

private fun statusLabel(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> if (task.total > 0) "${(task.progress * 100).toInt()}%" else "下载中"
    DownloadStatus.DONE -> "已完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELED -> "已取消"
}

@Composable
private fun statusColor(task: DownloadTask): Color = when (task.status) {
    DownloadStatus.DONE -> LocalAppColors.current.success
    DownloadStatus.FAILED -> LocalAppColors.current.warning
    else -> LocalAppColors.current.textSecondary
}

private fun detailLabel(task: DownloadTask): String {
    val downloaded = formatBytes(task.bytes)
    return when (task.status) {
        DownloadStatus.RUNNING -> buildString {
            if (task.total > 0) append("$downloaded / ${formatBytes(task.total)}")
            else append("已下载 $downloaded")
            if (task.speed > 0) append(" · ${formatBytes(task.speed)}/s")
        }

        DownloadStatus.DONE -> "已保存 · $downloaded"
        DownloadStatus.FAILED -> task.error ?: "下载失败"
        DownloadStatus.CANCELED -> "已取消 · 已下载 $downloaded"
        DownloadStatus.QUEUED -> "等待开始"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%d KB", bytes / 1024)
    else -> "$bytes B"
}
