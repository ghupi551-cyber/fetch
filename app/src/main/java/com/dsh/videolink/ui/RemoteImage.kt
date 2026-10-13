package com.dsh.videolink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsh.videolink.extract.Http
import com.dsh.videolink.ui.glass.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 极简内存图片缓存（LRU，最多 32 张）。 */
object ImageCache {
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 32
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache[key] }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(cache) { cache[key] = bitmap }
    }
}

/**
 * 加载网络图片（带请求头，抖音/B 站封面都需要 Referer）。
 * 会自动按需降采样，避免大图 OOM。
 */
@Composable
fun rememberRemoteImage(
    url: String?,
    headers: Map<String, String> = emptyMap(),
    maxDimension: Int = 2048
): ImageBitmap? {
    var bitmap by remember(url) {
        mutableStateOf(url?.let { ImageCache.get(it)?.asImageBitmap() })
    }

    LaunchedEffect(url, headers) {
        if (url.isNullOrBlank() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val bytes = Http.getBytes(url, headers) ?: return@withContext null
            decodeSampled(bytes, maxDimension)
        }
        if (loaded != null) {
            ImageCache.put(url, loaded)
            bitmap = loaded.asImageBitmap()
        }
    }

    return bitmap
}

private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    var dimension = maxOf(bounds.outWidth, bounds.outHeight)
    while (dimension / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

@Composable
fun RemoteImage(
    url: String?,
    headers: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    val image = rememberRemoteImage(url, headers)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Spinner(size = 20.dp, strokeWidth = 2.dp)
        }
    }
}
