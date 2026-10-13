package com.dsh.videolink.extract

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class MediaKind(val short: String) {
    MP4("MP4"),
    HLS("HLS"),
    DASH("DASH"),
    WEBM("WebM"),
    AUDIO("音频"),
    IMAGE("图片"),
    OTHER("视频")
}

data class VideoItem(
    val url: String,
    val label: String,
    val kind: MediaKind,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String = "video",
    val sizeBytes: Long = 0L,
    /** 是否「视频+音轨」合一，方便界面提示 */
    val muxed: Boolean = true,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0
) {
    /** 画质描述，例如「1920×1080 · 60fps」 */
    val quality: String
        get() = buildString {
            when {
                width > 0 && height > 0 -> append("${width}×${height}")
                height > 0 -> append("${height}p")
            }
            if (fps > 0) {
                if (isNotEmpty()) append(" · ")
                append("${fps}fps")
            }
        }

    /** 体积描述，例如「24.5 MB」 */
    val size: String
        get() = when {
            sizeBytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", sizeBytes / 1024.0 / 1024 / 1024)
            sizeBytes >= 1024L * 1024 -> String.format("%.1f MB", sizeBytes / 1024.0 / 1024)
            sizeBytes >= 1024 -> String.format("%d KB", sizeBytes / 1024)
            else -> ""
        }
}

data class ExtractResult(
    val inputUrl: String,
    val finalUrl: String,
    val title: String?,
    val poster: String?,
    val items: List<VideoItem>,
    val uploader: String? = null,
    val durationSeconds: Int = 0,
    val engine: String = "内置解析器",
    val notes: List<String> = emptyList()
)

/**
 * 视频链接提取引擎：
 * 1. 直链（mp4 / m3u8 / mpd …）直接返回；
 * 2. 普通网页解析 og:video、<video>、<source>、JSON-LD、页面内嵌媒体地址；
 * 3. B 站（含 b23.tv 短链）走官方公开接口，拿到带音轨的 mp4。
 */
object VideoExtractor {

    private val URL_REGEX =
        Regex("""https?://[^\s"'<>()\[\]{}，。、；：""''！？【】（）]+""", RegexOption.IGNORE_CASE)

    /** 没有写 http:// 的裸域名，例如 www.example.com/a/b 或 v.douyin.com/xxx */
    private val BARE_DOMAIN = Regex(
        """(?<![@\w./-])((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?::\d{2,5})?""" +
                """(?:/[^\s"'<>()\[\]{}，。、；：""''！？【】（）]*)?)"""
    )

