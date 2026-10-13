# Fetch

> 粘贴链接，提取可直接播放 / 下载的视频地址。

一个 Android 应用：把视频链接粘进去，拿到能直接播放、直接下载的地址。
界面用液态玻璃风格，底部标签栏支持长按拖动的透镜折射效果。

- **应用名**：Fetch
- **包名**：`com.dsh.videolink`
- **版本**：5.0.0（versionCode 34）
- **要求**：Android 8.0+（arm64-v8a）
- **许可证**：GPL-3.0

---

## 支持的平台

| 平台 | 情况 |
| --- | --- |
| 抖音 | ✅ 专用解析（**不要挂代理**，抖音对国外 IP 会风控） |
| 快手 | ✅ |
| 小红书 | ✅ |
| B 站 | ✅ 720P（1080P+ 需要登录账号） |
| 微博 / 西瓜视频 / 今日头条 / 知乎 | ✅ |
| TikTok / Instagram / X / Facebook | ✅（走在线接口） |
| 任意 `.mp4` / `.m3u8` / `.mpd` 直链 | ✅ |
| YouTube | ❌ 已移除支持（本机直连被拦、在线线路也不稳定，不再做无用尝试） |
| 优酷 / 腾讯视频 / 爱奇艺 | ❌ DRM 加密，解不了 |
| 皮皮虾 / 微信视频号 / 红果短剧 | ❌ 引擎不支持 |

## 功能

| | |
| --- | --- |
| 🔗 **一键提取** | 粘贴链接即可拿到可播放 / 可下载的地址 |
| 🎞️ **画质与帧率** | 显示分辨率、帧率、体积；按清晰度从高到低排序 |
| ⚡ **并发解析** | 本机与在线线路同时开跑，谁先成功用谁 |
| 🎬 **本机合成** | 画面与声音分离时自动合成完整视频（ffmpeg 优先，失败自动改 Media3） |
| 🎵 **音频** | 「只存音频」全平台可用；音乐播放器式预览（转盘、进度拖动、倍速） |
| ⬇️ **带进度下载** | 百分比 / 速度 / 大小，可取消、可重试；已合成的本地文件直接复制 |
| ⭐ **收藏** | 结果里点收藏，历史页单独一栏，随时回头再解析 |
| 🪟 **液态玻璃** | 底部标签栏长按拖动有透镜折射；浅色 / 深色纯黑 / 5 种强调色 |

## 解析顺序

1. **直链** —— `.mp4 / .m3u8 / .mpd / .webm`，m3u8 主列表自动展开清晰度；
2. **抖音专用** —— 短链取作品号 → 访问主页预热 Cookie → 读分享页 `_ROUTER_DATA`，
   失败自动重试 3 次（每次都重新预热）；`playwm`→`play` 得到无水印地址；
3. **B 站专用** —— 公开接口拿带音轨 mp4（中性 UA 绕开风控 412）；
4. **通用引擎** —— 覆盖大量站点；同一链接按多条客户端线路重试；
5. **在线接口** —— 抖音 / TikTok 免水印公共接口（本地全失败时才用）；
6. **内置网页解析** —— `og:video` / `<video>` / JSON-LD / 页面内嵌媒体地址。

**出结果前会真机验证**（HTTP 200/206），取不到就不给用户，避免「点开黑屏」。

## 安装

