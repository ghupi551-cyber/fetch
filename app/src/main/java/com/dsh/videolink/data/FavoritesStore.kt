package com.dsh.videolink.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteEntry(
    val url: String,
    val title: String,
    val time: Long
)

/**
 * 收藏（稍后再看）：只存用户最初粘贴的链接和标题，
 * 点开时重新解析一次，这样过期地址不会失效。
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("videolink_favorites", Context.MODE_PRIVATE)

    var items by mutableStateOf(load())
        private set

    fun contains(url: String): Boolean = items.any { it.url == url }

    /** 收藏 / 取消收藏，返回操作后是否已收藏 */
    fun toggle(url: String, title: String?): Boolean {
        return if (contains(url)) {
            remove(url)
            false
        } else {
            add(url, title)
            true
        }
    }

    fun add(url: String, title: String?) {
        if (url.isBlank()) return
        val entry = FavoriteEntry(url, title.orEmpty(), System.currentTimeMillis())
        val next = (listOf(entry) + items.filterNot { it.url == url }).take(MAX_ITEMS)
        items = next
        save(next)
    }

    fun remove(url: String) {
        val next = items.filterNot { it.url == url }
        items = next
        save(next)
    }

    fun clear() {
        items = emptyList()
        save(emptyList())
    }

    private fun save(list: List<FavoriteEntry>) {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("time", entry.time)
                }
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private fun load(): List<FavoriteEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url")
                    if (url.isNotEmpty()) {
                        add(
                            FavoriteEntry(
                                url = url,
                                title = obj.optString("title"),
                                time = obj.optLong("time")
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "favorites"
        const val MAX_ITEMS = 100
    }
}
