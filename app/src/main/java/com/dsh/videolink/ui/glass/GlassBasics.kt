package com.dsh.videolink.ui.glass

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsh.videolink.ui.AppText
import com.dsh.videolink.ui.LocalAppColors
import com.dsh.videolink.ui.LocalGlassStrength
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AppText.body,
    color: Color = LocalAppColors.current.text,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign),
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * 液态玻璃面板（用于主要区块）。
 * 只做 vibrancy + blur：透镜折射（lens）留给可交互控件，
 * 否则列表里每个卡片都要跑一遍折射 shader，滑动会明显掉帧。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    strong: Boolean = false,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalAppColors.current
    val glassStrength = LocalGlassStrength.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(8f.dp.toPx() * glassStrength)
            },
            highlight = { Highlight.Default.copy(alpha = 0.55f) },
            innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.25f) },
            shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.16f)) },
            onDrawSurface = {
                drawRect(
                    if (tint != Color.Unspecified) tint
                    else if (strong) colors.glassStrongSurface
                    else colors.glassSurface
                )
            }
        ),
        content = content
    )
}

/** 轻量卡片：不采样背景，用在列表行里，滚动不掉帧。 */
@Composable
fun PlainCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppColors.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    Column(
        modifier
            .clip(shape)
            .background(colors.glassSurface)
            .border(1.dp, colors.glassBorder.copy(alpha = 0.35f), shape),
        content = content
    )
}

/** 玻璃输入框，用于粘贴视频链接。 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    minHeight: Dp = 104.dp,
    cornerRadius: Dp = 24.dp,
    masked: Boolean = false
) {
    val colors = LocalAppColors.current
    val glassStrength = LocalGlassStrength.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(8f.dp.toPx() * glassStrength)
                },
                highlight = { Highlight.Default.copy(alpha = 0.5f) },
                innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.28f) },
                onDrawSurface = { drawRect(colors.glassSurface) }
            )
            .padding(16.dp)
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = minHeight - 32.dp)) {
            if (value.isEmpty()) {
                AppText(
                    text = placeholder,
                    style = AppText.body,
                    color = colors.textSecondary.copy(alpha = 0.65f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = AppText.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
    }
}

/**
 * 普通圆形图标按钮：不采样背景。
 * 之前每个按钮都走一遍 backdrop，结果列表里几十个按钮会把帧率拖垮。
 */
@Composable
fun AppIconButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalAppColors.current.text,
    buttonSize: Dp = 44.dp,
    iconSize: Dp = 19.dp,
    description: String? = null,
    strong: Boolean = false
) {
    val colors = LocalAppColors.current
    Box(
        modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(if (strong) colors.glassStrongSurface else colors.glassSurface)
            .border(1.dp, colors.glassBorder.copy(alpha = 0.3f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = description,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(iconSize)
        )
    }
}

/** 轻量胶囊标签（不采样背景）。 */
@Composable
fun GlassPill(
    text: String,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalAppColors.current.textSecondary
) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .clip(Capsule())
            .background(colors.glassSurface)
            .border(1.dp, colors.glassBorder.copy(alpha = 0.28f), Capsule())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppText(text = text, style = AppText.caption, color = tint, maxLines = 1)
    }
}

/** 胶囊形可点小按钮（不采样背景）。 */
@Composable
fun ActionChip(
    @DrawableRes iconRes: Int? = null,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalAppColors.current.text,
    background: Color = LocalAppColors.current.glassStrongSurface
) {
    Row(
        modifier
            .clip(Capsule())
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                colorFilter = ColorFilter.tint(tint)
            )
        }
        AppText(text = label, style = AppText.caption, color = tint, maxLines = 1)
    }
}

@Composable
fun Spinner(
    color: Color = LocalAppColors.current.accent,
    size: Dp = 22.dp,
    strokeWidth: Dp = 2.5.dp
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle"
    )
    Canvas(Modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        drawArc(
            color = color.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
