package com.dsh.videolink.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dsh.videolink.R
import com.dsh.videolink.data.FavoritesStore
import com.dsh.videolink.data.HistoryStore
import com.dsh.videolink.ui.glass.ActionChip
import com.dsh.videolink.ui.glass.AppIconButton
import com.dsh.videolink.ui.glass.AppText
import com.dsh.videolink.ui.glass.GlassCard
import com.dsh.videolink.ui.glass.GlassPill
import com.dsh.videolink.ui.glass.PlainCard
import com.kyant.backdrop.Backdrop

@Composable
fun HistoryScreen(
    backdrop: Backdrop,
    history: HistoryStore,
    favorites: FavoritesStore,
    onReuse: (String) -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val entries = history.items
    val saved = favorites.items
    var showClearConfirm by remember { mutableStateOf(false) }
    var showClearFavoritesConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText(text = "历史", style = AppText.hero, color = colors.text)
            AppText(
                text = "点任意一条会回主页重新解析",
                style = AppText.body,
                color = colors.textSecondary
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassPill(text = "收藏 ${saved.size}", backdrop = backdrop)
            GlassPill(text = "历史 ${entries.size} / 30", backdrop = backdrop)
        }

        if (saved.isEmpty() && entries.isEmpty()) {
            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(text = "还是空的", style = AppText.title, color = colors.text)
                    AppText(
                        text = "提取成功后链接会记在这里；解析结果里点「收藏」可以单独存一份。",
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
            if (saved.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppText(
                            text = "收藏",
                            style = AppText.label,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            iconRes = R.drawable.ic_delete,
                            label = "清空收藏",
                            onClick = { showClearFavoritesConfirm = true },
                            tint = colors.warning
                        )
                    }
                }
                items(saved, key = { "fav_${it.url}" }) { entry ->
                    PlainCard(Modifier.fillMaxWidth().clickable { onReuse(entry.url) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                AppText(
                                    text = entry.title.ifBlank { entry.url },
                                    style = AppText.label,
                                    color = colors.text,
                                    maxLines = 2
                                )
                                AppText(
                                    text = entry.url,
                                    style = AppText.caption,
                                    color = colors.textSecondary,
                                    maxLines = 1
                                )
                            }
                            AppIconButton(
                                iconRes = R.drawable.ic_star,
                                onClick = { favorites.remove(entry.url) },
                                description = "取消收藏",
                                buttonSize = 38.dp,
                                iconSize = 17.dp,
                                tint = colors.warning
                            )
                            AppIconButton(
                                iconRes = R.drawable.ic_open,
                                onClick = { openUrl(context, entry.url) },
                                description = "浏览器打开",
                                buttonSize = 38.dp,
                                iconSize = 17.dp
                            )
                        }
                    }
                }
            }

            if (entries.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppText(
                            text = "最近解析",
                            style = AppText.label,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            iconRes = R.drawable.ic_delete,
                            label = "一键清空",
                            onClick = { showClearConfirm = true },
                            tint = colors.warning
                        )
                    }
                }
                items(entries, key = { "his_${it.url}" }) { entry ->
                    val starred = favorites.contains(entry.url)
                    PlainCard(Modifier.fillMaxWidth().clickable { onReuse(entry.url) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                AppText(
                                    text = entry.title.ifBlank { entry.url },
                                    style = AppText.label,
                                    color = colors.text,
                                    maxLines = 2
                                )
                                AppText(
                                    text = entry.url,
                                    style = AppText.caption,
                                    color = colors.textSecondary,
                                    maxLines = 1
                                )
                            }
                            AppIconButton(
                                iconRes = if (starred) R.drawable.ic_star else R.drawable.ic_star_border,
                                onClick = { favorites.toggle(entry.url, entry.title) },
                                description = "收藏",
                                buttonSize = 38.dp,
                                iconSize = 17.dp,
                                tint = if (starred) colors.warning else colors.text
                            )
                            AppIconButton(
                                iconRes = R.drawable.ic_copy,
                                onClick = {
                                    clipboard.setText(AnnotatedString(entry.url))
                                    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                                },
                                description = "复制链接",
                                buttonSize = 38.dp,
                                iconSize = 17.dp
                            )
                            AppIconButton(
                                iconRes = R.drawable.ic_delete,
                                onClick = { history.remove(entry.url) },
                                description = "删除",
                                buttonSize = 38.dp,
                                iconSize = 17.dp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearFavoritesConfirm) {
        ConfirmDialog(
            title = "清空收藏？",
            message = "会移除全部 ${saved.size} 条收藏，无法恢复。",
            confirmText = "清空",
            onConfirm = {
                favorites.clear()
                showClearFavoritesConfirm = false
                Toast.makeText(context, "已清空收藏", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showClearFavoritesConfirm = false }
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空历史记录？",
            message = "会删除全部 ${entries.size} 条记录，无法恢复。收藏不会被删除。",
            confirmText = "清空",
            onConfirm = {
                history.clear()
                showClearConfirm = false
                Toast.makeText(context, "已清空历史", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showClearConfirm = false }
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
