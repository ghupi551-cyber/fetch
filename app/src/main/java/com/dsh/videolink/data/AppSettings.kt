package com.dsh.videolink.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.dsh.videolink.ui.GLASS_STRENGTH_DEFAULT

/**
 * 使用可观察的 Compose state 包裹 SharedPreferences，界面可以自动刷新。
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("videolink_settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(prefs.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT)
        private set

    /** 液态玻璃强度，0.3 ~ 1.8，默认 1.0 */
    var glassStrength by mutableStateOf(prefs.getFloat(KEY_GLASS, GLASS_STRENGTH_DEFAULT))
        private set

    /** 打开应用时自动读取剪贴板里的链接（默认关闭，避免一打开就被打扰） */
    var autoClipboard by mutableStateOf(prefs.getBoolean(KEY_AUTO_CLIPBOARD, false))
        private set

    /** 播放视频时保持屏幕常亮 */
    var keepScreenOn by mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
        private set

    /** 动态渐变背景：默认关闭，静态渐变更省电也更跟手 */
    var animatedBackground by mutableStateOf(prefs.getBoolean(KEY_ANIMATED_BG, false))
        private set

    /** 检查更新用的 version.json 地址（用户可自己填） */
    var updateUrl by mutableStateOf(prefs.getString(KEY_UPDATE_URL, "").orEmpty())
        private set

    /** 强调色方案索引 */
    var accentIndex by mutableStateOf(prefs.getInt(KEY_ACCENT, 0))
        private set

    /** 用户粘贴的抖音 Cookie（可选）：抖音的分享页/接口需要新鲜 Cookie */
    var douyinCookie by mutableStateOf(prefs.getString(KEY_DOUYIN_COOKIE, "").orEmpty())
        private set

    fun setTheme(value: String) {
        themeMode = value
        prefs.edit { putString(KEY_THEME, value) }
    }

    /** 拖动滑块时只更新内存状态，避免每一帧都写磁盘。 */
    fun previewGlassStrength(value: Float) {
        glassStrength = value
    }

    fun persistGlassStrength() {
        prefs.edit { putFloat(KEY_GLASS, glassStrength) }
    }

    fun updateAutoClipboard(value: Boolean) {
        autoClipboard = value
        prefs.edit { putBoolean(KEY_AUTO_CLIPBOARD, value) }
    }

    fun updateKeepScreenOn(value: Boolean) {
        keepScreenOn = value
        prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }
    }

    fun updateAnimatedBackground(value: Boolean) {
        animatedBackground = value
        prefs.edit { putBoolean(KEY_ANIMATED_BG, value) }
    }

    fun updateUpdateUrl(value: String) {
        updateUrl = value
        prefs.edit { putString(KEY_UPDATE_URL, value) }
    }

    fun updateAccentIndex(value: Int) {
        accentIndex = value
        prefs.edit { putInt(KEY_ACCENT, value) }
    }

    fun updateDouyinCookie(value: String) {
        douyinCookie = value
        prefs.edit { putString(KEY_DOUYIN_COOKIE, value) }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // 默认改成浅色，key 带版本号让老安装也生效
        private const val KEY_THEME = "theme_mode_v2"
        // key 带版本号：换默认值时老安装也能生效
        private const val KEY_GLASS = "glass_strength_v2"
        private const val KEY_AUTO_CLIPBOARD = "auto_clipboard"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_ANIMATED_BG = "animated_background"
        private const val KEY_DOUYIN_COOKIE = "douyin_cookie"
        private const val KEY_ACCENT = "accent_index"
        private const val KEY_UPDATE_URL = "update_url"
    }
}
