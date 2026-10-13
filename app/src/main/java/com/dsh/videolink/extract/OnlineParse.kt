package com.dsh.videolink.extract

import android.content.Context
import org.json.JSONObject

/**
 * 在线解析接口（第三方服务器代抓）。
 *
 * 目前只保留短视频的两条接口：抖音 / TikTok 免水印。
 * 说明：这些接口由第三方提供，会限流、也可能改版，所以两条互为备份。
 */
object OnlineParse {

    fun tiktok(context: Context, url: String, notes: MutableList<String>): ExtractResult? {
        fromTikwm(url)?.let { return it }
        fromDouyinWtf(url)?.let { return it }
        notes += "在线接口这次没通（第三方接口经常限流或改版），稍后再试。"
        return null
    }

    /** tikwm.com：老牌免水印接口，抖音 / TikTok 都支持 */
    private fun fromTikwm(url: String): ExtractResult? {
        val api = "https://tikwm.com/api/?url=" + java.net.URLEncoder.encode(url, "UTF-8")
        val response = runCatching {
            Http.get(
                api,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "https://tikwm.com/"
                ),
                maxBytes = 800_000,
                timeoutMs = 15_000,
                ua = Http.UA
            )
        }.getOrNull() ?: return null
        if (response.code !in 200..299) return null
        val root = runCatching { JSONObject(response.text) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null

        val items = mutableListOf<VideoItem>()
        val headers = mapOf("Referer" to "https://www.tiktok.com/", "User-Agent" to Http.UA)

        data.optString("hdplay").takeIf { it.startsWith("http") }?.let { hd ->
            items += VideoItem(
                url = hd,
                label = "高清 · MP4（含音轨，无水印）",
                kind = MediaKind.MP4,
                headers = headers,
                sizeBytes = data.optLong("hd_size", 0L),
                muxed = true
            )
        }
        data.optString("play").takeIf { it.startsWith("http") }?.let { play ->
            items += VideoItem(
                url = play,
                label = "标清 · MP4（含音轨，无水印）",
                kind = MediaKind.MP4,
                headers = headers,
                sizeBytes = data.optLong("size", 0L),
                muxed = true
            )
        }
        data.optString("music").takeIf { it.startsWith("http") }?.let { music ->
            items += VideoItem(
                url = music,
                label = "音频 · 背景音乐",
                kind = MediaKind.AUDIO,
                headers = headers
            )
        }
        if (items.isEmpty()) return null

        return ExtractResult(
            inputUrl = url,
            finalUrl = url,
            title = data.optString("title").ifBlank { null },
            poster = data.optString("cover").ifBlank { null },
            items = items,
            uploader = data.optJSONObject("author")?.optString("unique_id")?.ifBlank { null },
            durationSeconds = data.optInt("duration", 0),
            engine = "在线接口",
            notes = mutableListOf("已通过在线接口解析成功。")
        )
    }

    /** douyin.wtf（TikTokDownloader 的公共实例），作为备用 */
    private fun fromDouyinWtf(url: String): ExtractResult? {
        val api = "https://api.douyin.wtf/api/hybrid/video_data?minimal=true&url=" +
                java.net.URLEncoder.encode(url, "UTF-8")
        val response = runCatching {
            Http.get(api, maxBytes = 800_000, timeoutMs = 15_000, ua = Http.UA)
        }.getOrNull() ?: return null
        if (response.code !in 200..299) return null
        val root = runCatching { JSONObject(response.text) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null

        val headers = mapOf("Referer" to "https://www.tiktok.com/", "User-Agent" to Http.UA)
        val items = mutableListOf<VideoItem>()

        data.optJSONObject("video")
            ?.optJSONObject("play_addr")
            ?.optJSONArray("url_list")
            ?.let { list ->
                val media = if (list.length() > 0) list.optString(0) else ""
                if (media.startsWith("http")) {
                    items += VideoItem(
                        url = media,
                        label = "MP4（含音轨，无水印）",
                        kind = MediaKind.MP4,
                        headers = headers,
                        muxed = true
                    )
                }
            }
        data.optJSONObject("music")
            ?.optJSONObject("play_url")
            ?.optJSONArray("url_list")
            ?.let { list ->
                val media = if (list.length() > 0) list.optString(0) else ""
                if (media.startsWith("http")) {
                    items += VideoItem(
                        url = media,
                        label = "音频 · 背景音乐",
                        kind = MediaKind.AUDIO,
                        headers = headers
                    )
                }
            }
        if (items.isEmpty()) return null

        return ExtractResult(
            inputUrl = url,
            finalUrl = url,
            title = data.optString("desc").ifBlank { null },
            poster = data.optJSONObject("cover")
                ?.optJSONArray("url_list")
                ?.let { if (it.length() > 0) it.optString(0) else null },
            items = items,
            uploader = data.optJSONObject("author")?.optString("unique_id")?.ifBlank { null },
            durationSeconds = 0,
            engine = "在线接口",
            notes = mutableListOf("已通过在线接口解析成功。")
        )
    }
}
