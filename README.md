# mpv for Android (SyncTV Couple Edition)

[![Build Status](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml)

This is a customized fork of [mpv-android](https://github.com/mpv-android/mpv-android), a video player for Android based on [libmpv](https://github.com/mpv-player/mpv). 

This edition extends the player with **real-time playback synchronization features** tailored for the **SyncTV Couple** online synchronized theater system.

---

## 🚀 New Synchronization Features & Extensions

We modified the core Kotlin architecture and injected custom synchronization modules (`app/src/main/java/is/xyz/mpv/sync/`) to interface natively with the **SyncTV Mine / Couple** WebSocket server, adding:

1. **Native WebSocket Sync Protocol**: 
   A high-performance WebSocket client integrated directly into the player life cycle. It connects to FastAPI server rooms to sync play, pause, seek, and playback speed state changes in real time.
2. **"Sync Room" Player Controls Integration**:
   Added a beautiful native "Sync" button (`ic_sync_48dp`) inside the player control bar layout (`fragment_main_screen.xml` and `player.xml`), allowing users to toggle synchronization, join rooms, and monitor latency in a single tap.
3. **Collaborative Ready State Machine**:
   Supports client readiness status synchronization, ensuring all connected users are aligned and ready before playback resumes. Shows native progress overlay prompts during programmatic seeks.
4. **Android Native Danmaku Overlay Engine**:
   Injected a custom hardware-accelerated danmaku rendering canvas (`DanmakuView.kt`) on top of the native video surface view to display room chat messages and bullet comments seamlessly in real time.
5. **Universal Hardware Decoding Bypass**:
   Bypasses typical mobile browser media decoding constraints by leveraging `libmpv` native hardware acceleration (supporting high-bitrate 4K, HDR, H.265/HEVC, DTS audio, and multi-styled ASS/SSA subtitles) while staying perfectly frame-synced with the web server.

---

## Original Player Features

* Hardware and software video decoding
* Gesture-based seeking, volume/brightness control and more
* libass support for styled subtitles
* Secondary (or dual) subtitle support
* High-quality rendering with advanced settings (scalers, debanding, interpolation, ...)
* Play network streams with the "Open URL" function
* Background playback, Picture-in-Picture, keyboard input supported

### Library?

mpv-android is **not** a library/module (AAR) you can import into your app.

If you'd like to use libmpv in your app you can use our code as inspiration.
The important parts are [`MPVLib`](app/src/main/java/is/xyz/mpv/MPVLib.kt), [`BaseMPVView`](app/src/main/java/is/xyz/mpv/BaseMPVView.kt) and the [native code](app/src/main/jni/).
Native code is built by [these scripts](buildscripts/).

## Downloads

You can download mpv-android from the [Releases section](https://github.com/mpv-android/mpv-android/releases) or

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=is.xyz.mpv)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/is.xyz.mpv)

**Note**: Android TV is supported, but only available on F-Droid or by installing the APK manually.

## Building from source

Take a look at the [README](buildscripts/README.md) inside the `buildscripts` directory.

Some other documentation can be found at this [link](http://mpv-android.github.io/mpv-android/).
