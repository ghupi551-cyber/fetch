# 第三方组件与许可证

Fetch 使用/打包了以下开源组件。对外分发本应用时，需要一并遵守这些许可证。

## 直接依赖

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| [youtubedl-android](https://github.com/yausername/youtubedl-android) | 0.18.1 | **GPL-3.0** | 在 Android 上运行 yt-dlp（打包 CPython 运行时） |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | 2025.11.12 | Unlicense（公有领域） | 1800+ 站点的解析内核 |
| [AndroidLiquidGlass / backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | 1.0.6 | Apache-2.0 | 液态玻璃效果（含移植进本项目的组件源码） |
| [kyant shapes](https://github.com/Kyant0/AndroidLiquidGlass) | 1.2.0 | Apache-2.0 | 胶囊等自定义形状 |
| [AndroidX Media3](https://github.com/androidx/media) | 1.11.1 | Apache-2.0 | 播放（ExoPlayer）+ 本机合成（Transformer） |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | 1.10.3 | Apache-2.0 | 界面 |
| [AndroidX Core / Activity](https://developer.android.com/jetpack/androidx) | 1.17.0 / 1.12.4 | Apache-2.0 | 基础库 |
| [Kotlin / kotlinx.coroutines](https://kotlinlang.org) | 2.3.10 / 1.11.0 | Apache-2.0 | 语言与协程 |
| [Jackson](https://github.com/FasterXML/jackson) | 2.11.1 | Apache-2.0 | youtubedl-android 的 JSON 映射 |
| [commons-io](https://commons.apache.org/proper/commons-io/) | 2.5 | Apache-2.0 | youtubedl-android 的 IO 工具 |

## 参考过思路但未包含代码

| 项目 | 许可证 | 参考内容 |
| --- | --- | --- |
| [yoinks](https://github.com/pablostanley/yoinks) | MIT | 提示了「终端工具背后其实是 yt-dlp」这条路线 |
| [parse-video](https://github.com/wujunwei928/parse-video) | MIT | 抖音 `_ROUTER_DATA` / `playwm→play` 做法 |
| [douyin-tool](https://github.com/CarGuo/douyin-tool) | MIT | 抖音分享页解析与请求头处理 |

## 关于 GPL-3.0

`youtubedl-android` 采用 GPL-3.0，它把 CPython 运行时与 yt-dlp 一起打包进 APK。
GPL 的传染性要求：**对外分发包含它的应用时，必须提供完整对应源码，并以 GPL-3.0 授权**。
本项目因此整体采用 GPL-3.0，源码即本仓库。

- 仅自己使用、不分发 → 不产生额外义务；
- 分发（上架应用商店、放网页供人下载）→ 提供本仓库源码即可满足；
- 不想承担 GPL 义务 → 必须移除 youtubedl-android 依赖，届时只剩直链解析与内置网页解析。

Apache-2.0 与 Unlicense 均与 GPL-3.0 兼容，可以一起打包，无需额外处理。
