package com.dsh.videolink.extract

import org.json.JSONArray
import org.json.JSONObject

/**
 * 抖音专用解析。
 *
 * 现状（2026）：
 * - 分享页 `https://www.iesdouyin.com/share/video/<id>/` 的 `window._ROUTER_DATA` 里
 *   才会带 `videoInfoRes.item_list`，而且**必须先访问一次 www.douyin.com 拿到 Cookie**，
 *   否则返回的是空壳页面（数据受限时 `item_list` 为空，或给出 `filter_list` 原因）。
 * - 拿到的 `play_addr.url_list[0]` 里把 `playwm` 换成 `play` 就是无水印地址，再跟随 302 即可。
 * - 如果这条链路被风控拦住，最后兜底是「设置里粘贴自己的抖音 Cookie」。
 *
 * 参考实现：[parse-video](https://github.com/wujunwei928/parse-video)（MIT）、
 * [douyin-tool](https://github.com/CarGuo/douyin-tool)（MIT）。
 */
object DouyinExtractor {

    private val AWEME_ID_PATTERNS = listOf(
        Regex("""/(?:video|note|slides)/(\d{15,})"""),
        Regex("""[?&]modal_id=(\d{15,})"""),
        Regex("""[?&]vid=(\d{15,})""")
    )

    private val ROUTER_DATA = Regex(
        """window\._ROUTER_DATA\s*=\s*(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun isDouyin(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        return host.contains("douyin.com") || host.contains("amemv.com")
    }

    fun awemeIdOf(url: String): String? = AWEME_ID_PATTERNS.asSequence()
        .mapNotNull { it.find(url)?.groupValues?.get(1) }
        .firstOrNull()

    /**
     * 通用引擎的抖音解析只认 www.douyin.com/video/<id>，
     * 这里把短链/分享链归一化过去（短链需要先跟随跳转才能拿到作品号）。
     */
    fun normalizeForYtDlp(url: String): String {
        val resolved = if (url.contains("v.douyin.com")) {
            runCatching { Http.resolve(url, ua = Http.IPHONE_UA) }.getOrDefault(url)
        } else {
            url
        }
        val id = awemeIdOf(resolved) ?: awemeIdOf(url) ?: return url
        return "https://www.douyin.com/video/$id"
    }

    /** 解析失败返回 null，调用方继续走其它引擎；尝试结果会写进 notes。 */
    fun extract(input: String, notes: MutableList<String>): ExtractResult? {
        val resolved = if (input.contains("v.douyin.com")) {
            runCatching { Http.resolve(input, ua = Http.IPHONE_UA) }.getOrDefault(input)
        } else {
            input
        }

        val awemeId = awemeIdOf(resolved) ?: awemeIdOf(input)
        if (awemeId == null) {
            notes += "抖音链接里没有识别到作品 ID，改用通用引擎重试。"
            return null
        }

        // 关键：先访问一次抖音主页，分享页才会返回完整数据
        Http.warmUpDouyin()

        val shareUrl = if (resolved.contains("/share/")) {
            resolved.substringBefore('#')
        } else {
            "https://www.iesdouyin.com/share/video/$awemeId/"
        }

        val html = try {
            Http.get(shareUrl, ua = Http.IPHONE_UA, maxBytes = 2_000_000).text
        } catch (t: Throwable) {
            notes += "抖音分享页请求失败：${t.message ?: t.javaClass.simpleName}"
            return null
        }
        if (html.isBlank()) {
            notes += "抖音分享页返回空内容，改用通用引擎重试。"
            return null
        }

        val routerJson = ROUTER_DATA.find(html)?.groupValues?.get(1)?.trim()?.trimEnd(';', '\n', '\r', ' ')
        val loaderData = routerJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("loaderData")

        if (loaderData == null) {
            notes += "抖音这次没有返回作品数据，通常是被风控拦了一下，稍等几秒再点一次解析就行。"
            return null
        }

        var videoInfoRes: JSONObject? = null
        val keys = loaderData.keys()
        while (keys.hasNext()) {
            val page = loaderData.optJSONObject(keys.next()) ?: continue
            if (page.has("videoInfoRes")) {
                videoInfoRes = page.optJSONObject("videoInfoRes")
                break
            }
        }

        if (videoInfoRes == null) {
            notes += "抖音这次没有返回作品数据，稍等一下再试一次即可。"
            return null
        }

        val items = videoInfoRes.optJSONArray("item_list")
        val first = if (items != null && items.length() > 0) items.optJSONObject(0) else null
        if (first == null) {
            val filter = videoInfoRes.optJSONArray("filter_list")?.optJSONObject(0)
            val reason = filter?.optString("filter_reason").orEmpty()
            val detail = filter?.optString("detail_msg").orEmpty()
            notes += buildString {
                append("抖音没有返回这个作品")
                if (reason.isNotBlank()) append("（$reason")
                if (detail.isNotBlank()) append("：$detail")
                if (reason.isNotBlank()) append("）")
                append("。可能是作品已删除/私密，或刚好被风控，稍后再试一次。")
            }
            return null
        }

        val title = first.optString("desc").ifBlank { null }
        val author = first.optJSONObject("author")?.optString("nickname")?.takeIf { it.isNotBlank() }
        val video = first.optJSONObject("video")
        val durationMs = video?.optLong("duration", 0L) ?: 0L

        val mediaHeaders = mapOf(
            "Referer" to "https://www.douyin.com/",
            "User-Agent" to Http.DESKTOP_UA
        )
        val gear = video?.optJSONArray("bit_rate")?.optJSONObject(0)
            ?.optString("gear_name")?.takeIf { it.isNotBlank() }

        val result = mutableListOf<VideoItem>()

        // 图集
        val images = first.optJSONArray("images")
        if (images != null && images.length() > 0) {
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = pickNonWebp(image.optJSONArray("url_list")) ?: continue
                result += VideoItem(
                    url = url,
                    label = "图片 ${i + 1}/${images.length()}",
                    kind = MediaKind.IMAGE,
                    headers = mediaHeaders
                )
            }
        }

        // 视频 / 图集背景音乐
        val playUrls = video?.optJSONObject("play_addr")?.optJSONArray("url_list")
        val playMediaUrl = pickFirst(playUrls)?.replace("playwm", "play")
        val isImagePost = images != null && images.length() > 0

        if (isImagePost) {
            // 图集作品的 video.play_addr 其实是背景音乐，不是视频。
            // 之前把它当成视频列出来，界面上就多出一个「播放」按钮。
            val musicUrl = pickFirst(playUrls)
            if (musicUrl != null) {
                result += VideoItem(
                    url = musicUrl,
                    label = "背景音乐",
                    kind = MediaKind.AUDIO,
                    headers = mediaHeaders
                )
            }
            notes += "这是一条图集作品，共 ${images?.length() ?: 0} 张图片。"
        } else if (playMediaUrl != null) {
            val size = video.optJSONObject("play_addr")?.optLong("data_size", 0L) ?: 0L
            val width = video.optInt("width", 0)
            val height = video.optInt("height", 0)
            // 帧率可能直接在 video.fps，也可能在 bit_rate[0].FPS
            var fps = video.optInt("fps", 0)
            if (fps <= 0) {
                val bitRate = video.optJSONArray("bit_rate")?.optJSONObject(0)
                fps = bitRate?.optInt("FPS", 0) ?: 0
            }
            result += VideoItem(
                url = playMediaUrl,
                label = buildString {
                    append("无水印 MP4")
                    if (!gear.isNullOrBlank()) append(" · $gear")
                    if (size > 0) append(" · ").append(formatSize(size))
                },
                kind = MediaKind.MP4,
                headers = mediaHeaders,
                sizeBytes = size,
                width = width,
                height = height,
                fps = fps
            )
        }

        if (result.isEmpty()) {
            notes += "抖音这条作品没有可用的视频或图片地址。"
            return null
        }

        val cover = pickNonWebp(video?.optJSONObject("cover")?.optJSONArray("url_list"))

        return ExtractResult(
            inputUrl = input,
            finalUrl = shareUrl,
            title = title,
            poster = cover,
            items = result,
            uploader = author,
            durationSeconds = (durationMs / 1000).toInt(),
            engine = "抖音分享页",
            notes = notes
        )
    }

    private fun pickFirst(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.startsWith("http")) return value
        }
        return null
    }

    /** 优先取非 webp 的地址（webp 在部分播放器/相册里打不开） */
    private fun pickNonWebp(array: JSONArray?): String? {
        if (array == null) return null
        var fallback: String? = null
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (!value.startsWith("http")) continue
            if (fallback == null) fallback = value
            if (!value.contains(".webp")) return value
        }
        return fallback
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format("%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
