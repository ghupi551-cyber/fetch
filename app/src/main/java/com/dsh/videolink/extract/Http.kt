package com.dsh.videolink.extract

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * 极简 HTTP 客户端：只依赖 JDK，避免额外依赖。
 * 额外维护一份「会话 Cookie」（部分站点如抖音需要先访问主页拿 Cookie 才会返回数据），
 * 以及用户可选粘贴的 Cookie。
 */
object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * 中性 UA：部分站点（例如 B 站）的风控只对「浏览器型 UA」返回挑战页，
     * 用应用自己的 UA 反而能正常拿到接口数据。
     */
    const val API_UA = "VideoLinkGlass/1.0 (Android)"

    const val IPHONE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 用户在设置里粘贴的 Cookie（当前主要用于抖音） */
    @Volatile
    var userCookie: String? = null
        set(value) {
            field = value?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** 会话期间自动收集到的 Cookie（域名 -> Cookie 键值对） */
    private val sessionCookies = linkedMapOf<String, String>()

    private val lock = Any()

    /** 清空自动收集的 Cookie（例如换账号或解析失败后重试） */
    @Volatile
    private var douyinWarmedUp = false

    /**
     * 抖音分享页只有带上「访问过主页」拿到的 Cookie 才会返回完整数据，
     * 每个进程只需要预热一次。
     */
    fun warmUpDouyin() {
        if (douyinWarmedUp) return
        synchronized(lock) {
            if (douyinWarmedUp) return
            runCatching {
                request(
                    url = "https://www.douyin.com/",
                    headers = emptyMap(),
                    maxBytes = 0,
                    timeoutMs = 12_000,
                    readBody = false,
                    ua = DESKTOP_UA
                )
            }
            douyinWarmedUp = true
        }
    }

    data class Response(
        val code: Int,
        val finalUrl: String,
        val contentType: String?,
        val text: String
    )

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 1_500_000,
        timeoutMs: Int = 15_000,
        ua: String = UA
    ): Response = request(url, headers, maxBytes, timeoutMs, readBody = true, ua = ua)

    /** 只取响应头和最终地址（跟随跳转），适合判断直链类型。 */
    fun probe(url: String, timeoutMs: Int = 12_000, ua: String = UA): Response =
        request(url, emptyMap(), 0, timeoutMs, readBody = false, ua = ua)

    /**
     * 带 Referer 等请求头的媒体探测：用来确认「这台手机到底能不能取到这个地址」。
     *
     * 注意：这里刻意**不带 Range 头**（readBody=true, 只读 1 字节）。
     * 有些 CDN（例如抖音）对 Range 请求会直接 403，用 Range 探测会出现
     * 「明明能播却报 403」的假失败。
     */
    fun probeMedia(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 6_000
    ): Response = request(
        url,
        headers,
        maxBytes = 1,
        timeoutMs = timeoutMs,
        readBody = true,
        ua = headers["User-Agent"] ?: UA
    )

    /** 跟随短链接跳转，返回最终地址。 */
    fun resolve(url: String, timeoutMs: Int = 12_000, ua: String = UA): String =
        request(url, emptyMap(), 0, timeoutMs, readBody = false, ua = ua).finalUrl

    private fun cookieFor(url: String): String? {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val parts = mutableListOf<String>()
        userCookie?.let { parts += it.trim().trimEnd(';') }
        if (host.contains("douyin") || host.contains("iesdouyin") || host.contains("amemv")) {
            synchronized(lock) {
                if (sessionCookies.isNotEmpty()) {
                    parts += sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                }
            }
        }
        val joined = parts.filter { it.isNotBlank() }.joinToString("; ")
        return joined.ifBlank { null }
    }

    private fun absorbCookies(url: String, setCookies: List<String>) {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (!(host.contains("douyin") || host.contains("iesdouyin") || host.contains("amemv"))) return
        synchronized(lock) {
            setCookies.forEach { raw ->
                val pair = raw.substringBefore(';').trim()
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                if (name.isNotBlank() && value.isNotBlank()) sessionCookies[name] = value
            }
        }
    }

    private fun request(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        timeoutMs: Int,
        readBody: Boolean,
        ua: String
    ): Response = try {
        requestInner(url, headers, maxBytes, timeoutMs, readBody, ua)
    } catch (t: ExtractException) {
        throw t
    } catch (t: Throwable) {
        // 国内网络下 DNS 失败 / 连接被重置都属于这一类，
        // 统一转成可读异常，保证上层只需处理这一种。
        throw ExtractException("连接失败：${t.message ?: t.javaClass.simpleName}")
    }

    private fun requestInner(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        timeoutMs: Int,
        readBody: Boolean,
        ua: String
    ): Response {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", ua)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,video/*;q=0.8,*/*;q=0.7"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                if (!readBody) setRequestProperty("Range", "bytes=0-1")
                cookieFor(current)?.let { setRequestProperty("Cookie", it) }
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val code = try {
                connection.responseCode
            } catch (t: Throwable) {
                connection.disconnect()
                throw ExtractException("连接失败：${t.message ?: t.javaClass.simpleName}")
            }

            connection.headerFields["Set-Cookie"]?.let { absorbCookies(current, it) }

            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                val nextUrl = connection.url?.toString() ?: current
                connection.disconnect()
                if (location.isNullOrBlank()) return Response(code, nextUrl, null, "")
                current = absolutize(nextUrl, location)
                if (++redirects > 8) return Response(code, current, null, "")
                continue
            }

            val contentType = connection.contentType
            val finalUrl = connection.url?.toString() ?: current
            val text = if (readBody && code in 200..299 && maxBytes > 0) {
                val encoding = connection.contentEncoding
                try {
                    connection.inputStream.use { stream -> readText(stream, encoding, maxBytes) }
                } catch (_: Throwable) {
                    ""
                }
            } else {
                ""
            }
            connection.disconnect()
            return Response(code, finalUrl, contentType, text)
        }
    }

    private fun readRaw(stream: InputStream, encoding: String?, maxBytes: Int): ByteArray {
        val decoded: InputStream = when {
            encoding == null -> stream
            encoding.contains("gzip", true) -> GZIPInputStream(stream)
            encoding.contains("deflate", true) -> InflaterInputStream(stream)
            else -> stream
        }
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(32 * 1024)
        var total = 0
        while (total < maxBytes) {
            val read = decoded.read(chunk)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    private fun readText(stream: InputStream, encoding: String?, maxBytes: Int): String =
        decode(readRaw(stream, encoding, maxBytes))

    /** 拉二进制内容（图片、封面等），同样会带上用户/会话 Cookie。 */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 16_000_000,
        timeoutMs: Int = 20_000,
        ua: String = UA
    ): ByteArray? {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                cookieFor(current)?.let { setRequestProperty("Cookie", it) }
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val code = try {
                connection.responseCode
            } catch (_: Throwable) {
                connection.disconnect()
                return null
            }
            connection.headerFields["Set-Cookie"]?.let { absorbCookies(current, it) }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                val nextUrl = connection.url?.toString() ?: current
                connection.disconnect()
                if (location.isNullOrBlank() || ++redirects > 8) return null
                current = absolutize(nextUrl, location)
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                return null
            }
            val bytes = try {
                connection.inputStream.use { readRaw(it, connection.contentEncoding, maxBytes) }
            } catch (_: Throwable) {
                null
            }
            connection.disconnect()
            return bytes
        }
    }

    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val charset = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.lowercase()
        val name = when (charset) {
            "gb2312", "gbk", "gb18030" -> "GBK"
            "big5" -> "Big5"
            null, "utf-8", "utf8" -> "UTF-8"
            else -> charset
        }
        return try {
            String(bytes, charset(name))
        } catch (_: Throwable) {
            String(bytes, Charsets.UTF_8)
        }
    }

    fun absolutize(base: String, target: String): String {
        val trimmed = target.trim().replace("&amp;", "&")
        return try {
            when {
                trimmed.startsWith("//") -> URI(base).scheme + ":" + trimmed
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> URI(base).resolve(trimmed).toString()
            }
        } catch (_: Throwable) {
            trimmed
        }
    }
}

class ExtractException(
    message: String,
    /** 引擎原始错误信息，界面上可以一键复制反馈 */
    val detail: String? = null,
    /** 是不是「本机 IP 被站点拦了」（用于智能选路：下次直接走在线） */
    val ipBlocked: Boolean = false
) : Exception(message)
