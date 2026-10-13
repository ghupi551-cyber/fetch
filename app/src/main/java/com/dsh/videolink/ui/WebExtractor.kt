package com.dsh.videolink.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.videolink.extract.Http
import com.dsh.videolink.extract.MediaKind
import com.dsh.videolink.extract.VideoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * 「内置浏览器」解析。
 *
 * 思路：TikTok 会拦本机的普通请求（机器人校验），
 * 但**真实浏览器**播放时是能拿到视频流的。所以这里开一个隐藏的 WebView，
 * 让它真的去播一遍，我们把它请求到的媒体地址截下来 —— 这些地址是本机自己请求的，
 * 不会出现「解析出来却播不了（黑屏）」的问题。
 *
 * 不需要 Cookie、不需要第三方服务器，全程在本机完成。
 */
object WebExtractor {

    private const val TIMEOUT_MS = 12_000L

    data class Request(val id: String, val url: String)

    data class WebResult(val media: List<VideoItem>, val title: String?, val pageUrl: String)

    var request by mutableStateOf<Request?>(null)
        private set

    private var pending: CompletableDeferred<WebResult>? = null

    fun isSupported(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        // 抖音有自己的解析通道且更快，这里只处理 TikTok
        return host.contains("tiktok.com")
    }

    /**
     * 把用户链接换成「最适合在浏览器里播放」的地址。
     * 注意：这里面有网络请求（短链还原），必须在 IO 线程调用，
     * 否则会抛 NetworkOnMainThreadException 直接把应用打崩。
     */
    fun playUrlFor(url: String, resolved: String? = null): String =
        resolved?.takeIf { it.isNotBlank() } ?: url

    suspend fun extract(url: String): WebResult? {
        return try {
            // 抖音/TikTok 短链还原放在 IO 线程
            val resolved = withContext(Dispatchers.IO) {
                runCatching { Http.resolve(url, ua = Http.UA) }.getOrNull()
            }
            val deferred = CompletableDeferred<WebResult>()
            pending = deferred
            request = Request(UUID.randomUUID().toString(), playUrlFor(url, resolved))
            val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
            request = null
            pending = null
            result
        } catch (t: Throwable) {
            // 兜底：内置浏览器这条路出任何问题都不该让应用崩
            request = null
            pending = null
            null
        }
    }

    fun complete(result: WebResult) {
        pending?.complete(result)
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        request = null
    }
}

/**
 * 隐藏的 WebView：只在有请求时挂载，1x1 透明，不改动界面。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HiddenWebExtractor() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 常驻一份收集容器，随请求切换
    val collected = remember { linkedMapOf<String, VideoItem>() }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var pageUrl by remember { mutableStateOf("") }

    // 只创建一次：创建 WebView 要几百毫秒，放在解析时才建会卡住界面
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = Http.UA
            settings.loadsImagesAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    runCatching {
                        request?.url?.toString()?.let { url ->
                            classify(url)?.let { item ->
                                synchronized(collected) { collected[item.url] = item }
                            }
                        }
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    url?.let { pageUrl = it }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    url?.let { pageUrl = it }
                    view?.evaluateJavascript(JS_COLLECT) { value ->
                        runCatching {
                            val decoded = value
                                ?.removePrefix("\"")
                                ?.removeSuffix("\"")
                                ?.replace("\\\"", "\"")
                                ?.replace("\\\\", "\\")
                                ?: return@evaluateJavascript
                            val obj = JSONObject(decoded)
                            val title = obj.optString("t")
                            if (title.isNotBlank()) pageTitle = title
                            val array: JSONArray = obj.optJSONArray("u") ?: return@evaluateJavascript
                            for (i in 0 until array.length()) {
                                val mediaUrl = array.optString(i)
                                runCatching { classify(mediaUrl) }.getOrNull()?.let { item ->
                                    synchronized(collected) { collected[item.url] = item }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val req = WebExtractor.request
    LaunchedEffect(req?.id) {
        if (req == null) return@LaunchedEffect
        synchronized(collected) { collected.clear() }
        pageTitle = null
        pageUrl = req.url
        runCatching { webView.loadUrl(req.url) }
        delay(5_000L)
        if (collected.isNotEmpty()) {
            runCatching {
                WebExtractor.complete(
                    WebExtractor.WebResult(sortMedia(collected.toList().toMap().values), pageTitle, pageUrl)
                )
            }
            return@LaunchedEffect
        }
        delay(4_000L)
        if (collected.isNotEmpty()) {
            runCatching {
                WebExtractor.complete(
                    WebExtractor.WebResult(sortMedia(collected.toList().toMap().values), pageTitle, pageUrl)
                )
            }
        }
    }

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f),
        factory = { webView }
    )
}

/** 页面里找媒体地址：<video> / <source> / YouTube 播放器数据 */
private val JS_COLLECT = """
(function(){
  var out = [];
  try {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.src) out.push(v.src);
      var ss = v.querySelectorAll('source');
      for (var j = 0; j < ss.length; j++) if (ss[j].src) out.push(ss[j].src);
    }
  } catch (e) {}
  return JSON.stringify({ t: document.title, u: out });
})();
""".trimIndent()

