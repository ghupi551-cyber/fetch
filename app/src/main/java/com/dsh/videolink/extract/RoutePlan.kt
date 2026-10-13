package com.dsh.videolink.extract

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 智能选路。
 *
 * 同一个链接可能有好几条路：本机直连、内置浏览器、在线线路……
 * 哪条最快最好，取决于**你当前的网络**（节点、地区）和**站点状态**，
 * 所以这里做三件事：
 *
 * 1. **按链接分类**（TikTok / 其它）分别记录战绩；
 * 2. **后台定时实测**：本机能不能连上该站点、在线站点还剩几个活着；
 * 3. **把最好的路排在第一个**，每次都优先走它。
 */
object RoutePlan {

    enum class Site(val key: String, val label: String) {
        TIKTOK("tiktok", "TikTok"),
        OTHER("other", "其它站点")
    }

    private const val PREFS = "videolink_routes"
    /** 本机被按 IP 拦之后，多久内不再优先试本机 */
    private const val BLOCK_COOLDOWN_MS = 30 * 60 * 1000L
    /** 连通性探测结果的有效期 */
    private const val PROBE_TTL_MS = 10 * 60 * 1000L

    /** 最近一次测速的文字结果，设置页直接显示 */
    var lastProbeText by mutableStateOf("还没测过")
        private set

    var lastProbeAt by mutableStateOf(0L)
        private set

    fun siteOf(url: String): Site {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        return if (host.contains("tiktok")) Site.TIKTOK else Site.OTHER
    }

    /** 本机直连地址（用来判断网络层面通不通） */
    private fun localProbeUrl(site: Site): String? = when (site) {
        Site.TIKTOK -> "https://www.tiktok.com/"
        Site.OTHER -> null
    }

    // ---------------------------------------------------------------- 战绩记录

    fun markLocalOk(context: Context, site: Site, costMs: Long = 0L) {
        bump(context, "local_ok_${site.key}", +1)
        if (costMs > 0) recordCost(context, "local_ms_${site.key}", costMs)
    }

    private fun recordCost(context: Context, key: String, costMs: Long) {
        val prefs = prefs(context)
        val previous = prefs.getLong(key, 0L)
        // 滑动平均，避免单次抖动影响判断
        val next = if (previous <= 0L) costMs else (previous * 3 + costMs) / 4
        prefs.edit { putLong(key, next) }
    }

    fun markLocalBlocked(context: Context, site: Site) {
        val prefs = prefs(context)
        prefs.edit {
            putLong("local_blocked_${site.key}", System.currentTimeMillis())
            putInt("local_ok_${site.key}", 0)
        }
    }

    fun markOnlineOk(context: Context, site: Site, costMs: Long = 0L) {
        bump(context, "online_ok_${site.key}", +1)
        if (costMs > 0) recordCost(context, "online_ms_${site.key}", costMs)
        // 在线能用，就把本机的失败冷却继续延长
        prefs(context).edit { putLong("local_blocked_${site.key}", System.currentTimeMillis()) }
    }

    fun markOnlineFail(context: Context, site: Site) =
        bump(context, "online_fail_${site.key}", +1)

