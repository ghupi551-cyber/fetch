package com.dsh.videolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class Blob(
    val color: Color,
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val speed: Float
)

// 深色模式要「全黑」：底就纯黑，光斑只留一点点层次，避免花哨
private val DarkBlobs = listOf(
    Blob(Color(0xFF10131F), 0.18f, 0.14f, 0.80f, 0.0f, 1.0f),
    Blob(Color(0xFF0C1A20), 0.86f, 0.16f, 0.74f, 1.3f, 1.25f),
    Blob(Color(0xFF180E16), 0.74f, 0.82f, 0.76f, 2.4f, 0.85f),
    Blob(Color(0xFF0A0E1A), 0.12f, 0.88f, 0.70f, 3.6f, 1.1f)
)

private val LightBlobs = listOf(
    Blob(Color(0xFFD3E0FF), 0.18f, 0.16f, 0.78f, 0.0f, 1.0f),
    Blob(Color(0xFFFFE1EC), 0.85f, 0.14f, 0.70f, 1.6f, 1.2f),
    Blob(Color(0xFFD6F2E9), 0.76f, 0.80f, 0.74f, 2.8f, 0.9f),
    Blob(Color(0xFFE9E1FF), 0.14f, 0.84f, 0.68f, 4.1f, 1.05f)
)

/**
 * 液态玻璃需要有东西可折射，这里画一层彩色渐变背景。
 * 通过 [layerBackdrop] 把这一层记录成 backdrop 给上层的玻璃控件采样。
 *
 * 性能说明：这一层一旦每帧重绘，整条背景图层 + 所有玻璃控件都会重算，
 * 所以开启动画时也只用 ~12fps 更新（视觉上依旧是缓慢流动，但省很多电和帧）。
 */
@Composable
fun GlassBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    // 静态渐变：玻璃需要有明暗变化才看得出折射，但不需要一直动，
    // 不动也省电、滑动更稳。
    val progress = 0.18f

    val blobs = if (colors.dark) DarkBlobs else LightBlobs

    Canvas(modifier.fillMaxSize().layerBackdrop(backdrop)) {
        drawRect(if (colors.dark) Color.Black else Color(0xFFF5F6FA))

        val twoPi = (2 * PI).toFloat()
        blobs.forEach { blob ->
            val angle = progress * twoPi * blob.speed + blob.phase
            val centerX = size.width * (blob.x + 0.14f * cos(angle))
            val centerY = size.height * (blob.y + 0.12f * sin(angle * 1.13f))
            val radius = size.minDimension * blob.radius
            val center = Offset(centerX, centerY)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        blob.color.copy(alpha = if (colors.dark) 0.9f else 0.95f),
                        blob.color.copy(alpha = 0f)
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        // 深色下再压一层纯黑，保证整体是「全黑」底
        if (colors.dark) {
            drawRect(Color.Black.copy(alpha = 0.55f))
        }

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (colors.dark) 0.03f else 0.35f),
                    Color.Transparent
                )
            )
        )
    }
}
