package com.dsh.videolink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dsh.videolink.data.AppSettings
import com.dsh.videolink.data.FavoritesStore
import com.dsh.videolink.data.HistoryStore
import com.dsh.videolink.extract.Http
import com.dsh.videolink.ui.AppRoot
import com.dsh.videolink.ui.appColors
import com.dsh.videolink.ui.LocalAppColors
import com.dsh.videolink.ui.LocalGlassStrength
import com.dsh.videolink.ui.glass.utils.ripple

class MainActivity : ComponentActivity() {

    private var sharedLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val settings = AppSettings(applicationContext)
        val history = HistoryStore(applicationContext)
        val favorites = FavoritesStore(applicationContext)
        sharedLink = readSharedText(intent)
        Http.userCookie = settings.douyinCookie

        setContent {
            val dark = when (settings.themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            val colors = appColors(dark, settings.accentIndex)

            LaunchedEffect(dark) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            CompositionLocalProvider(
                LocalAppColors provides colors,
                LocalGlassStrength provides settings.glassStrength,
                LocalIndication provides ripple(color = if (dark) Color.White else Color.Black)
            ) {
                AppRoot(
                    settings = settings,
                    history = history,
                    favorites = favorites,
                    sharedLink = sharedLink,
                    onSharedLinkConsumed = { sharedLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedText(intent)?.let { sharedLink = it }
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }
}
