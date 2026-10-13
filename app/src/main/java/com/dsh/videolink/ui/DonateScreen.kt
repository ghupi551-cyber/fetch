package com.dsh.videolink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dsh.videolink.R
import com.dsh.videolink.ui.glass.AppText

/**
 * 赞赏页：单独一屏，中间就是赞赏码。
 */
@Composable
fun DonateScreen(onClose: () -> Unit) {
    val colors = LocalAppColors.current

    Box(
        Modifier
            .fillMaxSize()
            .background(if (colors.dark) Color.Black else Color(0xFFF5F6FA))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.glassStrongSurface)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(colors.text)
                    )
                }
                AppText(text = "赞赏支持", style = AppText.title, color = colors.text)
            }

            Box(Modifier.size(12.dp))

            AppText(
                text = "如果这个应用帮到了你\n可以扫码请我喝杯咖啡 ☕",
                style = AppText.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )

            // 赞赏码：白底，方便扫描
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(10.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.donate_qr),
                    contentDescription = "微信赞赏码",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AppText(
                text = "微信扫码赞赏",
                style = AppText.label,
                color = colors.text
            )

            AppText(
                text = "感谢你的支持，这会让我更有动力继续维护它 🙏",
                style = AppText.caption,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
