package com.dsh.videolink.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import com.dsh.videolink.extract.MediaKind
import com.dsh.videolink.extract.VideoItem
import com.dsh.videolink.extract.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/** DIRECT = 直接下载地址；AUDIO = 抽音轨；MERGE = 视频流+音频流合并成完整视频 */
enum class DownloadMode { DIRECT, AUDIO, MERGE }

data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val title: String,
    val kind: MediaKind,
    val headers: Map<String, String>,
    val mode: DownloadMode = DownloadMode.DIRECT,
    val videoUrl: String = "",
    val videoHeaders: Map<String, String> = emptyMap(),
    val audioUrl: String = "",
    val audioHeaders: Map<String, String> = emptyMap(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0L,
    val total: Long = -1L,
    val speed: Long = 0L,
    val error: String? = null,
    val uri: Uri? = null,
    val filePath: String? = null
) {
    val progress: Float
        get() = when {
            total > 0L -> (bytes.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }

    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
}

/**
 * 应用内下载器：自己带请求头 + 手动跟随跳转（很多视频 CDN 需要 Referer/UA，
 * 系统 DownloadManager 拿不到这些，之前就是这个原因下不动）。
 */
object DownloadStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = mutableStateListOf<DownloadTask>()
    private val jobs = mutableMapOf<String, Job>()

    val tasks: List<DownloadTask> get() = _tasks

    val activeCount: Int get() = _tasks.count { it.isActive }

    fun enqueue(context: Context, item: VideoItem, title: String?, customName: String? = null) {
        val app = context.applicationContext
        val fileName = customName ?: buildFileName(item, title)
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = item.url,
            fileName = fileName,
            title = title?.takeIf { it.isNotBlank() } ?: fileName,
            kind = item.kind,
            headers = item.headers
        )
        _tasks.add(0, task)
        jobs[task.id] = scope.launch { runDownload(app, task.id) }
    }

    /** 合并下载：把「无音轨视频 + 音频」在本机用 ffmpeg 合成完整视频 */
    fun enqueueMerge(
        context: Context,
        title: String?,
        video: VideoItem,
        audio: VideoItem
    ) {
        val app = context.applicationContext
        val base = title?.takeIf { it.isNotBlank() }
            ?.replace(Regex("""[\\/:*?"<>|\r\n\t#]"""), "_")
            ?.trim()
            ?.take(40)
            ?: "视频"
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = video.url,
            fileName = base + "_" + (System.currentTimeMillis() % 100000) + ".mp4",
            title = "$base（合并成完整视频）",
            kind = MediaKind.MP4,
            headers = video.headers,
            mode = DownloadMode.MERGE,
            videoUrl = video.url,
            videoHeaders = video.headers,
            audioUrl = audio.url,
            audioHeaders = audio.headers
        )
        _tasks.add(0, task)
        jobs[task.id] = scope.launch { runMergeDownload(app, task.id) }
    }

    /** 只保存音频：把原始链接交给引擎抽音轨 */
    fun enqueueAudio(context: Context, sourceUrl: String, title: String?) {
        val app = context.applicationContext
        val fileName = buildString {
            append(
                title?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("""[\\/:*?"<>|\r\n\t#]"""), "_")
                    ?.trim()
                    ?.take(40)
                    ?: "audio"
            )
            append("_")
            append(System.currentTimeMillis() % 100000)
            append(".m4a")
        }
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = sourceUrl,
            fileName = fileName,
            title = (title?.takeIf { it.isNotBlank() } ?: "音频") + "（音频）",
            kind = MediaKind.AUDIO,
            headers = emptyMap(),
            mode = DownloadMode.AUDIO
        )
        _tasks.add(0, task)
        jobs[task.id] = scope.launch { runAudioDownload(app, task.id) }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { it.copy(status = DownloadStatus.CANCELED, speed = 0L) }
    }

    fun retry(context: Context, id: String) {
        val app = context.applicationContext
        val task = _tasks.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(status = DownloadStatus.QUEUED, bytes = 0L, total = -1L, error = null, speed = 0L) }
        jobs[id] = scope.launch { runDownload(app, id) }
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        _tasks.removeAll { it.id == id }
    }

    fun clearFinished() {
        _tasks.removeAll { !it.isActive }
    }

    fun openTask(context: Context, id: String) {
        val task = _tasks.firstOrNull { it.id == id } ?: return
        val uri = task.uri ?: task.filePath?.let { Uri.fromFile(File(it)) } ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(task.kind))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** 把「无音轨视频 + 音频」合成完整视频：两个都下到缓存，再用内置 ffmpeg 合并 */
    /** 本地文件（例如已合成的视频）直接复制到下载目录 */
    private fun copyLocalFile(context: Context, path: String, fileName: String): Uri? {
        val file = File(path)
        if (!file.exists()) throw IllegalStateException("本地文件不存在")
        return saveToDownloads(context, file, fileName, "video/mp4")
    }

    private suspend fun runMergeDownload(context: Context, id: String) {
        val task = _tasks.firstOrNull { it.id == id } ?: return
        try {
            update(id) { it.copy(status = DownloadStatus.RUNNING, error = null) }

            val merged = com.dsh.videolink.extract.MediaMerger.merge(
                context = context,
                videoUrl = task.videoUrl,
                videoHeaders = task.videoHeaders,
                audioUrl = task.audioUrl,
                audioHeaders = task.audioHeaders
            ) { progress ->
                update(id) {
                    it.copy(bytes = (progress.fraction * 1000).toLong(), total = 1000L)
                }
            }
            val uri = saveToDownloads(context, merged, task.fileName, "video/mp4")
            runCatching { merged.delete() }
            update(id) {
                it.copy(status = DownloadStatus.DONE, uri = uri, bytes = 1000L, total = 1000L, speed = 0L)
            }
            notifyFinished(context, task.fileName, success = true)
        } catch (t: Throwable) {
            val canceled = jobs[id]?.isCancelled == true || t is kotlinx.coroutines.CancellationException
            update(id) {
                it.copy(
                    status = if (canceled) DownloadStatus.CANCELED else DownloadStatus.FAILED,
                    error = if (canceled) null else (t.message ?: t.javaClass.simpleName),
                    speed = 0L
                )
            }
            if (!canceled) notifyFinished(context, task.fileName, success = false)
        } finally {
            jobs.remove(id)
        }
    }

    /** 流式下载到指定文件（带请求头） */
    private fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (!headers.keys.any { it.equals("User-Agent", true) }) {
                setRequestProperty("User-Agent", com.dsh.videolink.extract.Http.UA)
            }
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("下载失败：HTTP $code")
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    read += n
                    onProgress(read, total)
                }
                output.flush()
            }
        }
    }

    /** 找到内置 ffmpeg 解压出来的可执行文件 */
    private fun findFfmpeg(context: Context): File? {
        val roots = listOfNotNull(context.noBackupFilesDir, context.filesDir)
        roots.forEach { root ->
            runCatching {
                root.walkTopDown().maxDepth(5)
                    .firstOrNull { it.isFile && it.name == "ffmpeg" && it.length() > 0 }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun execFfmpeg(binary: File, args: List<String>): Pair<Int, String> {
        val command = mutableListOf(binary.absolutePath)
        command += args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        val code = process.waitFor()
        return code to log
    }

    /** 复制到系统「下载」目录，返回 MediaStore 地址 */
    private fun saveToDownloads(
        context: Context,
        file: File,
        fileName: String,
        mime: String
    ): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/视频链接提取"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建文件")
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法写入文件")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                "视频链接提取"
            ).apply { mkdirs() }
            file.copyTo(File(dir, fileName), overwrite = true)
            return null
        }
    }

    private suspend fun runAudioDownload(context: Context, id: String) {
        val task = _tasks.firstOrNull { it.id == id } ?: return
        var uri: Uri? = null
        try {
            update(id) { it.copy(status = DownloadStatus.RUNNING, error = null, speed = 0L) }
            val file = YtDlpEngine.downloadAudio(context, task.url) { fraction, _ ->
                update(id) {
                    it.copy(
                        bytes = (fraction * 100).toLong(),
                        total = 100L
                    )
                }
            }
            // 把抽好的音频搬到下载目录
            uri = saveToDownloads(context, file, task.fileName, "audio/mp4")
            runCatching { file.delete() }
            update(id) {
                it.copy(status = DownloadStatus.DONE, uri = uri, bytes = 100L, total = 100L, speed = 0L)
            }
            notifyFinished(context, task.fileName, success = true)
        } catch (t: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            val canceled = jobs[id]?.isCancelled == true || t is kotlinx.coroutines.CancellationException
            update(id) {
                it.copy(
                    status = if (canceled) DownloadStatus.CANCELED else DownloadStatus.FAILED,
                    error = if (canceled) null else (t.message ?: t.javaClass.simpleName),
                    speed = 0L
                )
            }
            if (!canceled) notifyFinished(context, task.fileName, success = false)
        } finally {
            jobs.remove(id)
        }
    }

    private suspend fun runDownload(context: Context, id: String) {
        val task = _tasks.firstOrNull { it.id == id } ?: return

        // 已经在本机合成好的文件：直接搬过去，不用再下一次
        if (task.url.startsWith("file:")) {
            try {
                update(id) { it.copy(status = DownloadStatus.RUNNING, error = null, bytes = 50, total = 100) }
                val uri = copyLocalFile(context, java.net.URI(task.url).path.orEmpty(), task.fileName)
                update(id) { it.copy(status = DownloadStatus.DONE, uri = uri, bytes = 100, total = 100) }
                notifyFinished(context, task.fileName, success = true)
            } catch (t: Throwable) {
                update(id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = t.message ?: t.javaClass.simpleName
                    )
                }
                notifyFinished(context, task.fileName, success = false)
            } finally {
                jobs.remove(id)
            }
            return
        }

        var output: OutputStream? = null
        var uri: Uri? = null
        var file: File? = null
        var connection: HttpURLConnection? = null

        try {
            update(id) { it.copy(status = DownloadStatus.RUNNING, error = null, speed = 0L) }

            var currentUrl = task.url
            var redirects = 0
            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    task.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || ++redirects > 8) {
                        throw IllegalStateException("跳转次数过多")
                    }
                    currentUrl = if (location.startsWith("http")) location
                    else Uri.parse(currentUrl).buildUpon().path(location).build().toString()
                    continue
                }
                if (code !in 200..299) {
                    val message = "服务器返回 HTTP $code"
                    connection.disconnect()
                    throw IllegalStateException(message)
                }
                break
            }

            val active = connection ?: throw IllegalStateException("连接失败")
            val total = active.contentLengthLong

            // 目标位置：API 29+ 走 MediaStore（下载目录/视频链接提取），更低版本直接写公共目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(task.kind))
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/视频链接提取"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建下载文件")
                output = resolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入下载文件")
            } else {
                // Android 9 及以下写公共目录需要运行时存储权限，这里改成应用自己的外部目录，
                // 不需要任何权限，也能在文件管理器里找到。
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val dir = File(base, "视频链接提取")
                if (!dir.exists()) dir.mkdirs()
                file = File(dir, task.fileName)
                output = FileOutputStream(file)
            }

            active.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastPush = 0L
                var lastBytes = 0L
                val startedAt = System.currentTimeMillis()
                while (true) {
                    if (!scope.isActive) throw IllegalStateException("已取消")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output!!.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastPush > 250L) {
                        val elapsed = (now - startedAt).coerceAtLeast(1L)
                        val speed = downloaded * 1000L / elapsed
                        lastBytes = downloaded
                        lastPush = now
                        update(id) { it.copy(bytes = downloaded, total = total, speed = speed) }
                    }
                }
                if (lastBytes == 0L) lastBytes = downloaded
                update(id) {
                    it.copy(
                        bytes = downloaded,
                        total = if (total > 0) total else downloaded,
                        speed = 0L
                    )
                }
            }
            output.flush()
            output.close()
            output = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            }

            update(id) {
                it.copy(
                    status = DownloadStatus.DONE,
                    uri = uri,
                    filePath = file?.absolutePath,
                    speed = 0L
                )
            }
            notifyFinished(context, task.fileName, success = true)
        } catch (t: Throwable) {
            runCatching { output?.close() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            runCatching { file?.delete() }
            val canceled = jobs[id]?.isCancelled == true || t is kotlinx.coroutines.CancellationException
            update(id) {
                it.copy(
                    status = if (canceled) DownloadStatus.CANCELED else DownloadStatus.FAILED,
                    error = if (canceled) null else (t.message ?: t.javaClass.simpleName),
                    speed = 0L
                )
            }
            if (!canceled) notifyFinished(context, task.fileName, success = false)
        } finally {
            runCatching { output?.close() }
            runCatching { connection?.disconnect() }
            jobs.remove(id)
        }
    }

    /** 下载结束时轻震一下 + 提示，省得一直盯着进度条 */
    private fun notifyFinished(context: Context, fileName: String, success: Boolean) {
        runCatching {
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                ?.let { it as? android.os.Vibrator }
                ?.vibrate(android.os.VibrationEffect.createOneShot(40, 120))
        }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    if (success) "下载完成：$fileName" else "下载失败：$fileName",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        val index = _tasks.indexOfFirst { it.id == id }
        if (index >= 0) _tasks[index] = transform(_tasks[index])
    }

    private fun mimeOf(kind: MediaKind): String = when (kind) {
        MediaKind.MP4 -> "video/mp4"
        MediaKind.WEBM -> "video/webm"
        MediaKind.HLS -> "application/vnd.apple.mpegurl"
        MediaKind.DASH -> "application/dash+xml"
        MediaKind.AUDIO -> "audio/mpeg"
        MediaKind.IMAGE -> "image/jpeg"
        MediaKind.OTHER -> "video/*"
    }

    private fun buildFileName(item: VideoItem, title: String?): String {
        val base = title?.takeIf { it.isNotBlank() }
            ?.replace(Regex("""[\\/:*?"<>|\r\n\t#]"""), "_")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(40)
            ?.takeIf { it.isNotEmpty() }
            ?: "video"
        val extension = when (item.kind) {
            MediaKind.MP4 -> "mp4"
            MediaKind.WEBM -> "webm"
            MediaKind.AUDIO -> "m4a"
            MediaKind.IMAGE -> "jpg"
            MediaKind.HLS -> "m3u8"
            MediaKind.DASH -> "mpd"
            MediaKind.OTHER -> {
                val name = item.url.substringBefore('?').substringAfterLast('/')
                if (name.contains('.') && name.length <= 12) name.substringAfterLast('.') else "mp4"
            }
        }
        return "${base}_${System.currentTimeMillis() % 100000}.$extension"
    }
}
