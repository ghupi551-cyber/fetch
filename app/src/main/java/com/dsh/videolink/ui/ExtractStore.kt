package com.dsh.videolink.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dsh.videolink.data.HistoryStore
import com.dsh.videolink.extract.ExtractException
import com.dsh.videolink.extract.Http
import com.dsh.videolink.extract.MediaKind
import com.dsh.videolink.extract.MediaMerger
import com.dsh.videolink.extract.NetworkProbe
import com.dsh.videolink.extract.MediaProbe
import com.dsh.videolink.extract.OnlineParse
import com.dsh.videolink.extract.RoutePlan
import kotlinx.coroutines.launch
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.extract.ExtractResult
import com.dsh.videolink.extract.VideoExtractor
import com.dsh.videolink.extract.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface ExtractUiState {
    data object Idle : ExtractUiState

    /** phase = 当前阶段说明（例如「第 2/3 条线路」） */
    data class Loading(
        val current: Int = 0,
        val total: Int = 1,
        val phase: String = ""
    ) : ExtractUiState

    data class BatchSuccess(
        val results: List<ExtractResult>,
        val failures: List<String>
    ) : ExtractUiState

    data class Error(val message: String, val detail: String? = null) : ExtractUiState
}

/**
 * 解析状态放在应用级单例里。
 *
 * 之前状态和协程都挂在主页的 composition 上，一换标签页主页就被销毁，
 * 于是「解析被中断」而且「结果也没了」。放到这里之后，切页不影响解析。
 */
object ExtractStore {