/** 判断一个请求地址是不是媒体流，并给出一个可读的标签 */
private fun classify(url: String): VideoItem? {
    if (!url.startsWith("http")) return null
    val lower = url.lowercase()
    // 带 range 的地址是**分片**：播不出来（黑屏）、下下来也是残片，直接丢掉
    if (lower.contains("range=")) return null
    val isGoogleVideo = lower.contains("googlevideo.com/videoplayback")
    val isTikTokVideo = (lower.contains("tiktok") || lower.contains("tiktokcdn") ||
            lower.contains("byteoversea") || lower.contains("douyin")) &&
            (lower.contains(".mp4") || lower.contains("mime_type=video") || lower.contains("video_mp4"))
    if (!isGoogleVideo && !isTikTokVideo) return null

    val itag = Regex("""[?&]itag=(\d+)""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
    val mime = Regex("""[?&]mime=([^&]+)""").find(lower)?.groupValues?.get(1)
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    val audioOnly = mime?.startsWith("audio") == true || itag in AUDIO_ITAGS
    val videoOnly = itag in VIDEO_ONLY_ITAGS || mime?.startsWith("video") == true && audioOnly.not() &&
            itag !in MUXED_ITAGS && isGoogleVideo

    val kind = when {
        audioOnly -> MediaKind.AUDIO
        itag == 18 || itag == 22 || itag == 59 || itag == 78 || itag == 17 -> MediaKind.MP4
        itag == 43 -> MediaKind.WEBM
        isTikTokVideo -> MediaKind.MP4
        else -> MediaKind.MP4
    }

    val quality = when {
        itag == 22 -> "720p"
        itag == 18 -> "360p"
        itag == 59 || itag == 78 -> "480p"
        itag == 17 -> "144p"
        itag == 137 -> "1080p"
        itag == 136 -> "720p"
        itag == 135 -> "480p"
        itag == 134 -> "360p"
        itag == 160 -> "144p"
        itag == 140 || itag == 139 -> "音频"
        itag == 251 || itag == 250 -> "音频"
        itag == 43 -> "360p"
        mirror(lower) -> mirrorQuality(lower)
        else -> ""
    }

    val label = when {
        audioOnly -> "音频" + if (quality.isNotBlank() && quality != "音频") " · $quality" else ""
        videoOnly -> "无音轨视频" + if (quality.isNotBlank()) " · $quality" else ""
        quality.isNotBlank() -> "$quality · MP4（含音轨）"
        else -> "MP4（含音轨）"
    }

    // 图片（封面等）不是视频，绝不能再当成播放地址
    if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
        lower.contains(".webp") || lower.contains(".gif") || lower.contains("mime=image") ||
        lower.contains("mime_type=image")
    ) {
        return null
    }

    // Referer 必须跟来源站点匹配，否则 CDN 会返回 403（表现就是黑屏）
    val referer = when {
        lower.contains("googlevideo") || lower.contains("youtube") -> "https://www.youtube.com/"
        lower.contains("tiktok") || lower.contains("byteoversea") -> "https://www.tiktok.com/"
        lower.contains("douyin") || lower.contains("douyinvod") -> "https://www.douyin.com/"
        else -> ""
    }
    val headers = buildMap {
        if (referer.isNotEmpty()) put("Referer", referer)
        put("User-Agent", Http.UA)
    }

    val size = ITAG_SIZE[itag ?: -1] ?: Triple(0, 0, 0)
    val guessed = when (itag) {
        18 -> Triple(640, 360, 30)
        22 -> Triple(1280, 720, 30)
        17 -> Triple(176, 144, 30)
        59, 78 -> Triple(854, 480, 30)
        43 -> Triple(640, 360, 30)
        137 -> Triple(1920, 1080, 30)
        136 -> Triple(1280, 720, 30)
        135 -> Triple(854, 480, 30)
        134 -> Triple(640, 360, 30)
        160 -> Triple(256, 144, 30)
        298 -> Triple(1280, 720, 60)
        299 -> Triple(1920, 1080, 60)
        303 -> Triple(1920, 1080, 60)
        else -> Triple(0, 0, 0)
    }
    val dims = if (size.first > 0) size else guessed

    return VideoItem(
        url = url,
        label = label,
        kind = kind,
        headers = headers,
        muxed = !audioOnly && !videoOnly,
        width = dims.first,
        height = dims.second,
        fps = dims.third
    )
}

private fun mirror(url: String): Boolean = url.contains("mime_type=video")

private fun mirrorQuality(url: String): String = when {
    url.contains("1080") -> "1080p"
    url.contains("720") -> "720p"
    url.contains("480") -> "480p"
    else -> ""
}

/** itag → (宽, 高, 帧率)，用来在界面上显示画质和帧率 */
private val ITAG_SIZE: Map<Int, Triple<Int, Int, Int>> = mapOf(
    17 to Triple(176, 144, 30),
    18 to Triple(640, 360, 30),
    22 to Triple(1280, 720, 30),
    43 to Triple(640, 360, 30),
    59 to Triple(854, 480, 30),
    78 to Triple(854, 480, 30)
)

private val AUDIO_ITAGS = setOf(139, 140, 141, 171, 172, 249, 250, 251)
private val VIDEO_ONLY_ITAGS = setOf(133, 134, 135, 136, 137, 138, 160, 242, 243, 244, 247, 248,
    264, 266, 271, 272, 278, 298, 299, 302, 303, 308, 313, 315, 337, 394, 395, 396, 397, 398, 399)
private val MUXED_ITAGS = setOf(17, 18, 22, 43, 59, 78)

/** 含音轨的排前面，其次按清晰度从高到低 */
private fun sortMedia(items: Collection<VideoItem>): List<VideoItem> =
    items.distinctBy { it.url }
        .sortedWith(
            compareBy(
                { if (it.kind == MediaKind.AUDIO) 1 else 0 },
                { -qualityRank(it.label) }
            )
        )

private fun qualityRank(label: String): Int = when {
    label.contains("1080") -> 1080
    label.contains("720") -> 720
    label.contains("480") -> 480
    label.contains("360") -> 360
    label.contains("144") -> 144
    else -> 0
}
