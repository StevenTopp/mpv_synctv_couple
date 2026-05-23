# mpv for Android (SyncTV 专属定制版)

[![Build Status](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml)

本代码仓是基于开源旗舰级安卓视频播放器 [mpv-android](https://github.com/mpv-android/mpv-android) (基于本地高效 `libmpv` 解码库) 深度二次开发制作的专属版本。

我们针对 `SyncTV Couple / Mine` 实时同步观影系统，在 Android 原生架构上重构并注入了**专属的双向同步控制、弹幕层渲染、防盗链伪装等全新特性**，旨在打破移动端手机浏览器在视频格式解码、高码率播放、弹幕渲染上的多重性能瓶颈。

---

## 🚀 专属定制扩展特性 (新加特性)

我们通过修改 Kotlin 核心播放器架构，并在 `app/src/main/java/is/xyz/mpv/sync/` 等模块中设计了一整套高精度通信桥，为您带来以下全新原生体验：

### 1. 🌐 内置网页同步面板 (Webview Sliding Panel)
* 在播放界面注入了轻量级、可滑动/隐藏的内置 Webview 控制面板。
* 玩家在播放器内可直接浏览 `SyncTV Couple` 的房间网页，无需在多个 App 间来回切换。

### 2. 🌁 高保真 Web-Native 双向通信桥 (Bidirectional Bridge)
* **Web 指令控制 Native**：实现极高精度的 `AndroidBridge` 控制，Web 端网页接收到房间内 WebSocket 的同步播放指令后，能实时控制本地原生 `libmpv` 执行高精度播放、暂停、毫秒级进度跳转 (Seek) 以及倍速切换。
* **Native 状态反馈 Web**：在原生播放器控制条上的任意操作，会自动通过桥接方法逆向反馈给内置网页，并分发至整个房间，实现完全对称的指令广播。

### 3. 🛡️ 第三方直链防盗链及自定义头部注入 (Custom HTTP Headers)
* 完美解决了 Alist 网盘直链等资源在原生播放器中的跨域限制。
* 播放器可实时提取当前视频 URL 并进行特征判定。针对百度网盘等直链，系统会自动注入正确的 `User-Agent: pan.baidu.com` 等 HTTP 头部字段到原生 `libmpv` 核心，绕过第三方防盗链与防下载机制。

### 4. 🎇 高性能硬件加速弹幕渲染层 (Native Danmaku Canvas)
* 放弃了开销极大的 Web 网页端弹幕渲染方案。
* 在 Native 视频渲染 SurfaceView 之上，注入了一层高性能的纯原生弹幕透明画布 (`DanmakuView.kt`)。网页端接收到的房间弹幕，可直接投射并在安卓原生画布上以极其丝滑、无延迟的 60FPS 帧率进行硬件加速渲染。

### 5. 🔤 房间在线字幕同步加载 (Synchronized Subtitle Injection)
* 房间内任何成员上传或选择的同步字幕（如 `.vtt` / `.srt` / `.ass` 字幕），会在本地原生播放器启动时自动抓取，并通过 `libmpv` 的 `sub-add` 指令直接载入原生视频，完美配合 `libass` 实现超高颜值的精美字幕渲染。

### 6. 🏆 极致的原生解码优势 (libmpv Hardware Decoding)
* 移动端浏览器往往面临极大的硬解限制（如不支持 HEVC/H.265、高比特率 HDR 画面、DTS 多声道音轨、以及复杂样式的 SSA/ASS 双字幕特效）。
* 本定制版利用高效的本地 `libmpv` 原生解码核心，能够完美硬解播放高达 4K 10bit、HDR 原生色彩、甚至 Dolby/DTS 音频的超清片源，并始终保持与 Web 端房间的毫秒级时序同步！

---

## 🏗️ 运行与编译说明

编译以及原生 Native 部分的构建方法与原项目完全一致，详情请参阅 `buildscripts/README.md`。

本定制版本代码结构修改位置：
* **核心控制逻辑与指令桥：** [MPVActivity.kt](app/src/main/java/is/xyz/mpv/MPVActivity.kt)
* **弹幕渲染画布：** [DanmakuView.kt](app/src/main/java/is/xyz/mpv/DanmakuView.kt)
* **播放界面控制层 XML：** `app/src/main/res/layout/player.xml`
