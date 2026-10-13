package com.dsh.videolink.extract

import android.media.MediaMetadataRetriever

/**
 * 用系统自带的媒体解析器读出**真实**的分辨率 / 帧率 / 时长。
 *
 * 有些线路（例如 TikTok 的免水印接口）只返回地址，不给画质信息，
 * 界面上就什么都没有。这里直接问系统："这个文件到底是什么规格的？"
 */
object MediaProbe {

    data class Info(val width: Int, val height: Int, val fps: Int, val durationSeconds: Int)

    fun fill(url: String, headers: Map<String, String>): Info {
        val retriever = MediaMetadataRetriever()
        return try {
            if (headers.isEmpty()) {
                retriever.setDataSource(url)
            } else {
                retriever.setDataSource(url, headers)
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.toInt()
                ?: run {
                    val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toLongOrNull() ?: 0L
                    if (frames > 0L && durationMs > 0L) {
                        ((frames * 1000.0) / durationMs).toInt()
                    } else {
                        0
                    }
                }

            Info(width, height, fps, (durationMs / 1000L).toInt())
        } catch (t: Throwable) {
            Info(0, 0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