到 Releases 下载 `Fetch-x.y.z.apk`，在手机上安装（提示「未知来源」时允许即可）。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleRelease      # 或使用你自己的 Gradle Wrapper
```

版本组合：AGP 9.0.0 / Kotlin 2.3.10 / Compose 1.10.3 / Media3 1.11.1 / youtubedl-android 0.18.1。

### 在 aarch64 Linux 上构建（可选）

Android SDK 的 `build-tools` 只提供 x86_64 的 `aapt2`；在 arm64 机器上可换用 Termux 的原生 aapt2：

```properties
android.aapt2FromMavenOverride=/opt/tools/aapt2
```

（路径必须以 `aapt2` 结尾，AGP 会校验。）

另外两个必须的设置：

- `ndk.abiFilters += "arm64-v8a"` —— 只保留 arm64 控制体积；
- `packaging.jniLibs.useLegacyPackaging = true` —— **必须**，youtubedl-android 要从
  `nativeLibraryDir` 读取 `libpython.zip.so`。

## 项目结构

```
app/src/main/java/com/dsh/videolink/
├── extract/          解析
│   ├── VideoExtractor.kt   链路调度（直链 → 抖音 → B站 → 通用引擎 → 在线 → 网页兜底）
│   ├── DouyinExtractor.kt  抖音分享页解析（含重试与预热）
│   ├── OnlineParse.kt      在线免水印接口（抖音 / TikTok）
│   ├── YtDlpEngine.kt      通用引擎封装（多线路 + 超时保护 + 引擎自更新）
│   ├── MediaMerger.kt      画面+声音合成（ffmpeg → Media3 双方案）
│   ├── MediaProbe.kt       读取真实分辨率 / 帧率
│   ├── MediaUrl.kt         判断地址是不是真媒体（防止把网页当视频）
│   ├── RoutePlan.kt        智能选路（按链接分类记战绩、后台测速）
│   ├── NetworkProbe.kt     代理（VPN）检测
│   └── Http.kt             极简 HTTP（Cookie 会话、二进制抓取）
├── download/         应用内下载器（进度 / 速度 / 取消 / 重试）
├── data/             设置、历史、收藏（SharedPreferences）
├── ui/               主页 / 下载 / 历史 / 设置 + 播放器 + 图片查看器 + 赞赏页
│   └── glass/        液态玻璃组件（移植自 AndroidLiquidGlass catalog）
└── VideoLinkApp.kt   崩溃日志与网络异常兜底
```

## 隐私

- 只申请必要权限：网络、网络状态、震动，以及仅 Android 9 以下生效的存储权限；
- 不含任何统计 / 上报 SDK；
- 只在你点「下载」时才写文件，保存到 `下载/视频链接提取`；
- 历史与收藏只存本机。

## 已知限制

- **没有 4K / 120fps**：高档流需要登录或本机直连，本机方案拿不到；1080P 属于正常水平；
- **B 站 1080P+ 需要登录**（公开接口只给到 720P）；
- **抖音请在关掉代理时解析**（开着代理会被风控，应用会检测并提示）；
- **仅 arm64-v8a**。

## 许可证

本项目以 **GPL-3.0** 发布，完整文本见 [LICENSE](LICENSE)。

通用解析引擎使用了 [youtubedl-android](https://github.com/yausername/youtubedl-android)（GPL-3.0），
它把 CPython 运行时与 yt-dlp 一起打包进应用，因此本项目整体采用同一许可证。
第三方组件清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Kyant，Apache-2.0）—— 液态玻璃效果
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)（Unlicense）—— 通用解析引擎
- [youtubedl-android](https://github.com/yausername/youtubedl-android)（GPL-3.0）—— yt-dlp 的 Android 封装
- [parse-video](https://github.com/wujunwei928/parse-video)（MIT）—— 抖音解析思路

## 发布到 GitHub（手机上也能做）

1. 手机浏览器打开 `github.com`，注册 / 登录，右上角 **+ → New repository**，
   名字填 `fetch`，选 **Public**，Create。
2. 进入仓库 → **uploading an existing file** → 把源码包解压后的文件
   （`app/`、`web/`、`gradle/`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、
   `README.md`、`LICENSE`、`THIRD_PARTY_NOTICES.md`、`.gitignore`）全部上传 → Commit。
3. 回仓库首页 → 右侧 **Releases → Create a new release** → Tag 填 `v5.0.0`，
   标题 `Fetch 5.0.0`，把 APK 拖到附件区 → Publish release。

> 因为链接了 GPL-3.0 的组件，仓库公开（含源码）即满足许可证要求。