    private const val MAX_LINKS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    var state by mutableStateOf<ExtractUiState>(ExtractUiState.Idle)
        private set

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, rawInput: String, history: HistoryStore) {
        // 上一次还在跑（比如某条线路卡住）就先掐掉再开始，
        // 否则用户点历史「重新解析」会毫无反应。
        if (isRunning) {
            job?.cancel()
            YtDlpEngine.cancelCurrent()
        }
        val url = VideoExtractor.findUrl(rawInput)
        if (url.isNullOrBlank()) {
            state = ExtractUiState.Error("没有识别到链接，请粘贴包含链接的内容")
            return
        }

        val app = context.applicationContext
        val site = RoutePlan.siteOf(url)

        job = scope.launch {
            var lastError: ExtractException? = null

            // 国内网络访问外网站点：先花 3.5 秒确认一下能不能连上，
            // 连不上就立刻告诉用户，不要让他等一两分钟才看到失败。
            if (site != RoutePlan.Site.OTHER && site != RoutePlan.Site.OTHER) {
                state = ExtractUiState.Loading(0, 1, "正在解析链接…")
                val reachable = withContext(Dispatchers.IO) {
                    runCatching { RoutePlan.quickReachable(app, url) }.getOrDefault(true)
                }
                if (!reachable) {
                    // 下一次点解析必须重新探测（用户可能已经连上代理）
                    withContext(Dispatchers.IO) { RoutePlan.invalidateReachability(app) }
                    val name = "TikTok"
                    state = ExtractUiState.Error(
                        "当前网络连不上 $name，需要先连上代理（VPN）再解析。\n\n" +
                                "抖音、B 站等国内站点不需要代理，可以直接解析。"
                    )
                    return@launch
                }
            }

            // 本机这一路：内置浏览器 → 内置引擎/通用引擎/网页兜底
            suspend fun tryLocal(useBrowser: Boolean = true): ExtractResult? {
                val startedAt = System.currentTimeMillis()
                return try {
                    var webResult: ExtractResult? = null
                    // 实测本机连不上这个站点时，别再用内置浏览器去加载（会白等甚至出错）
                    // useBrowser=false 时不启浏览器：解析时会省下几 MB 流量（并发竞速时用）
                    if (useBrowser && WebExtractor.isSupported(url) && RoutePlan.localReachable(app, url)) {
                        state = ExtractUiState.Loading(0, 1, "正在解析链接…")
                        val web = WebExtractor.extract(url)
                        if (web != null && web.media.isNotEmpty()) {
                            webResult = ExtractResult(
                                inputUrl = url,
                                finalUrl = web.pageUrl,
                                title = web.title,
                                poster = null,
                                items = web.media,
                                engine = "内置浏览器",
                                notes = mutableListOf("地址是在本机真实播放时抓到的。")
                            )
                        }
                    }
                    val result = webResult ?: VideoExtractor.extract(app, url) { index, total ->
                        state = ExtractUiState.Loading(
                            current = index,
                            total = total,
                            phase = if (total > 1) "本机 · 第 ${index + 1}/$total 条线路" else "本机解析中…"
                        )
                    }
                    RoutePlan.markLocalOk(app, site, System.currentTimeMillis() - startedAt)
                    result
                } catch (t: ExtractException) {
                    lastError = t
                    if (t.ipBlocked) RoutePlan.markLocalBlocked(app, site)
                    null
                } catch (t: Throwable) {
                    lastError = ExtractException(t.message ?: t.javaClass.simpleName)
                    null
                }
            }

            // 在线这一路：交给公共解析线路去取，把可播放地址送回来。
            // 注意：这两个都是**阻塞式**网络调用，必须放在 IO 线程，
            // 否则主线程会抛 NetworkOnMainThreadException（就是之前的闪退）
            // 并且界面会卡住。
            suspend fun tryOnline(): ExtractResult? {
                if (site == RoutePlan.Site.OTHER) return null
                state = ExtractUiState.Loading(0, 1, "正在解析链接…")
                val notes = mutableListOf<String>()
                val started = System.currentTimeMillis()
                val result = try {
                    withContext(Dispatchers.IO) {
                        when (site) {
                                RoutePlan.Site.TIKTOK -> OnlineParse.tiktok(app, url, notes)
                            else -> null
                        }
                    }
                } catch (t: Throwable) {
                    notes += "在线解析失败（${t.message ?: t.javaClass.simpleName}）。"
                    null
                }
                val cost = System.currentTimeMillis() - started
                return if (result != null && result.items.isNotEmpty()) {
                    RoutePlan.markOnlineOk(app, site, cost)
                    result.copy(notes = result.notes + notes)
                } else {
                    RoutePlan.markOnlineFail(app, site)
                    null
                }
            }

            state = try {
                state = ExtractUiState.Loading(0, 1, "正在解析…")
                // 智能选路：后台测下来哪条好就先走哪条
                val foreign = site != RoutePlan.Site.OTHER
                var result: ExtractResult? = if (foreign) {
                    // 本地和在线同时开跑，谁先成功用谁。
                    // 本地这一路**不启内置浏览器**（浏览器要加载整个页面，几 MB 流量），
                    // 浏览器留到两条路都失败后再单独试。
                    kotlinx.coroutines.coroutineScope {
                        val channel = kotlinx.coroutines.channels.Channel<ExtractResult?>(2)
                        val localJob = launch {
                            channel.send(runCatching { tryLocal(useBrowser = false) }.getOrNull())
                        }
                        val onlineJob = launch { channel.send(runCatching { tryOnline() }.getOrNull()) }
                        var first: ExtractResult? = null
                        repeat(2) {
                            if (first == null) {
                                val r = channel.receive()
                                if (r != null) first = r
                            }
                        }
                        if (first != null) {
                            // 另一条路不用再跑了
                            localJob.cancel()
                            onlineJob.cancel()
                            withContext(Dispatchers.Main) { WebExtractor.cancel() }
                        }
                        channel.close()
                        first
                    } ?: run {
                        // 两条都没成，才动用内置浏览器（最费流量，但也是最后的机会）
                        tryLocal(useBrowser = true)
                    }
                } else {
                    // 国内站点：本机快，直接本机
                    tryLocal() ?: tryOnline()
                }

                if (result == null) {
                    throw lastError ?: ExtractException(
                        if (foreign) "在线线路这次都没成功（可能被限流或需要换节点）。稍后再试，" +
                                "或换个节点；本机直连对这个站点通常也走不通，就没有再浪费你的时间。"
                        else "没能解析出可播放的地址，换个链接或稍后再试"
                    )
                }

                // 出结果前先真的取一下地址：取不到就先别给用户，免得点开黑屏。
                // 注意：取不到**不等于**不能用 —— 分离流还能靠本机合成拿到本地文件，
                // 所以这里只是「挑一个能取到的」，挑不到就交给合成环节判断。
                val picked = pickPlayable(app, result!!)
                var verified = if (picked != null) {
                    autoMergeIfNeeded(app, picked)
                } else {
                    val merged = autoMergeIfNeeded(app, result!!)
                    if (merged.items.any { it.url.startsWith("file:") }) {
                        merged
                    } else {
                        throw ExtractException(
                            "地址是拿到了，但这台手机去取的时候被站点拒绝（403 或超时），本机合成也没成功。" +
                                    "通常是网络 / IP / 地区限制，换一个节点再试即可。"
                        )
                    }
                }
                history.add(url, verified.title)
                ExtractUiState.BatchSuccess(listOf(verified), emptyList())
            } catch (t: ExtractException) {
                if (t.ipBlocked) RoutePlan.markLocalBlocked(app, site)
                // 只在失败说明里补一句现状，不提前拦着不让试
                val reachable = RoutePlan.localReachable(app, url)
                val vpnOn = runCatching { NetworkProbe.vpnActive(app) }.getOrDefault(false)
                val suffix = when {
                    // 国内站点 + 挂着代理 → 这是最常见的原因，直接说清楚
                    site == RoutePlan.Site.OTHER && vpnOn ->
                        "\n\n（提示：检测到你正在用代理。抖音、B 站这类国内站点在挂代理时会被风控，" +
                                "把代理关掉再解析就正常了。）"
                    site != RoutePlan.Site.OTHER && !reachable ->
                        "\n\n（现状：当前网络连不上这个站点，本机和在线线路都没成功。）"
                    else -> ""
                }
                ExtractUiState.Error(
                    clean(t.message ?: "提取失败，请换个链接再试") + suffix,
                    t.detail
                )
            } catch (t: Throwable) {
                ExtractUiState.Error(t.message ?: "提取失败，请换个链接再试")
            }
        }
    }

    fun clear() {
        job?.cancel()
        YtDlpEngine.cancelCurrent()
        job = null
        state = ExtractUiState.Idle
    }

    /**
     * 分离流（画面一条、声音一条）自动合成为一个完整视频。
     * 合成结果放在缓存里，预览和下载都用它，不会再出现「黑屏」或「下下来打不开」。
     */
    private suspend fun autoMergeIfNeeded(context: Context, result: ExtractResult): ExtractResult {
        // 候选：所有「无音轨视频」按清晰度从高到低 + 所有音频
        val videos = result.items
            .filter { it.kind != MediaKind.AUDIO && it.kind != MediaKind.IMAGE && !it.muxed }
            .sortedByDescending { it.height }
        val audios = result.items.filter { it.kind == MediaKind.AUDIO }
        val video = videos.firstOrNull() ?: return result
        val audio = audios.firstOrNull() ?: return result

        // 逐对尝试（最多 2 个视频 × 2 个音频 = 4 种组合），坏地址自动换下一个
        var lastError: String? = null
        val tried = mutableSetOf<String>()
        videos.take(2).forEach { v ->
            audios.take(2).forEach { a ->
                val key = "${v.url}|${a.url}"
                if (key in tried) return@forEach
                tried += key
                if (MediaMerger.isPartialUrl(v.url) || MediaMerger.isPartialUrl(a.url)) {
                    lastError = "地址是分片"
                    return@forEach
                }
                try {
                    val file = MediaMerger.merge(
                        context = context,
                        videoUrl = v.url,
                        videoHeaders = v.headers,
                        audioUrl = a.url,
                        audioHeaders = a.headers
                    ) { progress ->
                        state = ExtractUiState.Loading(0, 1, progress.stage)
                    }
                    val merged = v.copy(
                        url = file.toURI().toString(),
                        label = v.label.replace("无音轨视频", "已合成完整视频"),
                        headers = emptyMap(),
                        muxed = true,
                        sizeBytes = file.length(),
                        fileName = (result.title?.takeIf { it.isNotBlank() } ?: "video")
                    )
                    return result.copy(
                        items = listOf(merged) + audios,
                        notes = result.notes + "已在本机把画面和声音合成一个完整视频，可直接播放或下载。"
                    )
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                }
            }
        }

        return result.copy(
            notes = result.notes +
                    "自动合成失败（${lastError ?: "所有候选地址都不可用"}）。" +
                    "可以试「只存音频」，或换条线路再试。"
        )
    }

    /**
     * 挑一个本机能取到的地址（HTTP 200/206）。
     * 最多试 4 个候选（直连地址常被 403，代理地址通常能过），
     * 挑不到就返回 null，由调用方决定是否还能靠本机合成拿到文件。
     */
    private suspend fun pickPlayable(context: Context, result: ExtractResult): ExtractResult? {
        val playable = result.items.filter { it.kind != MediaKind.IMAGE }
        if (playable.isEmpty()) return result

        val ordered = playable.sortedBy { if (it.kind == MediaKind.AUDIO) 1 else 0 }
        var chosen: VideoItem? = null
        ordered.take(4).forEach { item ->
            if (chosen != null) return@forEach
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val headers = item.headers + mapOf("User-Agent" to Http.UA)
                    val probe = Http.probeMedia(item.url, headers, timeoutMs = 4_000)
                    probe.code in 200..299 || probe.code == 206
                }.getOrDefault(false)
            }
            if (ok) chosen = item
        }

        val picked = chosen ?: return null

        // 给取得到的条目补上真实分辨率 / 帧率
        var filled = 0
        val enriched = (listOf(picked) + result.items.filterNot { it.url == picked.url }).map { item ->
            if (item.kind != MediaKind.IMAGE && item.width == 0 && item.height == 0 && filled < 3) {
                filled++
                val info = withContext(Dispatchers.IO) {
                    runCatching { MediaProbe.fill(item.url, item.headers) }
                        .getOrDefault(MediaProbe.Info(0, 0, 0, 0))
                }
                if (info.width > 0 || info.height > 0 || info.fps > 0) {
                    item.copy(
                        width = if (info.width > 0) info.width else item.width,
                        height = if (info.height > 0) info.height else item.height,
                        fps = if (info.fps > 0) info.fps else item.fps
                    )
                } else {
                    item
                }
            } else {
                item
            }
        }
        return result.copy(items = enriched)
    }

    /** 提示里的超长链接截断，避免刷屏 */
    private fun clean(text: String): String =
        Regex("""https?://\S{56,}""").replace(text) { it.value.take(48) + "…" }

    /** 用户手动取消解析 */
    fun cancel() {
        job?.cancel()
        YtDlpEngine.cancelCurrent()
        WebExtractor.cancel()
        job = null
        state = ExtractUiState.Idle
    }
}
