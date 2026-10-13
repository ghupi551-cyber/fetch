package com.dsh.videolink.extract

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用解析引擎（youtubedl-android）。
 *
 * 内核封装了覆盖 1800+ 站点的解析器，这里直接调用它。
 *
 * 首次调用会把 Python 运行时从 nativeLibraryDir 解压到应用私有目录，需要几秒。
 */
object YtDlpEngine {

    private const val PROCESS_ID = "videolink"

    /** 单条线路的解析硬超时（毫秒）——太短会误杀，太长会让用户以为卡死 */
    private const val ENGINE_TIMEOUT_MS = 30_000L

    /** 取消当前解析（杀掉落着的引擎进程） */
    fun cancelCurrent() {
        runCatching { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) }
    }

    sealed interface Status {
        data object NotStarted : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    @Volatile
    private var status: Status = Status.NotStarted

    @Volatile
    private var cachedVersion: String? = null

    private val initLock = Any()

    fun currentStatus(): Status = status

    fun versionName(): String? = cachedVersion

    /** 初始化（幂等、线程安全）。返回是否可用。 */
    fun ensureReady(context: Context): Boolean {
        if (status is Status.Ready) return true
        synchronized(initLock) {
            if (status is Status.Ready) return true
            return try {
                val app = context.applicationContext
                YoutubeDL.getInstance().init(app)
                // ffmpeg 用于音视频合并与音频抽取；失败不影响解析本身
                runCatching { com.yausername.ffmpeg.FFmpeg.getInstance().init(app) }
                cachedVersion = runCatching {
                    YoutubeDL.getInstance().versionName(app)?.trim()?.takeIf { it.isNotEmpty() }
                }.getOrNull()
                status = Status.Ready
                true
            } catch (t: Throwable) {
                status = Status.Failed(t.message ?: t.javaClass.simpleName)
                false
            }
        }
    }

    /**
     * 用通用引擎解析链接。返回 null 表示引擎不可用或没有解析出可用格式，
     * 调用方应回退到内置解析器。
     */
    suspend fun extract(
        context: Context,
        url: String,
        onProgress: ((String) -> Unit)? = null,
        onAttempt: ((index: Int, total: Int) -> Unit)? = null
    ): ExtractResult? {
        val app = context.applicationContext
        if (!ensureReady(app)) return null

        // 同一个链接按「多条线路」依次尝试：主线路失败就换一个客户端/接口再试，
        // 这样个别线路被限制时还有机会成功。
        var lastError: ExtractException? = null
        val attempts = attemptsFor(url)
        attempts.forEachIndexed { index, attempt ->
            onAttempt?.invoke(index, attempts.size)
            try {
                val result = runExtract(app, url, attempt, onProgress)
                if (result != null && result.items.isNotEmpty()) {
                    return if (index == 0) result else result.copy(
                        notes = result.notes + "主线路没成功，已自动换用${attempt.label}解析。"
                    )
                }
            } catch (t: ExtractException) {
                lastError = t
                // 机器人校验这类问题换客户端也一样被拦，直接交给别的线路，别让用户干等
                val text = (t.message.orEmpty() + " " + t.detail.orEmpty())
                if (text.contains("not a bot", true) ||
                    text.contains("Sign in to confirm", true) ||
                    text.contains("cookies", true)
                ) {
                    return@forEachIndexed
                }
            } catch (t: Throwable) {
                lastError = ExtractException(t.message ?: t.javaClass.simpleName)
            }
        }
        lastError?.let { throw it }
        return null
    }

    private data class Attempt(val label: String, val extraOptions: List<Pair<String, String>>)

    private fun attemptsFor(url: String): List<Attempt> {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        return when {
            host.contains("tiktok") -> listOf(
                Attempt("主线路", emptyList()),
                Attempt(
                    "备用线路 A",
                    listOf("--extractor-args" to "tiktok:api_hostname=api22-normal-c-useast2a.tiktokv.com")
                ),
                Attempt(
                    "备用线路 B",
                    listOf("--extractor-args" to "tiktok:api_hostname=api16-normal-c-useast1a.tiktokv.com")
                )
            )

            else -> listOf(Attempt("主线路", emptyList()))
        }
    }

    private suspend fun runExtract(
        context: Context,
        url: String,
        attempt: Attempt,
        onProgress: ((String) -> Unit)?
    ): ExtractResult? {
        val app = context
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")                 // 输出单个 JSON
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
            addOption("--retries", "2")
            addOption("--no-mtime")
            attempt.extraOptions.forEach { (key, value) -> addOption(key, value) }
            // 部分站点需要新鲜 Cookie，写成 Netscape 格式交给引擎
            writeCookieFile(app)?.let { addOption("--cookies", it.absolutePath) }
        }

        // 引擎偶尔会在某些站点上卡住不返回，这里加硬超时：
        // 超时就杀掉 Python 进程，避免界面一直停在「正在解析」。
        val response = try {
            coroutineScope {
                val deferred = async(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, PROCESS_ID) { _, _, line ->
                        if (!line.isNullOrBlank()) onProgress?.invoke(line)
                    }
                }
                val result = withTimeoutOrNull(ENGINE_TIMEOUT_MS) { deferred.await() }
                if (result == null) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) }
                    deferred.cancel()
                }
                result
            } ?: throw ExtractException(
                "解析超时（超过 ${ENGINE_TIMEOUT_MS / 1000} 秒），已经中止。稍后重试或换一条链接试试"
            )
        } catch (t: ExtractException) {
            throw t
        } catch (t: Throwable) {
            val detail = t.message?.trim().orEmpty()
            throw ExtractException(
                if (detail.contains("412")) "站点风控拦截（HTTP 412），稍后再试"
                else "解析失败：${detail.ifBlank { t.javaClass.simpleName }}"
            )
        }

        if (response.exitCode != 0) {
            throw ExtractException(
                prettyError(response.err, response.exitCode),
                detail = response.err.takeLast(1500).trim()
            )
        }

        val json = runCatching { JSONObject(response.out) }.getOrNull()
            ?: throw ExtractException("解析结果无法读取")

        return parse(json)
    }

    /**
     * 只保存音频：让引擎下载并抽出音轨（依赖内置 ffmpeg）。
     * 返回抽好的音频文件（在应用缓存目录里），调用方负责搬到下载目录。
     */
    fun downloadAudio(
        context: Context,
        url: String,
        onProgress: (Float, String) -> Unit
    ): java.io.File {
        val app = context.applicationContext
        if (!ensureReady(app)) throw ExtractException("解析引擎不可用")
        val outDir = java.io.File(app.cacheDir, "audio").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }

        val template = java.io.File(outDir, "audio_%(title).40s.%(ext)s").absolutePath
        val request = YoutubeDLRequest(url).apply {
            addOption("-x")                      // 抽音轨
            addOption("--audio-format", "m4a")
            addOption("--audio-quality", "0")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--newline")
            addOption("--socket-timeout", "20")
            addOption("-o", template)
            writeCookieFile(app)?.let { addOption("--cookies", it.absolutePath) }
        }

        val response = YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
            val fraction = if (progress > 1f) progress / 100f else progress
            onProgress(fraction.coerceIn(0f, 1f), line ?: "")
        }
        if (response.exitCode != 0) {
            throw ExtractException(
                prettyError(response.err, response.exitCode),
                detail = response.err.takeLast(1500).trim()
            )
        }
        val produced = outDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
        return produced ?: throw ExtractException("没有生成音频文件")
    }

    /**
     * 更新解析引擎到最新版（站点改版导致某个站点失效时，点一下就能修）。
     */
    suspend fun updateEngine(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!ensureReady(app)) return@withContext "引擎初始化失败，无法更新"
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            when (status) {
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "已经是最新版"
                YoutubeDL.UpdateStatus.DONE -> "解析引擎已更新完成"
                else -> "更新结束：$status"
            }
        } catch (t: Throwable) {
            "更新失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 把用户粘贴的 Cookie 串写成引擎可读的 Netscape cookies.txt */
    private fun writeCookieFile(context: Context): java.io.File? {
        val raw = Http.userCookie?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val dir = java.io.File(context.cacheDir, "ytdlp").apply { mkdirs() }
            val file = java.io.File(dir, "cookies.txt")
            val expiry = System.currentTimeMillis() / 1000L + 365L * 24 * 3600
            val builder = StringBuilder("# Netscape HTTP Cookie File\n")
            raw.split(';').forEach { part ->
                val pair = part.trim()
                if (!pair.contains('=')) return@forEach
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    builder.append(".douyin.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
                }
            }
            file.writeText(builder.toString())
            file
        } catch (_: Throwable) {
            null
        }
    }

    private fun prettyError(stderr: String, exitCode: Int): String {
        val line = stderr.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ERROR:") }
            ?.removePrefix("ERROR:")
            ?.trim()
        val message = line?.takeIf { it.isNotEmpty() }
            ?: "解析进程退出码 $exitCode"
        return when {
            message.contains("Sign in to confirm", true) ->
                "该站点要求登录验证（$message），换直链或用支持登录的下载工具试试"
            message.contains("412") -> "站点风控拦截（HTTP 412），稍后再试"
            message.contains("Unsupported URL", true) -> "暂不支持这个链接：$message"
            else -> message
        }
    }

    // ------------------------------------------------------------- 结果解析

    private fun parse(json: JSONObject): ExtractResult {
        // 少数情况会直接返回 playlist；只取第一条 entry
        json.optJSONArray("entries")?.let { entries ->
            if (entries.length() > 0) {
                val first = entries.optJSONObject(0)
                if (first != null) return parse(first)
            }
        }

        val title = json.optString("title").ifBlank { null }
        val uploader = json.optString("uploader")
            .ifBlank { json.optString("channel") }
            .ifBlank { json.optString("uploader_id") }
            .ifBlank { null }
        val duration = json.optInt("duration", 0)
        val poster = json.optString("thumbnail").ifBlank { null }
        val extractor = json.optString("extractor_key").ifBlank { "通用引擎" }
        val pageUrl = json.optString("webpage_url").ifBlank { json.optString("original_url") }
        val globalHeaders = json.optJSONObject("http_headers").toStringMap()

        val notes = mutableListOf<String>()
        if (json.optBoolean("is_live", false)) {
            notes += "这是一个直播流，播放器可能无法拖动进度。"
        }

        val rawFormats = json.optJSONArray("formats") ?: JSONArray()
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until rawFormats.length()) {
            val format = rawFormats.optJSONObject(i) ?: continue
            toCandidate(format, globalHeaders)?.let { candidates += it }
        }

        // 有些站点只给顶层 url
        if (candidates.isEmpty()) {
            val single = toCandidate(json, globalHeaders)
            if (single != null) candidates += single
        }

        val items = buildItems(candidates, pageUrl, notes)

        if (items.isEmpty()) {
            notes += "通用引擎没有返回可用的媒体格式。"
        }

        return ExtractResult(
            inputUrl = pageUrl,
            finalUrl = pageUrl,
            title = title,
            poster = poster,
            items = items,
            uploader = uploader,
            durationSeconds = duration,
            engine = "通用引擎 · $extractor",
            notes = notes
        )
    }

    private data class Candidate(
        val url: String,
        val extension: String,
        val kind: MediaKind,
        val width: Int,
        val height: Int,
        val fps: Int,
        val audioBitrate: Int,
        val sizeBytes: Long,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val headers: Map<String, String>,
        val formatNote: String
    ) {
        val muxed: Boolean get() = hasVideo && hasAudio
        val videoOnly: Boolean get() = hasVideo && !hasAudio
        val audioOnly: Boolean get() = !hasVideo && hasAudio
    }

    private fun toCandidate(format: JSONObject, fallbackHeaders: Map<String, String>): Candidate? {
        val rawUrl = format.optString("url").ifBlank { format.optString("manifest_url") }
        if (rawUrl.isBlank() || !rawUrl.startsWith("http")) return null

        val vcodec = format.optString("vcodec", "none")
        val acodec = format.optString("acodec", "none")
        val hasVideo = vcodec.isNotEmpty() && vcodec != "none"
        val hasAudio = acodec.isNotEmpty() && acodec != "none"
        if (!hasVideo && !hasAudio) return null

        val note = format.optString("format_note")
        if (note.equals("storyboard", true)) return null
        if (note.contains("storyboard", true)) return null

        val extension = format.optString("ext").ifBlank { "mp4" }.lowercase()
        val protocol = format.optString("protocol").lowercase()
        val isManifest = format.optString("manifest_url").isNotBlank()
        val kind = when {
            extension == "m3u8" || protocol.contains("m3u8") -> MediaKind.HLS
            extension == "mpd" || protocol.contains("dash") || (isManifest && protocol.contains("dash")) -> MediaKind.DASH
            !hasVideo && (extension in setOf("m4a", "mp3", "aac", "opus", "ogg")) -> MediaKind.AUDIO
            extension == "webm" -> MediaKind.WEBM
            extension == "mp4" || extension == "m4v" -> MediaKind.MP4
            else -> if (hasVideo) MediaKind.OTHER else MediaKind.AUDIO
        }

        val size = format.optLong("filesize", 0).takeIf { it > 0 }
            ?: format.optLong("filesize_approx", 0).takeIf { it > 0 }
            ?: 0L

        val headers = (format.optJSONObject("http_headers").toStringMap() + fallbackHeaders)
            .filterKeys { !it.equals("Accept-Encoding", true) }

        return Candidate(
            url = rawUrl,
            extension = extension,
            kind = kind,
            width = format.optInt("width", 0),
            height = format.optInt("height", 0),
            fps = format.optInt("fps", 0),
            audioBitrate = format.optInt("abr", 0),
            sizeBytes = size,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            headers = headers,
            formatNote = note
        )
    }

    private fun buildItems(
        candidates: List<Candidate>,
        pageUrl: String,
        notes: MutableList<String>
    ): List<VideoItem> {
        if (candidates.isEmpty()) return emptyList()

        val muxed = candidates.filter { it.muxed }
            .sortedWith(compareByDescending<Candidate> { it.height }.thenByDescending { it.sizeBytes })
            .distinctBy { "${it.height}_${it.extension}_${it.kind}" }

        val videoOnly = candidates.filter { it.videoOnly }
            .sortedWith(compareByDescending<Candidate> { it.height }.thenByDescending { it.sizeBytes })
            .distinctBy { "${it.height}_${it.extension}" }

        val audioOnly = candidates.filter { it.audioOnly }
            .sortedWith(compareByDescending<Candidate> { it.audioBitrate }.thenByDescending { it.sizeBytes })
            .distinctBy { "${it.audioBitrate}_${it.extension}" }

        val combined = buildList {
            addAll(muxed.take(12))
            addAll(videoOnly.take(6))
            addAll(audioOnly.take(3))
        }

        if (muxed.isEmpty() && videoOnly.isNotEmpty()) {
            notes += "该站点只提供「视频+音频」分离的流；没有内置 ffmpeg 合并，" +
                    "所以高画质会显示为无音轨，可分别下载后用剪辑软件合并。"
        }

        return combined.map { candidate ->
            VideoItem(
                url = candidate.url,
                label = labelFor(candidate),
                kind = candidate.kind,
                headers = candidate.headers + ("Referer" to refererOf(pageUrl, candidate.headers)),
                sizeBytes = candidate.sizeBytes,
                muxed = candidate.muxed,
                width = candidate.width,
                height = candidate.height,
                fps = candidate.fps
            )
        }
    }

    private fun labelFor(candidate: Candidate): String {
        val size = formatSize(candidate.sizeBytes)
        return when {
            candidate.muxed -> buildString {
                append("${candidate.extension.uppercase()}（含音轨）")
                if (size.isNotEmpty()) append(" · $size")
            }

            candidate.videoOnly -> buildString {
                append("${candidate.extension.uppercase()} · 无音轨")
                if (size.isNotEmpty()) append(" · $size")
            }

            else -> buildString {
                append("音频 · ${candidate.extension.uppercase()}")
                if (candidate.audioBitrate > 0) append(" · ${candidate.audioBitrate}kbps")
                if (size.isNotEmpty()) append(" · $size")
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%d KB", bytes / 1024)
    }

    private fun refererOf(pageUrl: String, headers: Map<String, String>): String {
        headers["Referer"]?.let { return it }
        return runCatching {
            val uri = java.net.URI(pageUrl)
            val authority = uri.authority
            if (authority.isNullOrBlank()) pageUrl else "${uri.scheme}://$authority/"
        }.getOrDefault(pageUrl)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        keys().forEach { key ->
            val value = optString(key)
            if (value.isNotBlank()) map[key] = value
        }
        return map
    }
}
