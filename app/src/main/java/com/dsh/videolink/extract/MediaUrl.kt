package com.dsh.videolink.extract

/**
 * 判断一个地址到底是不是「能播的视频流」。
 *
 * 之前吃过亏：网页里的 `og:video` / `twitter:player` 经常是**嵌入页地址**
 * （例如站点自己的"嵌入页"地址），把它当成视频交给播放器，
 * 结果就是黑屏 + 00:00，还让人误以为解析成功了。
 * 这里做严格判定：只有明确的媒体后缀、媒体特征参数、或已知媒体 CDN 才算数。
 */
object MediaUrl {

    private val MEDIA_SUFFIXES = listOf(
        ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".flv", ".ts", ".m4s",
        ".m3u8", ".mpd", ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg"
    )

    /** 已知的视频 CDN（注意：douyinpic / byteimg 是图片站，绝不能算视频） */
    private val MEDIA_HOST_HINTS = listOf(
        "googlevideo.com", "bilivideo", "akamaized", "tiktokcdn", "byteoversea",
        "douyinvod", "ixigua", "kwaicdn", "ks-cdn", "vod", "video.twimg",
        "cdninstagram", "fbcdn", "xhscdn"
    )

    private val IMAGE_SUFFIXES = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".bmp", ".svg", ".avif"
    )

    private val PAGE_HOSTS = listOf(
        "tiktok.com", "douyin.com",
        "bilibili.com", "instagram.com", "twitter.com", "x.com", "facebook.com",
        "weibo.com", "kuaishou.com", "xiaohongshu.com"
    )

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()

    /** 是否是可以直接交给播放器/下载器的媒体地址 */
    fun isPlayable(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val lower = url.lowercase()
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        val query = uri.query.orEmpty().lowercase()

        // 图片先排除
        if (IMAGE_SUFFIXES.any { path.endsWith(it) }) return false
        if (query.contains("mime=image") || query.contains("mime_type=image")) return false

        if (MEDIA_SUFFIXES.any { path.endsWith(it) }) return true
        if (query.contains("mime=video") || query.contains("mime_type=video")) return true
        if (query.contains("videoplayback")) return true
        if (MEDIA_HOST_HINTS.any { host.contains(it) }) return true
        return false
    }

    /** 是不是「网页地址」被误当成了视频 */
    fun isPageLike(url: String): Boolean {
        val host = hostOf(url)
        return PAGE_HOSTS.any { host == it || host.endsWith(".$it") } && !isPlayable(url)
    }
}
