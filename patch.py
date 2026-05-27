import re

with open(r'd:\Code\mpv-android\app\src\main\java\is\xyz\mpv\MPVActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

if 'AndroidBridge' in text:
    print("Already patched!")
    exit(0)

# 1. Add imports
imports = '''import android.webkit.*
import org.json.JSONObject
import org.json.JSONArray'''
text = text.replace('import is.xyz.mpv.databinding.PlayerBinding', 'import is.xyz.mpv.databinding.PlayerBinding\n' + imports)

# 2. Add properties to MPVActivity
props = '''
    private var onlineSubtitleUrl: String? = null

    // Remote synchronization state flags to prevent broadcast feedback loops
    private var isRemotePlaySync = false
    private var isRemotePauseSync = false
    private var isRemoteSeekSync = false
    private var isRemoteSpeedSync = false

    private var toast: Toast? = null

    // MPV State for SyncTV Ready Probe
    private var mpvSeeking = false
    private var mpvPausedForCache = false
    private var mpvCacheBufferingState = 100
    private var mpvHasVideoOut = false
    private var mpvEofReached = false
'''
text = text.replace('private var toast: Toast? = null', props)
if 'private var mpvSeeking' not in text:
    text = text.replace('    private var lockedUI = false', props + '\n    private var lockedUI = false')

# 3. Add WebView setup in onCreate
webview_setup = '''
            // WebView setup
            val prefs = getDefaultSharedPreferences(applicationContext)
            val defaultServer = prefs.getString("sync_server", "http://www.monsieursteve.top:9990") ?: "http://www.monsieursteve.top:9990"
            binding.syncServerUrlInput.setText(defaultServer)

            binding.syncWebView.settings.javaScriptEnabled = true
            binding.syncWebView.settings.domStorageEnabled = true
            binding.syncWebView.settings.databaseEnabled = true
            binding.syncWebView.settings.mediaPlaybackRequiresUserGesture = false

            binding.syncWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(getMonkeyPatchJs(), null)
                }
            }
            binding.syncWebView.webChromeClient = WebChromeClient()
            binding.syncWebView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            binding.syncWebView.loadUrl(defaultServer)

            binding.syncServerGoBtn.setOnClickListener {
                val url = binding.syncServerUrlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    var targetUrl = url
                    if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                        targetUrl = "http://$targetUrl"
                    }
                    prefs.edit().putString("sync_server", targetUrl).apply()
                    binding.syncWebView.loadUrl(targetUrl)
                }
            }
'''
if 'syncWebView.settings.javaScriptEnabled' not in text:
    text = text.replace('            updateOrientation(true)', webview_setup + '\n            updateOrientation(true)')

# 4. Add observers
observers = '''
        MPVLib.observeProperty("seeking", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("cache-buffering-state", MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("eof-reached", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("video-out-params", MPVLib.mpvFormat.MPV_FORMAT_NODE)
'''
text = text.replace('MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)', 
                    'MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)\n' + observers)

# 5. Handle observer events
event_ui_bool = '''    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> {
                updatePlaybackStatus(value)
                val isRemote = if (value) {
                    val r = isRemotePauseSync
                    isRemotePauseSync = false
                    r
                } else {
                    val r = isRemotePlaySync
                    isRemotePlaySync = false
                    r
                }
                syncPlayerStateToWebView("pause", value, isRemote)
            }
            "seeking" -> {
                mpvSeeking = value
                syncPlayerStateToWebView("seeking", value)
            }
            "paused-for-cache" -> {
                mpvPausedForCache = value
                syncPlayerStateToWebView("paused-for-cache", value)
            }
            "eof-reached" -> {
                mpvEofReached = value
                syncPlayerStateToWebView("eof-reached", value)
            }
            "mute" -> updateAudioUI()
        }
    }'''
text = re.sub(r'private fun eventPropertyUi\(property: String, value: Boolean\) \{.*?\n    \}', event_ui_bool, text, flags=re.DOTALL)

event_ui_long = '''    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> {
                updatePlaybackPos(psc.positionSec)
                val isRemote = isRemoteSeekSync
                isRemoteSeekSync = false
                syncPlayerStateToWebView("time-pos", psc.positionSec, isRemote)
            }
            "cache-buffering-state" -> {
                mpvCacheBufferingState = value.toInt()
                syncPlayerStateToWebView("cache-buffering-state", value)
            }
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
        }
    }'''
text = re.sub(r'private fun eventPropertyUi\(property: String, value: Long\) \{.*?\n    \}', event_ui_long, text, flags=re.DOTALL)

event_ui_node = '''    override fun eventProperty(property: String, value: MPVNode) {
        if (!activityIsForeground) return
        when (property) {
            "video-out-params" -> {
                mpvHasVideoOut = true
                syncPlayerStateToWebView("has-video-out", true)
            }
        }
    }'''
if 'override fun eventProperty(property: String, value: MPVNode)' not in text:
    text = text.replace('override fun eventProperty(property: String, value: Long) {', event_ui_node + '\n\n    override fun eventProperty(property: String, value: Long) {')

# 6. Add loadVideoWithHeaders, syncPlayerStateToWebView, AndroidBridge and MonkeyPatch
bridge = '''
    private fun syncPlayerStateToWebView(property: String, value: Any, isRemoteSync: Boolean = false) {
        val script = when (property) {
            "pause" -> "if (typeof player !== 'undefined' && typeof player._setPausedNative === 'function') { player._setPausedNative($value, $isRemoteSync); }"
            "time-pos" -> "if (typeof player !== 'undefined' && typeof player._setCurrentTimeNative === 'function') { player._setCurrentTimeNative($value, $isRemoteSync); }"
            "duration/full" -> "if (typeof player !== 'undefined' && typeof player._setDurationNative === 'function') { player._setDurationNative($value); }"
            "speed" -> "if (typeof player !== 'undefined' && typeof player._setSpeedNative === 'function') { player._setSpeedNative($value, $isRemoteSync); }"
            "seeking", "paused-for-cache", "cache-buffering-state", "eof-reached", "has-video-out" -> "if (typeof player !== 'undefined' && typeof player._updateMpvState === 'function') { player._updateMpvState('$property', $value); }"
            else -> null
        }
        script?.let {
            runOnUiThread {
                binding.syncWebView.evaluateJavascript(it, null)
            }
        }
    }

    private fun loadVideoWithHeaders(url: String, mode: String = "replace", title: String? = null) {
        var userAgent = ""
        var referer = ""
        if (url.contains("baidupcs.com") || url.contains("pcs.baidu.com")) {
            userAgent = "pan.baidu.com"
            referer = "" // DO NOT send Referer to match working curl behavior
        } else if (url.contains("bilibili") || url.contains("bilivideo.com") || url.contains("biliapi") || url.contains("upos-") || url.contains("akamaized.net") || url.contains("hdslb.com")) {
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            referer = "https://www.bilibili.com/"
        }
        try {
            val headers = mutableListOf<String>()
            if (userAgent.isNotEmpty()) headers.add("User-Agent: $userAgent")
            if (referer.isNotEmpty()) headers.add("Referer: $referer")

            MPVLib.setPropertyString("user-agent", userAgent)
            MPVLib.setPropertyString("http-header-fields", headers.joinToString(","))
            MPVLib.setPropertyString("force-media-title", title ?: "")

            MPVLib.command(arrayOf("loadfile", url, mode))
        } catch (e: Exception) {
            Log.e(TAG, "loadVideoWithHeaders: failed to execute loadfile command", e)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onVideoSrcChanged(url: String, title: String) {
            runOnUiThread {
                val serverUrl = binding.syncServerUrlInput.text.toString().trim()
                val absoluteUrl = if (url.startsWith("/")) {
                    val base = if (serverUrl.endsWith("/")) serverUrl.substring(0, serverUrl.length - 1) else serverUrl
                    base + url
                } else {
                    url
                }
                onlineSubtitleUrl = null
                loadVideoWithHeaders(absoluteUrl, "replace", title)
            }
        }

        @JavascriptInterface
        fun onVideoPlay(isRemoteSync: Boolean) {
            runOnUiThread {
                if (isRemoteSync) isRemotePlaySync = true
                player.paused = false
            }
        }

        @JavascriptInterface
        fun onVideoPause(isRemoteSync: Boolean) {
            runOnUiThread {
                if (isRemoteSync) isRemotePauseSync = true
                player.paused = true
            }
        }

        @JavascriptInterface
        fun onVideoSeek(seconds: Double, isRemoteSync: Boolean) {
            runOnUiThread {
                if (isRemoteSync) isRemoteSeekSync = true
                player.timePos = seconds
            }
        }

        @JavascriptInterface
        fun onVideoSpeedChange(speed: Double, isRemoteSync: Boolean) {
            runOnUiThread {
                if (isRemoteSync) isRemoteSpeedSync = true
                player.playbackSpeed = speed
            }
        }
        
        @JavascriptInterface
        fun requestMpvState() {
            runOnUiThread {
                syncPlayerStateToWebView("seeking", mpvSeeking)
                syncPlayerStateToWebView("paused-for-cache", mpvPausedForCache)
                syncPlayerStateToWebView("cache-buffering-state", mpvCacheBufferingState)
                syncPlayerStateToWebView("eof-reached", mpvEofReached)
                syncPlayerStateToWebView("has-video-out", mpvHasVideoOut)
            }
        }
        
        @JavascriptInterface
        fun onDanmakuReceived(text: String, color: String) {
            // Placeholder
        }
    }

    private fun getMonkeyPatchJs(): String {
        return """
            (function() {
                // Periodic style application depending on pathname
                function applyStyles() {
                    if (window.location.pathname.indexOf('/room/') !== -1) {
                        if (!document.getElementById('synctv-custom-style')) {
                            var style = document.createElement('style');
                            style.id = 'synctv-custom-style';
                            style.type = 'text/css';
                            style.innerHTML = ' \\
                                .topbar { display: none !important; } \\
                                .theater { display: none !important; } \\
                                .layout { grid-template-columns: 1fr !important; gap: 0 !important; } \\
                                .side { grid-template-columns: 1fr !important; gap: 10px !important; } \\
                                .panel { padding: 12px !important; margin-bottom: 10px !important; } \\
                                ::-webkit-scrollbar { display: none !important; } \\
                            ';
                            document.head.appendChild(style);
                        }
                    } else {
                        var style = document.getElementById('synctv-custom-style');
                        if (style) style.remove();
                    }
                }
                applyStyles();
                setInterval(applyStyles, 500);

                // Periodic player mocking check
                setInterval(function() {
                    var p = document.querySelector('video');
                    if (!p || p._isMocked) return;
                    p._isMocked = true;

                    p._mockCurrentTime = 0;
                    p._mockDuration = 0;
                    p._mockPaused = true;
                    p._syncingFromNative = false;
                    p._mockPlaybackRate = 1.0;
                    
                    p.mpvState = {
                        seeking: false,
                        pausedForCache: false,
                        cacheBufferingState: 100,
                        eofReached: false,
                        hasVideoOut: false
                    };
                    
                    p._updateMpvState = function(key, value) {
                        if (key === 'seeking') this.mpvState.seeking = value;
                        else if (key === 'paused-for-cache') this.mpvState.pausedForCache = value;
                        else if (key === 'cache-buffering-state') this.mpvState.cacheBufferingState = value;
                        else if (key === 'eof-reached') this.mpvState.eofReached = value;
                        else if (key === 'has-video-out') this.mpvState.hasVideoOut = value;
                    };

                    p._setPausedNative = function(paused, isRemoteSync) {
                        this._syncingFromNative = true;
                        this._mockPaused = paused;
                        if (paused) {
                            this.dispatchEvent(new Event('pause'));
                        } else {
                            this.dispatchEvent(new Event('play'));
                            this.dispatchEvent(new Event('playing'));
                        }
                        this._syncingFromNative = false;
                    };

                    p._setCurrentTimeNative = function(time, isRemoteSync) {
                        this._syncingFromNative = true;
                        this._mockCurrentTime = time;
                        this.dispatchEvent(new Event('timeupdate'));
                        this._syncingFromNative = false;
                    };

                    p._setDurationNative = function(duration) {
                        this._mockDuration = duration;
                        this.dispatchEvent(new Event('durationchange'));
                    };

                    p._setSpeedNative = function(speed, isRemoteSync) {
                        this._syncingFromNative = true;
                        this._mockPlaybackRate = speed;
                        this.dispatchEvent(new Event('ratechange'));
                        this._syncingFromNative = false;
                    };

                    var nativeSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                    if (!nativeSrc) {
                        nativeSrc = Object.getOwnPropertyDescriptor(p.constructor.prototype, 'src');
                    }

                    if (nativeSrc && nativeSrc.set) {
                        Object.defineProperty(p, 'src', {
                            get: function() { return this._mockSrc || ""; },
                            set: function(val) {
                                this._mockSrc = val;
                                try {
                                    var title = document.title || "Video";
                                    AndroidBridge.onVideoSrcChanged(val, title);
                                } catch(e) { console.error(e); }
                            }
                        });
                        
                        Object.defineProperty(p, 'currentTime', {
                            get: function() { return this._mockCurrentTime; },
                            set: function(val) {
                                this._mockCurrentTime = val;
                                if (this._syncingFromNative) return;
                                try {
                                    AndroidBridge.onVideoSeek(val, typeof isSyncing !== 'undefined' ? isSyncing : false);
                                } catch(e) {}
                            }
                        });

                        Object.defineProperty(p, 'duration', {
                            get: function() { return this._mockDuration; },
                            set: function() {}
                        });

                        Object.defineProperty(p, 'paused', {
                            get: function() { return this._mockPaused; },
                            set: function() {}
                        });

                        Object.defineProperty(p, 'playbackRate', {
                            get: function() { return this._mockPlaybackRate; },
                            set: function(val) {
                                this._mockPlaybackRate = val;
                                this.dispatchEvent(new Event('ratechange'));
                                if (this._syncingFromNative) return;
                                try {
                                    AndroidBridge.onVideoSpeedChange(val, typeof isSyncing !== 'undefined' ? isSyncing : false);
                                } catch(e) {}
                            }
                        });

                        p.play = function() {
                            this._mockPaused = false;
                            if (this._syncingFromNative) return Promise.resolve();
                            AndroidBridge.onVideoPlay(typeof isSyncing !== 'undefined' ? isSyncing : false);
                            return Promise.resolve();
                        };

                        p.pause = function() {
                            this._mockPaused = true;
                            if (this._syncingFromNative) return;
                            AndroidBridge.onVideoPause(typeof isSyncing !== 'undefined' ? isSyncing : false);
                        };

                        p.load = function() {
                            console.log("Mocked video.load() suppressed.");
                        };
                    }
                }, 500);
            })();
        """.trimIndent()
    }
'''
if 'inner class AndroidBridge' not in text:
    text = text.replace('    companion object {', bridge + '\n    companion object {')

with open(r'd:\Code\mpv-android\app\src\main\java\is\xyz\mpv\MPVActivity.kt', 'w', encoding='utf-8') as f:
    f.write(text)

print("MPVActivity.kt successfully patched!")
