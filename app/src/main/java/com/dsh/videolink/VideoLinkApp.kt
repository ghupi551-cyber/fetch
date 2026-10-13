package com.dsh.videolink

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * 崩溃日志：万一真的崩了，把堆栈写到应用私有目录，
 * 用户可以在「设置 → 关于」里一键把日志发出来，方便定位问题。
 * 只记堆栈，不记录任何链接、视频或个人信息之外的运行信息。
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean =
        file(context).let { it.exists() && it.length() > 0L }

    fun read(context: Context): String =
        runCatching { file(context).readText().takeLast(6000) }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val version = runCatching {
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    "${info.versionName}(${info.longVersionCode})"
                }.getOrDefault("?")
                file(app).appendText(
                    "\n===== $stamp · Fetch $version =====\n" +
                            Log.getStackTraceString(throwable) + "\n"
                )
            }
            // 网络类异常（国内网络解析时最常见）只记录、不退出应用：
            // 这类问题重试/换网络就能解决，没必要把用户踢出去。
            if (isRecoverable(throwable)) return@setDefaultUncaughtExceptionHandler
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 可以「吞掉」的异常：网络 / 连接 / 超时 / 解析失败 */
    private fun isRecoverable(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 8) {
            val name = current.javaClass.name
            if (name.contains("UnknownHost") ||
                name.contains("SocketTimeout") ||
                name.contains("ConnectException") ||
                name.contains("NetworkOnMainThread") ||
                name.contains("SSLException") ||
                name.contains("StreamReset") ||
                name.contains("IOException") ||
                name.contains("ExtractException")
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
