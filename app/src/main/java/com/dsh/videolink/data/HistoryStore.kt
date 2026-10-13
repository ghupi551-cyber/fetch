package com.dsh.videolink.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val url: String,
    val title: String,
    val time: Long
)

/** 提取历史记录，最多保留 30 条。 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("videolink_history", Context.MODE_PRIVATE)

    var items by mutableStateOf(load())
        private set

    fun add(url: String, title: String?) {
        val entry = HistoryEntry(url = url, title = title.orEmpty(), time = System.currentTimeMillis())
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

    private fun save(list: List<HistoryEntry>) {
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

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url")
                    if (url.isNotEmpty()) {
                        add(
                            HistoryEntry(
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
        const val KEY = "history"
        const val MAX_ITEMS = 30
    }
}
