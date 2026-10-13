package com.dsh.videolink.extract

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音视频合成：部分站点会把画面和声音分成两条流，
 * 这里在本机把它们下下来、再用内置 ffmpeg 合成一个完整 mp4。
 *
 * 关键点：只有**完整文件**（不是分片）才能合，分片地址会产出打不开的残片，
 * 所以下载前会先检查地址。
 */
object MediaMerger {

    data class Progress(val stage: String, val fraction: Float)

    /**
     * 下载视频流 + 音频流并合成。
     * @return 合成好的文件（在应用缓存目录里）
     */
    suspend fun merge(
        context: Context,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        audioUrl: String,
        audioHeaders: Map<String, String>,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "merge").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val videoFile = File(dir, "v_$stamp.mp4")
        val audioFile = File(dir, "a_$stamp.m4a")
        val outFile = File(dir, "out_$stamp.mp4")

        try {
            if (isPartialUrl(videoUrl) || isPartialUrl(audioUrl)) {
                throw ExtractException("这个地址是分片，下载下来会打不开（换一条线路再试）")
            }

            onProgress(Progress("正在下载画面…", 0.05f))
            downloadToFile(videoUrl, videoHeaders, videoFile) { read, total ->
                val fraction = if (total > 0) (read.toFloat() / total) * 0.45f else 0.2f
                onProgress(Progress("正在下载画面…", fraction.coerceIn(0f, 0.45f)))
            }

            onProgress(Progress("正在下载声音…", 0.5f))
            downloadToFile(audioUrl, audioHeaders, audioFile) { read, total ->
                val fraction = 0.5f + if (total > 0) (read.toFloat() / total) * 0.25f else 0.1f
                onProgress(Progress("正在下载声音…", fraction.coerceIn(0.5f, 0.75f)))
            }

            if (videoFile.length() <= 0L) throw ExtractException("画面没有下载成功")
            if (audioFile.length() <= 0L) throw ExtractException("声音没有下载成功")

            onProgress(Progress("正在合成…", 0.8f))

            // 方案一：内置 ffmpeg（原样搬运，最快）
            var ffmpegLog = ""
            val (ffmpeg, diagnostic) = locateFfmpeg(context)
            if (ffmpeg != null) {
                val (code, log) = runProcess(
                    ffmpeg,
                    listOf(
                        "-y",
                        "-i", videoFile.absolutePath,
                        "-i", audioFile.absolutePath,
                        "-c", "copy",
                        "-movflags", "+faststart",
                        outFile.absolutePath
                    ),
                    libDir = findLibsDir(context),
                    context = context
                )
                ffmpegLog = log.takeLast(200)
                if (code == 0 && outFile.exists() && outFile.length() > 0L) {
                    onProgress(Progress("合成完成", 1f))
                    return@withContext outFile
                }
            }

            // 方案二：纯 Java 的 Media3 合成（不依赖任何外部程序，兜底用）
            runCatching { outFile.delete() }
            onProgress(Progress("正在用系统合成器合成…", 0.85f))
            val ok = runCatching {
                mergeWithTransformer(context, videoFile, audioFile, outFile)
            }.getOrDefault(false)
            if (!ok || !outFile.exists() || outFile.length() <= 0L) {
                throw ExtractException(
                    if (ffmpeg == null) "两种合成方式都失败了（ffmpeg 不可用：$diagnostic）"
                    else "两种合成方式都失败了（ffmpeg：$ffmpegLog）"
                )
            }

            onProgress(Progress("合成完成", 1f))
            outFile
        } catch (t: Throwable) {
            runCatching { outFile.delete() }
            throw t
        } finally {
            runCatching { videoFile.delete() }
            runCatching { audioFile.delete() }
        }
    }

    /**
     * 纯 Java 的合成：用 Android 官方的 Media3 Transformer 把「画面文件 + 声音文件」
     * 合并成一个 mp4。不依赖 ffmpeg，作为兜底方案。
     */
    @androidx.media3.common.util.UnstableApi
    private suspend fun mergeWithTransformer(
        context: Context,
        videoFile: File,
        audioFile: File,
        outFile: File
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val videoItem = androidx.media3.transformer.EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(videoFile))
        ).setRemoveAudio(true).build()
        val audioItem = androidx.media3.transformer.EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(audioFile))
        ).setRemoveVideo(true).build()
        val sequence = androidx.media3.transformer.EditedMediaItemSequence.Builder(videoItem, audioItem).build()
        val composition = androidx.media3.transformer.Composition.Builder(sequence)
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .build()
        val transformer = androidx.media3.transformer.Transformer.Builder(context)
            .addListener(object : androidx.media3.transformer.Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult
                ) {
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(outFile.exists() && outFile.length() > 0L))
                    }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exception: androidx.media3.transformer.ExportException
                ) {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            })
            .build()
        runCatching { transformer.start(composition, outFile.absolutePath) }
            .onFailure { if (cont.isActive) cont.resumeWith(Result.success(false)) }
        cont.invokeOnCancellation { runCatching { transformer.cancel() } }
    }

    /** 分片地址（带 range / 只有一小段）不能用来合成 */
    fun isPartialUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("range=")) return true
        if (lower.contains("&rn=") || lower.contains("&rbuf=")) return true
        return false
    }

    /** 文件头是不是真的媒体（mp4 / webm / ts / mp3 等），防止把错误页当视频 */
    fun looksLikeMedia(file: File): Boolean {
        if (!file.exists() || file.length() < 8 * 1024) return false
        return runCatching {
            val head = ByteArray(16)
            file.inputStream().use { it.read(head) }
            val text = String(head, Charsets.ISO_8859_1).lowercase()
            if (text.contains("<!doctype") || text.contains("<html") || text.trimStart().startsWith("{")) {
                return false
            }
            val hex = head.joinToString("") { "%02x".format(it) }
            // mp4/mov: ....ftyp ; webm/mkv: 1a45dfa3 ; ts: 47 ; mp3: fff* / 494433 ; flv/av: 464c5601 / 41564920
            hex.contains("66747970") || hex.startsWith("1a45dfa3") || hex.startsWith("47") ||
                    hex.startsWith("494433") || hex.startsWith("fff") || hex.startsWith("464c5601") ||
                    hex.startsWith("41564920")
        }.getOrDefault(false)
    }

    fun downloadToFile(
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
                setRequestProperty("User-Agent", Http.UA)
            }
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) throw ExtractException("下载失败：HTTP $code")
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
        // 下完先验货：不是真媒体就当成失败，交给上层换下一个地址
        if (!looksLikeMedia(target)) {
            throw ExtractException("这个地址下回来的不是视频数据（可能被拦截或已过期）")
        }
    }

    /**
     * 找内置 ffmpeg。
     * 顺序：
     * 1. **直接问库**（youtubedl-android 内部已经算好了 ffmpegPath，最可靠）；
     * 2. 主动触发一次 ffmpeg 初始化（把 libffmpeg.zip.so 解压出来）；
     * 3. 在整个应用私有目录里搜（名字含 ffmpeg 的都被考虑）。
     * 找不到时把「搜过哪里、看到过什么」一并返回，方便定位。
     */
    fun locateFfmpeg(context: Context): Pair<File?, String> {
        val notes = mutableListOf<String>()

        val libDir = findLibsDir(context)
        val allLibDirs = findLibDirs(context)
        if (libDir != null) notes += "共享库目录：${libDir.absolutePath}"
        else notes += "没找到共享库目录（libav*.so）"
        val hasCxx = allLibDirs.any { dir ->
            runCatching { dir.listFiles()?.any { it.name.startsWith("libc++_shared") } == true }
                .getOrDefault(false)
        }
        notes += "库目录 ${allLibDirs.size} 个，libc++_shared ${if (hasCxx) "已找到" else "缺失"}"



        // 1) 反射拿库里的 ffmpegPath（必须验证能跑，否则就会出 libavdevice.so.61 找不到那种错）
        libFieldPath(context, notes)?.let { file ->
            if (verify(file, libDir, context)) {
                notes += "可用（库路径）：${file.absolutePath}"
                return file to notes.joinToString("；")
            }
        }

        // 2) 主动初始化一次
        val initError = runCatching {
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context.applicationContext)
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
        if (initError != null) notes += "ffmpeg 初始化报错：$initError"
        else notes += "ffmpeg 初始化完成"

        libFieldPath(context, notes)?.let { file ->
            if (verify(file, libDir, context)) {
                notes += "可用（初始化后）：${file.absolutePath}"
                return file to notes.joinToString("；")
            }
        }

        // 3) 关键一步：共享库目录已经告诉我们 ffmpeg 解压在哪个包目录里，
        //    直接把这个目录里的**所有文件**都拿出来逐个试跑
        //    （真正的可执行文件名不一定叫 ffmpeg，之前按名字找就漏了）
        val packageCandidates = mutableListOf<File>()
        libDir?.let { lib ->
            val packageRoot = lib.parentFile?.parentFile   // .../packages/ffmpeg/usr/lib -> .../packages/ffmpeg
            packageRoot?.let { root ->
                runCatching {
                    root.walkTopDown().maxDepth(4).forEach { f ->
                        if (f.isFile && f.length() > 0) packageCandidates += f
                    }
                }
            }
            notes += "包目录候选：${packageCandidates.size} 个（${packageRoot?.absolutePath}）"
        }
        packageCandidates.forEach { file ->
            if (verify(file, libDir, context)) {
                notes += "可用（包目录）：${file.absolutePath}"
                return file to notes.joinToString("；")
            }
        }

        // 4) 兜底：全盘按名字搜（私有目录 + native 库目录）
        val roots = buildList {
            add(context.applicationInfo.nativeLibraryDir)
            add(context.noBackupFilesDir.absolutePath)
            add(context.filesDir.absolutePath)
            add(context.cacheDir.absolutePath)
            add(runCatching { context.codeCacheDir.absolutePath }.getOrNull())
            add(runCatching { context.dataDir.absolutePath }.getOrNull())
        }.filterNotNull().distinct()

        val candidates = mutableListOf<File>()
        roots.forEach { rootPath ->
            runCatching {
                java.io.File(rootPath).walkTopDown().maxDepth(6).forEach { file ->
                    if (file.isFile && file.length() > 0 &&
                        file.name.contains("ffmpeg") &&
                        !file.name.endsWith(".zip.so")
                    ) {
                        candidates += file
                    }
                }
            }
        }
        notes += "搜索了 ${roots.size} 个目录，找到 ${candidates.size} 个候选项"

        // 优先名字正好是 ffmpeg 的，其次 libffmpeg.so 这类启动器
        val ordered = candidates.sortedBy { if (it.name == "ffmpeg") 0 else 1 }
        ordered.forEach { file ->
            if (verify(file, libDir, context)) {
                notes += "可用：${file.absolutePath}"
                return file to notes.joinToString("；")
            }
        }

        val probe = (packageCandidates + candidates).firstOrNull { it.canExecute() || it.name.contains("ffmpeg") }
        if (probe != null) {
            val (_, output) = runProcess(probe, listOf("-version"), libDir, context)
            notes += "最后一个候选 ${probe.name} 的输出：${output.take(200)}"
        }
        notes += "候选：${(packageCandidates + candidates).take(8).joinToString("、") { it.name }}"
        return null to notes.joinToString("；")
    }

    /**
     * 找出 ffmpeg 共享库（libav*.so）所在目录。
     * libffmpeg.so 这个启动器必须靠 LD_LIBRARY_PATH 才能找到它们，
     * 少了这一步就会报 "library libavdevice.so.61 not found"。
     */
    /** 所有含 .so 的目录都要加进 LD_LIBRARY_PATH（libc++_shared.so 常常不在同一个目录） */
    private fun findLibDirs(context: Context): List<File> {
        val roots = buildList {
            add(context.noBackupFilesDir.absolutePath)
            add(context.filesDir.absolutePath)
            add(context.cacheDir.absolutePath)
            add(runCatching { context.codeCacheDir.absolutePath }.getOrNull())
            add(runCatching { context.dataDir.absolutePath }.getOrNull())
            add(context.applicationInfo.nativeLibraryDir)
        }.filterNotNull().distinct()

        val dirs = linkedSetOf<File>()
        roots.forEach { rootPath ->
            runCatching {
                java.io.File(rootPath).walkTopDown().maxDepth(6).forEach { file ->
                    if (file.isFile && (file.name.endsWith(".so") || file.name.contains(".so."))) {
                        file.parentFile?.let { dirs += it }
                    }
                }
            }
        }
        return dirs.toList()
    }

    private fun findLibsDir(context: Context): File? {
        val roots = buildList {
            add(context.noBackupFilesDir.absolutePath)
            add(context.filesDir.absolutePath)
            add(context.cacheDir.absolutePath)
            add(runCatching { context.codeCacheDir.absolutePath }.getOrNull())
            add(runCatching { context.dataDir.absolutePath }.getOrNull())
            add(context.applicationInfo.nativeLibraryDir)
        }.filterNotNull().distinct()

        var found: File? = null
        roots.forEach { rootPath ->
            runCatching {
                java.io.File(rootPath).walkTopDown().maxDepth(6).forEach { file ->
                    if (found == null && file.isFile &&
                        (file.name.startsWith("libavcodec.so") || file.name.startsWith("libavdevice.so"))
                    ) {
                        found = file.parentFile
                    }
                }
            }
        }
        return found
    }

    /** 真的跑一次 ffmpeg -version，验证这个文件能当 ffmpeg 用 */
    private fun verify(binary: File, libDir: File?, context: Context): Boolean = runCatching {
        if (!binary.exists() || binary.length() <= 0) return false
        if (binary.name.endsWith(".zip.so")) return false
        val (_, output) = runProcess(binary, listOf("-version"), libDir, context)
        // 只要它能报出版本，就当它能用（有些构建退出码不一定是 0）
        output.contains("ffmpeg version", true) || output.contains("ffmpeg", true) && output.contains("version", true)
    }.getOrDefault(false)

    /** 执行 ffmpeg，执行前把共享库目录加进 LD_LIBRARY_PATH */
    private fun runProcess(
        binary: File,
        args: List<String>,
        libDir: File?,
        context: Context
    ): Pair<Int, String> {
        val process = ProcessBuilder(listOf(binary.absolutePath) + args)
            .redirectErrorStream(true)
            .apply {
                val paths = buildList {
                    libDir?.let { add(it.absolutePath) }
                    findLibDirs(context).forEach { add(it.absolutePath) }
                    add(context.applicationInfo.nativeLibraryDir)
                    System.getenv("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.distinct()
                environment()["LD_LIBRARY_PATH"] = paths.joinToString(":")
            }
            .start()
        val log = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        val code = process.waitFor()
        return code to log
    }

    /** 反射读 youtubedl-android 内部算好的 ffmpegPath */
    private fun libFieldPath(context: Context, notes: MutableList<String>): File? {
        return runCatching {
            val instance = com.yausername.youtubedl_android.YoutubeDL.getInstance()
            val field = instance.javaClass.getDeclaredField("ffmpegPath")
            field.isAccessible = true
            val file = field.get(instance) as? File
            if (file != null && file.exists() && file.length() > 0) {
                notes += "库提供的路径：${file.absolutePath}"
                file
            } else {
                null
            }
        }.getOrNull()
    }

    fun findFfmpeg(context: Context): File? = locateFfmpeg(context).first

    fun execFfmpeg(binary: File, args: List<String>): Pair<Int, String> {
        val command = mutableListOf(binary.absolutePath)
        command += args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        val code = process.waitFor()
        return code to log
    }
}