    private fun bump(context: Context, key: String, delta: Int) {
        val prefs = prefs(context)
        prefs.edit { putInt(key, prefs.getInt(key, 0) + delta) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 决策

    /**
     * 要不要「先走在线」？
     * 判断顺序：本机连不上 → 先在线；本机刚被按 IP 拦过 → 先在线；
     * 本机从来没成功过、而在线成功过 → 先在线；否则本机优先（本机更快）。
     */
    fun onlineFirst(context: Context, url: String): Boolean {
        val site = siteOf(url)
        if (site == Site.OTHER) return false
        val p = prefs(context)
        val now = System.currentTimeMillis()

        val reachable = p.getBoolean("reachable_${site.key}", true)
        val probedAt = p.getLong("reachable_at_${site.key}", 0L)
        if (now - probedAt < PROBE_TTL_MS && !reachable) return true

        val blockedAt = p.getLong("local_blocked_${site.key}", 0L)
        if (blockedAt > 0 && now - blockedAt < BLOCK_COOLDOWN_MS) return true

        val localOk = p.getInt("local_ok_${site.key}", 0)
        val onlineOk = p.getInt("online_ok_${site.key}", 0)
        if (localOk == 0 && onlineOk > 0) return true

        // 两条路都成功过 → 比实测速度，谁快用谁
        val localAvg = p.getLong("local_ms_${site.key}", 0L)
        val onlineAvg = p.getLong("online_ms_${site.key}", 0L)
        if (onlineAvg > 0 && localAvg > 0 && onlineAvg < localAvg) return true

        // TikTok 本机那条路慢（要真播一遍抓地址），在线成功过就优先在线
        if (onlineOk > 0) return true

        return false
    }

    /** 最近一次实测里，在线线路还有没有可用的（没测过就当作可用） */
    fun onlineAvailable(context: Context): Boolean {
        val p = prefs(context)
        val at = p.getLong("online_alive_at", 0L)
        if (at == 0L) return true
        return p.getInt("online_alive", 1) > 0
    }

    /**
     * 解析前快速探一下「这个站点本机到底能不能连上」。
     * 超时很短（3.5 秒），结果缓存 2 分钟，避免每次解析都等。
     * 这样国内网络访问外网时可以立刻提示，而不是让用户干等一两分钟。
     */
    fun quickReachable(context: Context, url: String): Boolean {
        val site = siteOf(url)
        if (site == Site.OTHER) return true
        val p = prefs(context)
        val cachedAt = p.getLong("quick_at_${site.key}", 0L)
        val now = System.currentTimeMillis()
        if (now - cachedAt < 30 * 1000L) {
            return p.getBoolean("quick_ok_${site.key}", true)
        }
        val probeUrl = localProbeUrl(site) ?: return true
        val ok = runCatching {
            Http.probe(probeUrl, timeoutMs = 3_500).code in 200..399
        }.getOrDefault(false)
        p.edit {
            putBoolean("quick_ok_${site.key}", ok)
            putLong("quick_at_${site.key}", now)
            putBoolean("reachable_${site.key}", ok)
            putLong("reachable_at_${site.key}", now)
        }
        return ok
    }

    /** 让连通性缓存立刻失效：用户连上代理后马上重试时，必须重新探测 */
    fun invalidateReachability(context: Context) {
        val p = prefs(context)
        p.edit {
            Site.entries.forEach { site ->
                remove("quick_at_${site.key}")
                remove("reachable_at_${site.key}")
            }
        }
    }

    /** 最近一次实测里，本机能不能连上该站点（没测过就当作可以） */
    fun localReachable(context: Context, url: String): Boolean {
        val site = siteOf(url)
        if (site == Site.OTHER) return true
        val p = prefs(context)
        val probedAt = p.getLong("reachable_at_${site.key}", 0L)
        if (probedAt == 0L) return true
        return p.getBoolean("reachable_${site.key}", true)
    }

    // ---------------------------------------------------------------- 后台测速

    /**
     * 实测每条路通不通。由界面在启动时 + 每隔几分钟调用一次。
     * 只做轻量探测（HEAD/小请求），不消耗流量也不解析视频。
     */
    suspend fun probe(context: Context): String = withContext(Dispatchers.IO) {
        runCatching { probeInner(context) }.getOrDefault("测速失败（网络异常）")
    }

    private suspend fun probeInner(context: Context): String {
        val app = context.applicationContext
        val lines = mutableListOf<String>()

        Site.entries.filter { it != Site.OTHER }.forEach { site ->
            val probeUrl = localProbeUrl(site) ?: return@forEach
            val ok = runCatching {
                val code = Http.probe(probeUrl, timeoutMs = 5_000).code
                code in 200..399
            }.getOrDefault(false)
            prefs(app).edit {
                putBoolean("reachable_${site.key}", ok)
                putLong("reachable_at_${site.key}", System.currentTimeMillis())
            }
            lines += "${site.label} 本机" + if (ok) "可达" else "不可达"
        }

        val text = lines.joinToString("；")
        lastProbeText = text
        lastProbeAt = System.currentTimeMillis()
        prefs(app).edit { putString("last_probe", text) }
        return text
    }

    /** 启动时先把上次的测速结果显示出来，别让用户看到空白 */
    fun loadLastProbe(context: Context) {
        val saved = prefs(context).getString("last_probe", null)
        if (!saved.isNullOrBlank()) lastProbeText = saved
    }
}