    private val NON_VIDEO_SUFFIX = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
        "html", "htm", "txt", "js", "css", "json", "xml", "apk", "zip", "rar", "7z", "pdf"
    )

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "m3u8", "mpd", "webm", "mov", "m4v", "flv", "mkv", "ts", "avi", "mp3", "m4a", "aac"
    )

    private val VIDEO_META_KEYS = setOf(
        "og:video",
        "og:video:url",
        "og:video:secure_url",
        "og:video:url:secure_url",
        "twitter:player:stream",
        "video:url",
        "video:secure_url"
    )

    private val HARD_SITES = mapOf(
        "douyin.com" to "抖音",
        "iesdouyin.com" to "抖音",
        "tiktok.com" to "TikTok",
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "kuaishou.com" to "快手",
        "xiaohongshu.com" to "小红书",
        "xhslink.com" to "小红书"
    )

    suspend fun extract(
        context: Context,
        rawInput: String,
        onEngineAttempt: ((index: Int, total: Int) -> Unit)? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        // 一旦确认是本机被按 IP 拦截，就别再一层层试下去，直接给结论
        var ipBlocked = false
        val input = findUrl(rawInput)
            ?: throw ExtractException("没有识别到链接，请粘贴包含 http(s) 链接的内容")
        val notes = mutableListOf<String>()

        // 1) 直链最快，不需要启动 Python 引擎
        val probed = try {
            Http.probe(input)
        } catch (_: Throwable) {
            null
        }
        val probedUrl = probed?.finalUrl ?: input
        if (isDirectMedia(probedUrl) || isMediaContentType(probed?.contentType)) {
            val item = buildItem(probedUrl, probedUrl, notes, MediaKind.OTHER)
                ?: throw ExtractException("这个链接看起来不是可播放的视频地址")
            val expanded = expandHls(listOf(item), notes)
            return@withContext ExtractResult(
                inputUrl = input,
                finalUrl = probedUrl,
                title = null,
                poster = null,
                items = expanded,
                engine = "直链解析",
                notes = notes
            )
        }

        // 2) B 站专用通道（公开接口能拿到带音轨的 1080P mp4）
        if (isBilibili(input)) {
            try {
                return@withContext extractBilibili(input, notes)
            } catch (t: Throwable) {
                notes += "B 站专用接口没取到（${t.message ?: "未知错误"}），换通用引擎重试。"
            }
        }

        // 2.5) 抖音分享页（先访问主页拿 Cookie，再解析 _ROUTER_DATA）
        // 抖音风控是随机的：第一次"没返回作品数据"很常见，这里自动重试两次，
        // 每次都重新预热 Cookie，用户不用自己反复点。
        if (DouyinExtractor.isDouyin(input)) {
            var lastError: String? = null
            repeat(3) { attempt ->
                try {
                    DouyinExtractor.extract(input, notes)?.let { return@withContext it }
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                }
                if (attempt < 2) {
                    notes += "抖音第 ${attempt + 1} 次没取到，正在重试…"
                    delay(if (attempt == 0) 1_200L else 2_500L)
                    Http.warmUpDouyin()
                }
            }
            notes += "抖音重试 3 次都没拿到作品数据（${lastError ?: "被风控"}），改用通用引擎。"
        }

        // 3) 通用解析引擎（本机直连；已被 IP 拦就跳过）
        if (!ipBlocked) {
        val ytDlpInput = if (DouyinExtractor.isDouyin(input)) DouyinExtractor.normalizeForYtDlp(input) else input
        try {
            val ytResult = YtDlpEngine.extract(context, ytDlpInput, onAttempt = onEngineAttempt)
            if (ytResult != null && ytResult.items.isNotEmpty()) {
                return@withContext ytResult.copy(notes = ytResult.notes + notes)
            }
            when (val status = YtDlpEngine.currentStatus()) {
                is YtDlpEngine.Status.Failed ->
                    notes += "通用解析引擎初始化失败（${status.message}），已改用内置解析器。"
                else ->
                    if (ytResult != null) notes += "通用引擎没有解析出可用格式，改用内置解析器。"
            }
        } catch (t: ExtractException) {
            notes += (t.message ?: "通用引擎解析失败") + "，已尝试备用线路。"
            if (isBotBlock(t.message.orEmpty()) || isBotBlock(t.detail.orEmpty())) ipBlocked = true
        } catch (t: Throwable) {
            notes += "通用引擎解析失败（${t.message ?: t.javaClass.simpleName}），已尝试备用线路。"
            if (isBotBlock(t.message.orEmpty())) ipBlocked = true
        }
        }

        // 3.2) TikTok / 抖音：本机不行就走在线免水印接口
        //（tikwm 同时支持抖音链接，抖音风控变严时这条能兜住）
        if (input.contains("tiktok", true) ||
            input.contains("douyin", true) ||
            input.contains("iesdouyin", true)
        ) {
            try {
                OnlineParse.tiktok(context, input, notes)?.let { result ->
                    return@withContext result.copy(notes = result.notes + notes)
                }
            } catch (t: Throwable) {
                notes += "在线解析接口失败（${t.message ?: t.javaClass.simpleName}）。"
            }
        }

        // 3.9) 已知解不了的站点：直接说清楚，不做无用尝试（省时间，也不给虚假期待）
        run {
            val host = runCatching { java.net.URI(input).host.orEmpty() }.getOrDefault("")
            UNSUPPORTED_SITES.firstOrNull { host.contains(it.first) }?.let { (_, name) ->
                throw ExtractException(
                    if (name == "YouTube")
                        "YouTube 已经不再支持（本机直连被拦，在线线路也不稳定），所以不做无用尝试了。" +
                                "抖音、快手、小红书、B 站、微博、西瓜视频这些都是正常的。"
                    else
                        "$name 这类站点目前解不了（有 DRM 加密，或者引擎不支持），" +
                                "不是网络问题，换节点也没用。抖音、快手、小红书、B 站、微博、西瓜视频这些是好的。"
                )
            }
        }

        // 4) 内置解析器兜底
        if (ipBlocked) {
            throw ExtractException(
                "这个站点对本机 IP 做了限制，页面解析同样拿不到地址。换个节点再试。",
                ipBlocked = true
            )
        }
        var finalUrl = probedUrl
        var pageTitle: String? = null
        var poster: String? = null

        val page = Http.get(finalUrl, maxBytes = 2_000_000)
        if (page.code !in 200..299) {
            throw ExtractException("网页访问失败：HTTP ${page.code}")
        }
        finalUrl = page.finalUrl
        val html = page.text
        if (html.isBlank()) {
            throw ExtractException("网页内容为空，可能需要登录或该站点禁止抓取")
        }

        pageTitle = Html.title(html)
        poster = Html.metaContent(html, "og:image")

        val candidates = LinkedHashSet<String>()
        candidates += Html.metaVideoUrls(html)
        candidates += Html.videoTagUrls(html)
        candidates += Html.jsonLdUrls(html)
        candidates += Html.rawMediaUrls(html)

        val items = mutableListOf<VideoItem>()
        val ignoredPageUrls = mutableSetOf<String>()
        candidates.forEach { candidate ->
            val absolute = runCatching { Http.absolutize(finalUrl, candidate) }.getOrNull()
                ?: return@forEach
            if (!absolute.startsWith("http")) return@forEach
            if (absolute == finalUrl) return@forEach
            // 只接受真正的媒体流；网页/嵌入页地址一律丢掉
            if (!MediaUrl.isPlayable(absolute)) {
                if (MediaUrl.isPageLike(absolute)) {
                    ignoredPageUrls += MediaUrl.hostOf(absolute)
                }
                return@forEach
            }
            val item = buildItem(absolute, finalUrl, notes, MediaKind.OTHER) ?: return@forEach
            if (items.none { it.url == item.url }) items += item
        }

        val expanded = expandHls(items, notes)
            .sortedWith(
                compareBy(
                    { kindPriority(it.kind) },
                    { it.label }
                )
            )

        val host = runCatching { java.net.URI(finalUrl).host.orEmpty() }.getOrDefault("")
        val matchedHard = HARD_SITES.filter { (domain, _) -> host.contains(domain) }
            .map { (domain, name) -> domain to name }
            .distinctBy { it.second }
        matchedHard.forEach { (_, name) ->
            notes += "$name 的播放地址带有加密签名，通用解析通常拿不到直链；可以换用分享出来的直链再试。"
        }
        if (ignoredPageUrls.isNotEmpty()) {
            notes += "页面里只找到网页播放地址（${ignoredPageUrls.joinToString("、")}），它不是视频流，已忽略。"
        }

        if (expanded.isEmpty()) {
            notes += "没有在页面里找到可直接播放的视频地址。可以试试：把视频分享时生成的链接、或视频本身的播放地址（.mp4/.m3u8）粘进来。"
        }

        ExtractResult(
            inputUrl = input,
            finalUrl = finalUrl,
            title = pageTitle,
            poster = poster,
            items = expanded,
            notes = notes
        )
    }

    // ---------------------------------------------------------------- helpers

    /** 这些站点有 DRM 加密或引擎不支持，试也是白试，直接如实告知 */
    private val UNSUPPORTED_SITES = listOf(
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "youku.com" to "优酷",
        "v.qq.com" to "腾讯视频",
        "iqiyi.com" to "爱奇艺",
        "novelquickapp.com" to "红果短剧",
        "pipix.com" to "皮皮虾",
        "channels.weixin.qq.com" to "微信视频号"
    )

    /** 提示里的超长 URL 截断，避免整屏都是链接 */
    private fun shorten(text: String): String =
        Regex("""https?://\S{70,}""").replace(text) { match ->
            match.value.take(64) + "…"
        }

    /** 判断错误信息是不是「机器人校验 / 需要登录」这一类按 IP 拦截 */
    private fun isBotBlock(message: String): Boolean {
        val text = message.lowercase()
        return text.contains("not a bot") ||
                text.contains("login_required") ||
                text.contains("sign in to confirm") ||
                text.contains("confirm your age") ||
                text.contains("cookies for the authentication")
    }

    fun findUrl(text: String): String? {
        val withScheme = URL_REGEX.find(text)?.value
            ?.trim()
            ?.trimEnd('.', ',', ')', ']', '。', '，')
        if (!withScheme.isNullOrBlank()) return withScheme

        val bare = BARE_DOMAIN.find(text)?.value
            ?.trim()
            ?.trimEnd('.', ',', ')', ']', '。', '，')
        if (!bare.isNullOrBlank()) {
            val suffix = bare.substringBefore('/').substringAfterLast('.').lowercase()
            if (suffix in NON_VIDEO_SUFFIX) return null
            return "https://$bare"
        }
        return null
    }

    /** 一次粘贴多条链接时，把文本里所有链接都取出来 */
    fun findUrls(text: String): List<String> {
        val found = URL_REGEX.findAll(text)
            .map { it.value.trim().trimEnd('.', ',', ')', ']', '。', '，') }
            .filter { it.isNotBlank() }
            .toList()
        if (found.isNotEmpty()) return found.distinct()
        return listOfNotNull(findUrl(text))
    }

    private fun kindPriority(kind: MediaKind): Int = when (kind) {
        MediaKind.MP4 -> 0
        MediaKind.WEBM -> 1
        MediaKind.HLS -> 2
        MediaKind.DASH -> 3
        MediaKind.AUDIO -> 4
        MediaKind.IMAGE -> 5
        MediaKind.OTHER -> 6
    }

    private fun isMediaContentType(contentType: String?): Boolean {
        val type = contentType?.lowercase() ?: return false
        return type.startsWith("video/") || type.startsWith("audio/") ||
                type.contains("mpegurl") || type.contains("dash+xml")
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        return if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    }

    private fun isDirectMedia(url: String): Boolean = extensionOf(url) in MEDIA_EXTENSIONS

    private fun kindOf(url: String): MediaKind = when (extensionOf(url)) {
        "mp4", "m4v", "mov" -> MediaKind.MP4
        "m3u8" -> MediaKind.HLS
        "mpd" -> MediaKind.DASH
        "webm" -> MediaKind.WEBM
        "mp3", "m4a", "aac" -> MediaKind.AUDIO
        else -> MediaKind.OTHER
    }

    private fun buildItem(
        url: String,
        pageUrl: String,
        notes: MutableList<String>,
        fallbackKind: MediaKind
    ): VideoItem? {
        if (!url.startsWith("http")) return null
        if (url.contains("favicon") || url.endsWith(".gif")) return null
        val kind = if (kindOf(url) == MediaKind.OTHER) fallbackKind else kindOf(url)
        val referer = runCatching {
            val uri = java.net.URI(pageUrl)
            val authority = uri.authority
            if (authority.isNullOrBlank()) pageUrl else "${uri.scheme}://$authority/"
        }.getOrDefault(pageUrl)
        val type = when (kind) {
            MediaKind.MP4 -> "video/mp4"
            MediaKind.HLS -> "application/vnd.apple.mpegurl"
            MediaKind.DASH -> "application/dash+xml"
            MediaKind.WEBM -> "video/webm"
            MediaKind.AUDIO -> "audio/mpeg"
            MediaKind.IMAGE -> "image/jpeg"
            MediaKind.OTHER -> "video/*"
        }
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to Http.UA,
            "Accept" to type
        )
        val label = when (kind) {
            MediaKind.MP4 -> "MP4 直链"
            MediaKind.HLS -> "HLS 播放列表"
            MediaKind.DASH -> "DASH 清单"
            MediaKind.WEBM -> "WebM 直链"
            MediaKind.AUDIO -> "音频直链"
            MediaKind.IMAGE -> "图片"
            MediaKind.OTHER -> "视频地址"
        }
        return VideoItem(url = url, label = label, kind = kind, headers = headers)
    }

    /** 把 m3u8 主播放列表展开成各个清晰度。 */
    private fun expandHls(items: List<VideoItem>, notes: MutableList<String>): List<VideoItem> {
        val output = mutableListOf<VideoItem>()
        items.forEach { item ->
            if (item.kind != MediaKind.HLS) {
                output += item
                return@forEach
            }
            val playlist = try {
                Http.get(item.url, item.headers, maxBytes = 600_000).text
            } catch (_: Throwable) {
                ""
            }
            if (playlist.isBlank() || !playlist.contains("#EXTM3U")) {
                output += item
                return@forEach
            }
            val variants = mutableListOf<VideoItem>()
            val regex = Regex("""#EXT-X-STREAM-INF:([^\r\n]+)[\r\n]+([^\r\n#]+)""")
            regex.findAll(playlist).forEach { match ->
                val attributes = match.groupValues[1]
                val uri = match.groupValues[2].trim()
                if (uri.isEmpty()) return@forEach
                val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(attributes)
                val height = resolution?.groupValues?.get(2)
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(attributes)?.groupValues?.get(1)
                val quality = when {
                    height != null -> "${height}p"
                    bandwidth != null -> "${bandwidth.toLongOrNull()?.div(1000) ?: 0} kbps"
                    else -> "自动"
                }
                val absolute = runCatching { Http.absolutize(item.url, uri) }.getOrDefault(uri)
                variants += item.copy(
                    url = absolute,
                    label = "$quality · HLS",
                    kind = MediaKind.HLS
                )
            }
            if (variants.isEmpty()) {
                output += item
            } else {
                notes += "已从 m3u8 主列表解析出 ${variants.size} 个清晰度。"
                output += variants.reversed()
            }
        }
        return output
    }

    // ------------------------------------------------------------- bilibili

    private data class BiliQuality(val label: String, val height: Int, val fps: Int)

    private fun biliQuality(code: Int): BiliQuality? = when (code) {
        127 -> BiliQuality("8K", 4320, 60)
        126 -> BiliQuality("杜比视界", 2160, 60)
        125 -> BiliQuality("HDR", 2160, 60)
        120 -> BiliQuality("4K", 2160, 60)
        116 -> BiliQuality("1080P60", 1080, 60)
        112 -> BiliQuality("1080P+", 1080, 60)
        80 -> BiliQuality("1080P", 1080, 30)
        74 -> BiliQuality("720P60", 720, 60)
        64 -> BiliQuality("720P", 720, 30)
        32 -> BiliQuality("480P", 480, 30)
        16 -> BiliQuality("360P", 360, 30)
        else -> null
    }

    private fun isBilibili(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return host.contains("bilibili.com") || host.contains("b23.tv") || host.contains("acg.tv")
    }

    private fun extractBilibili(input: String, notes: MutableList<String>): ExtractResult {
        val resolved = try {
            Http.resolve(input)
        } catch (_: Throwable) {
            input
        }
        val bvid = Regex("""(BV[0-9A-Za-z]{8,14})""").find(resolved)?.value
        val aid = Regex("""av(\d+)""", RegexOption.IGNORE_CASE).find(resolved)?.groupValues?.get(1)
        if (bvid == null && aid == null) {
            throw ExtractException("没有从链接里识别到 B 站视频号（BV/av）")
        }

        // B 站风控对「浏览器型 UA」会返回 412 挑战页，接口调用要用中性 UA；
        // 而 CDN 播放地址两种 UA 都能取，用浏览器 UA 更稳。
        val apiHeaders = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to Http.API_UA
        )
        val playHeaders = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to Http.UA,
            "Accept" to "video/mp4"
        )
        val viewApi = if (bvid != null) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        } else {
            "https://api.bilibili.com/x/web-interface/view?aid=$aid"
        }
        val viewResponse = try {
            Http.get(viewApi, apiHeaders, maxBytes = 1_000_000)
        } catch (t: Throwable) {
            throw ExtractException("B 站接口请求失败：${t.message}")
        }
        if (viewResponse.code == 412) {
            throw ExtractException("B 站风控拦截（HTTP 412），请稍后重试")
        }
        val root = runCatching { JSONObject(viewResponse.text) }.getOrNull()
            ?: throw ExtractException("B 站接口返回内容无法解析（HTTP ${viewResponse.code}）")
        val data = root.optJSONObject("data")
            ?: throw ExtractException("B 站接口返回异常：${root.optString("message").ifBlank { "unknown" }}")

        val title = data.optString("title").ifBlank { null }
        val poster = data.optString("pic").ifBlank { null }
        val realBvid = data.optString("bvid").ifBlank { bvid.orEmpty() }
        val cid = data.optLong("cid")
        val pages = data.optInt("videos", 1)
        if (pages > 1) {
            notes += "该视频有 $pages 个分 P，这里提取的是第 1 个分 P。"
        }

        val playApi = "https://api.bilibili.com/x/player/playurl" +
                "?bvid=$realBvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1"
        val playJson = try {
            Http.get(playApi, apiHeaders, maxBytes = 1_000_000).text
        } catch (t: Throwable) {
            throw ExtractException("B 站播放地址请求失败：${t.message}")
        }
        val playRoot = runCatching { JSONObject(playJson) }.getOrNull()
        val playData = playRoot?.optJSONObject("data")
        val durl = playData?.optJSONArray("durl")

        val qualityCode = playData?.optInt("quality", 0) ?: 0
        val qualityInfo = biliQuality(qualityCode)

        val items = mutableListOf<VideoItem>()
        if (durl != null) {
            for (i in 0 until durl.length()) {
                val entry = durl.optJSONObject(i) ?: continue
                val mediaUrl = entry.optString("url")
                if (mediaUrl.isBlank()) continue
                val size = entry.optLong("size", 0L)
                items += VideoItem(
                    url = mediaUrl,
                    label = buildString {
                        append("MP4（含音轨）")
                        if (durl.length() > 1) append(" · 分段 ${i + 1}")
                        if (qualityInfo != null) append(" · ${qualityInfo.label}")
                        if (size > 0) append(" · ").append(String.format("%.1f MB", size / 1024.0 / 1024))
                    },
                    kind = MediaKind.MP4,
                    headers = playHeaders,
                    sizeBytes = size,
                    height = qualityInfo?.height ?: 0,
                    fps = qualityInfo?.fps ?: 0
                )
            }
        }
        if (items.isEmpty()) {
            notes += "接口没有返回可用的 mp4 地址，可能是会员专享、地区限制或需要登录。"
        }
        val quality = playData?.optInt("quality", 0) ?: 0
        if (quality > 0) {
            notes += "当前接口可获取的最高清晰度代号：$quality（高清画质通常需要登录账号）。"
        }

        return ExtractResult(
            inputUrl = input,
            finalUrl = resolved,
            title = title,
            poster = poster,
            items = items,
            uploader = data.optJSONObject("owner")?.optString("name")?.takeIf { it.isNotBlank() },
            durationSeconds = data.optInt("duration", 0),
            engine = "B 站公开接口",
            notes = notes
        )
    }
}

