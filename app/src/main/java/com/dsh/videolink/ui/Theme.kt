package com.dsh.videolink.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class AppColors(
    val dark: Boolean,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSecondary: Color,
    val glassSurface: Color,
    val glassStrongSurface: Color,
    val glassBorder: Color,
    val warning: Color,
    val success: Color
)

val DarkAppColors = AppColors(
    dark = true,
    text = Color(0xFFF2F3F8),
    textSecondary = Color(0xFF9BA2B4),
    accent = Color(0xFF6BA6FF),
    accentSecondary = Color(0xFFB49BFF),
    // 纯黑背景 + 磨砂白玻璃
    glassSurface = Color(0x1AFFFFFF),
    glassStrongSurface = Color(0x2EFFFFFF),
    glassBorder = Color(0x24FFFFFF),
    warning = Color(0xFFFFC46B),
    success = Color(0xFF4ED17E)
)

val LightAppColors = AppColors(
    dark = false,
    text = Color(0xFF16171D),
    textSecondary = Color(0xFF5C6270),
    accent = Color(0xFF0A6CFF),
    accentSecondary = Color(0xFF6C4DFF),
    glassSurface = Color(0x59FFFFFF),
    glassStrongSurface = Color(0x80FFFFFF),
    glassBorder = Color(0x66FFFFFF),
    warning = Color(0xFFB26A00),
    success = Color(0xFF12873C)
)

/** 可选的强调色方案（设置页里一键换色）。 */
data class AccentOption(val name: String, val accent: Color, val accentSecondary: Color)

val AccentOptions = listOf(
    AccentOption("极光蓝", Color(0xFF6BA6FF), Color(0xFFB49BFF)),
    AccentOption("薄荷绿", Color(0xFF4ED8B4), Color(0xFF8CE99A)),
    AccentOption("蜜橘橙", Color(0xFFFFAB5C), Color(0xFFFF8A8A)),
    AccentOption("樱花粉", Color(0xFFFF8FB1), Color(0xFFD9A0FF)),
    AccentOption("青碧", Color(0xFF4FD1E0), Color(0xFF7C9BFF))
)

fun appColors(dark: Boolean, accentIndex: Int): AppColors {
    val base = if (dark) DarkAppColors else LightAppColors
    val option = AccentOptions[accentIndex.coerceIn(0, AccentOptions.lastIndex)]
    return base.copy(accent = option.accent, accentSecondary = option.accentSecondary)
}

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/** 液态玻璃强度，设置页里的滑块会实时改变折射与模糊。默认拉到满。 */
const val GLASS_STRENGTH_MIN = 0.6f
const val GLASS_STRENGTH_MAX = 1.8f
const val GLASS_STRENGTH_DEFAULT = GLASS_STRENGTH_MAX

val LocalGlassStrength = staticCompositionLocalOf { GLASS_STRENGTH_DEFAULT }

object AppText {
    val hero = TextStyle(
        fontSize = 30.sp,
        lineHeight = 37.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp
    )
    val title = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold
    )
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val mono = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
}