/** 轻量 HTML 解析，只做正则级别的信息抽取。 */
object Html {

    private val VIDEO_META_KEYS = setOf(
        "og:video",
        "og:video:url",
        "og:video:secure_url",
        "og:video:url:secure_url",
        "twitter:player:stream",
        "video:url",
        "video:secure_url"
    )

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val VIDEO_TAG = Regex("""<(?:video|source|embed)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val MEDIA_URL = Regex(
        """(?:https?:)?//[^\s"'<>\\]+?\.(?:mp4|m3u8|mpd|webm|mov|m4v|flv)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    private fun attributes(tag: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ATTR.findAll(tag).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[3]
                .ifEmpty { match.groupValues[4] }
                .ifEmpty { match.groupValues[5] }
            if (map[key] == null) map[key] = value
        }
        return map
    }

    fun metaContent(html: String, key: String): String? {
        META_TAG.findAll(html).forEach { match ->
            val attributes = attributes(match.value)
            val name = attributes["property"] ?: attributes["name"] ?: attributes["itemprop"]
            if (name.equals(key, true)) {
                val content = attributes["content"]
                if (!content.isNullOrBlank()) return unescape(content)
            }
        }
        return null
    }

    fun title(html: String): String? {
        val raw = TITLE_TAG.find(html)?.groupValues?.get(1) ?: return null
        val cleaned = unescape(raw).replace(Regex("""\s+"""), " ").trim()
        return cleaned.ifBlank { null }
    }

    fun metaVideoUrls(html: String): List<String> {
        val result = mutableListOf<String>()
        META_TAG.findAll(html).forEach { match ->
            val attributes = attributes(match.value)
            val name = (attributes["property"] ?: attributes["name"] ?: attributes["itemprop"])
                ?.lowercase() ?: return@forEach
            if (name in VIDEO_META_KEYS) {
                attributes["content"]?.let { content ->
                    if (content.startsWith("http") || content.startsWith("//")) {
                        result += unescape(content)
                    }
                }
            }
        }
        return result
    }

    fun videoTagUrls(html: String): List<String> {
        val result = mutableListOf<String>()
        VIDEO_TAG.findAll(html).forEach { match ->
            val attributes = attributes(match.value)
            listOf("src", "data-src", "data-original", "data-video", "data-url").forEach { key ->
                attributes[key]?.let { value ->
                    if (value.startsWith("http") || value.startsWith("//") || value.startsWith("/")) {
                        result += unescape(value)
                    }
                }
            }
        }
        return result
    }

    fun jsonLdUrls(html: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex(""""(?:contentUrl|embedUrl|url)"\s*:\s*"([^"]+\.(?:mp4|m3u8|mpd|webm)[^"]*)"""",
            RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { match ->
            result += unescape(match.groupValues[1])
        }
        return result
    }

    fun rawMediaUrls(html: String): List<String> {
        val normalized = unescape(html)
        return MEDIA_URL.findAll(normalized).map { it.value }.distinct().toList()
    }

    fun unescape(text: String): String = text
        .replace("\\/", "/")
        .replace("\\u002F", "/", true)
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .trim()
}
