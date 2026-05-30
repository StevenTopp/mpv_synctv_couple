package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.PlayerBinding
import android.webkit.*
import org.json.JSONObject
import org.json.JSONArray
import `is`.xyz.mpv.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.math.roundToInt

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, MPVLib.LogObserver, TouchGesturesObserver {
    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false

    
    private var onlineSubtitleUrl: String? = null
    private var pendingOnlineSubtitleUrl: String? = null
    private var pendingOnlineSubtitleTitle: String? = null
    private var pendingOnlineSubtitleGeneration = 0
    private var appliedOnlineSubtitleUrl: String? = null
    private var appliedOnlineSubtitleGeneration = 0

    // Remote synchronization state flags to prevent broadcast feedback loops
    private var isRemotePlaySync = false
    private var isRemotePauseSync = false
    private var isRemoteSeekSync = false
    private var isRemoteSpeedSync = false
    private var isProgrammaticSeek = false
    private var pendingProgrammaticSeekFinish = false

    private var toast: Toast? = null
    private var pendingLocalFileUrl: String? = null
    private var webViewFilePathCallback: ValueCallback<Array<Uri>>? = null

    // Manual audio compatibility toggle (forces stereo downmix for multi-channel AAC freeze)
    private var isAudioCompatEnabled = false

    // MPV State for SyncTV Ready Probe
    private var mpvSeeking = false
    private var mpvPausedForCache = false
    private var mpvCacheBufferingState = 100
    private var mpvHasVideoOut = false
    private var mpvEofReached = false

    // Tracking variables for SyncTV throttling and snapshots
    private var activeSeekId: String? = null
    private var lastTimePosLogTime: Long = 0L
    private var lastCacheLogTime: Long = 0L
    private var lastDumpProbeLogTime: Long = 0L
    private var lastMediaSessionUpdateTime: Long = 0L
    private var lastTimePosUiUpdateTime: Long = 0L

    // Android MPV Buffer Probe for Remote Seek Sync
    private var currentLoadedUrl: String? = null
    private var mediaLoadGeneration = 0
    private var startedMediaGeneration = 0
    private var isBufferProbeActive = false
    private var bufferProbeGeneration = 0
    private var bufferProbeTarget: Double? = null
    private var originalMuteState = false

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isTouchOutsidePanelStarted = false

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    private val psc = Utils.PlaybackStateCache()
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            val seconds = progress.toDouble() / SEEK_BAR_PRECISION
            player.timePos = seconds
            // Update the native time text immediately as the user drags/clicks to show progress in real time
            binding.playbackPositionTxt.text = Utils.prettyTime(seconds.toInt())
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            prevBtn.setOnClickListener { playlistPrev() }
            nextBtn.setOnClickListener { playlistNext() }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            playBtn.setOnClickListener { player.cyclePause() }
            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            topLockBtn.setOnClickListener { lockUI() }
            topPiPBtn.setOnClickListener { goIntoPiP() }
            topMenuBtn.setOnClickListener { openTopMenu() }
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }
            prevBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            nextBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)

            topSyncBtn.setOnClickListener {
                if (syncPanel.visibility == View.VISIBLE) {
                    syncPanel.visibility = View.GONE
                } else {
                    syncPanel.visibility = View.VISIBLE
                    showControls()
                }
                updateControlsMargins()
            }

            // WebView setup
            val prefs = getDefaultSharedPreferences(applicationContext)
            val defaultServer = prefs.getString("sync_server", "http://www.monsieursteve.top:9990") ?: "http://www.monsieursteve.top:9990"
            syncServerUrlInput.setText(defaultServer)

            syncWebView.settings.javaScriptEnabled = true
            syncWebView.settings.domStorageEnabled = true
            syncWebView.settings.databaseEnabled = true
            syncWebView.settings.mediaPlaybackRequiresUserGesture = false

            syncWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(getMonkeyPatchJs(), null)
                    syncThemeToWebView()
                }
            }
            syncWebView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d("WebConsole", "[${it.messageLevel()}] ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (webViewFilePathCallback != null) {
                        webViewFilePathCallback?.onReceiveValue(null)
                        webViewFilePathCallback = null
                    }
                    webViewFilePathCallback = filePathCallback

                    val isSub = fileChooserParams?.acceptTypes?.any {
                        it.contains("srt") || it.contains("vtt") || it.contains("ass") || it.contains("ssa") || it.contains("subtitle")
                    } ?: false

                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        if (isSub) {
                            type = "*/*"
                            val mimeTypes = arrayOf(
                                "text/plain",
                                "application/octet-stream",
                                "application/x-subrip",
                                "text/vtt"
                            )
                            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                        } else {
                            type = "video/*"
                            val mimeTypes = arrayOf("video/*", "application/octet-stream")
                            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                        }
                    }

                    try {
                        activityResultCallbacks[RCODE_WEB_FILE_CHOOSER] = { resultCode: Int, intentData: Intent? ->
                            var results: Array<Uri>? = null
                            if (resultCode == RESULT_OK && intentData != null) {
                                var selectedUri: Uri? = null
                                val clipData = intentData.clipData
                                val dataString = intentData.dataString
                                if (clipData != null && clipData.itemCount > 0) {
                                    selectedUri = clipData.getItemAt(0).uri
                                } else if (dataString != null) {
                                    selectedUri = Uri.parse(dataString)
                                } else if (intentData.data != null) {
                                    selectedUri = intentData.data
                                }

                                if (selectedUri != null) {
                                    results = arrayOf(selectedUri)
                                    pendingLocalFileUrl = resolveUri(selectedUri)
                                }
                            }
                            webViewFilePathCallback?.onReceiveValue(results)
                            webViewFilePathCallback = null
                        }
                        startActivityForResult(intent, RCODE_WEB_FILE_CHOOSER)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open file chooser", e)
                        webViewFilePathCallback?.onReceiveValue(null)
                        webViewFilePathCallback = null
                        return false
                    }
                    return true
                }
            }
            syncWebView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            syncWebView.loadUrl(defaultServer)

            syncServerGoBtn.setOnClickListener {
                val url = syncServerUrlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    var targetUrl = url
                    if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                        targetUrl = "http://$targetUrl"
                    }
                    prefs.edit().putString("sync_server", targetUrl).apply()
                    syncWebView.loadUrl(targetUrl)
                }
            }

            syncServerUrlInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    val url = syncServerUrlInput.text.toString().trim()
                    if (url.isNotEmpty()) {
                        var targetUrl = url
                        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                            targetUrl = "http://$targetUrl"
                        }
                        prefs.edit().putString("sync_server", targetUrl).apply()
                        syncWebView.loadUrl(targetUrl)
                    }
                    true
                } else {
                    false
                }
            }
        }

        player.setOnTouchListener { _, e ->
            if (lockedUI) false else gestures.onTouchEvent(e)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { v, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = insets.left
                topMargin = insets.top
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Do these here and not in MainActivity because mpv can be launched from a file browser
        Utils.copyAssets(this)
        BackgroundPlaybackService.createNotificationChannel(this)

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init controls to be hidden and view fullscreen
        hideControls()

        // Initialize listeners for the player view
        initListeners()

        gestures = TouchGestures(this)

        // set up initial UI state
        readSettings()
        onConfigurationChanged(resources.configuration)
        run {
            // edge-to-edge & immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.controlsTitleGroup.visibility = View.VISIBLE

        updateOrientation(true)

        // Parse the intent
        val filepath = parsePathFromIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            parseIntentExtras(intent.extras)
        }

        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finishWithResult(RESULT_CANCELED)
            return
        }

        player.addObserver(this)
        MPVLib.addLogObserver(this)
        player.initialize(filesDir.path, cacheDir.path)
        if (filepath == "synctv://") {
            MPVLib.setOptionString("idle", "yes")
            binding.syncPanel.visibility = View.VISIBLE
            showControls()
            updateControlsMargins()
        } else {
            player.playFile(filepath)
        }

        mediaSession = initMediaSession()
        updateMediaSession()
        BackgroundPlaybackService.mediaToken = mediaSession?.sessionToken

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            Log.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false) {
        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing) // only count first call
            return
        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }
        setResult(code, result)
        finish()
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        // Suppress any further callbacks
        activityIsForeground = false

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        BackgroundPlaybackService.mediaToken = null
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        MPVLib.removeLogObserver(this)
        player.removeObserver(this)
        player.destroy()
        binding.syncWebView.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        Log.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }



        val host = try { Uri.parse(filepath).host ?: "" } catch (e: Exception) { "" }
        Log.d(SYNC_TAG, "[loadfile] urlHost=$host mode=replace")
        val isBaiduPcs = filepath.contains("baidupcs.com") || filepath.contains("pcs.baidu.com")
        Log.d(SYNC_TAG, "[headers] isBaiduPcs=$isBaiduPcs uaSet=false refererCleared=true")

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            if (this.newIntentReplace) {
                MPVLib.command(arrayOf("loadfile", filepath, "replace"))
                showToast(getString(R.string.notice_file_play))
            } else {
                MPVLib.command(arrayOf("loadfile", filepath, "append"))
                showToast(getString(R.string.notice_file_appended))
            }
            moveTaskToBack(true)
        } else {
            MPVLib.command(arrayOf("loadfile", filepath))
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                Log.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        val fmt = MPVLib.getPropertyString("video-format")
        val shouldBackground = shouldBackground()
        if (shouldBackground && !fmt.isNullOrEmpty())
            BackgroundPlaybackService.thumbnail = MPVLib.grabThumbnail(THUMB_SIZE)
        else
            BackgroundPlaybackService.thumbnail = null
        // media session uses the same thumbnail
        updateMediaSession()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            savePosition()
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        gestures.syncSettings(prefs, resources)

        val statsMode = prefs.getString("stats_mode", "") ?: ""
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toInt()
        else
            0
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.noUIPauseMode = getString("no_ui_pause", R.string.pref_no_ui_pause_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)
        this.controlsAtBottom = prefs.getBoolean("bottom_controls", true)
        this.showMediaTitle = prefs.getBoolean("display_media_title", false)
        this.useTimeRemaining = prefs.getBoolean("use_time_remaining", false)
        this.ignoreAudioFocus = prefs.getBoolean("ignore_audio_focus", false)
        this.playlistExitWarning = prefs.getBoolean("playlist_exit_warning", true)
        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
    }

    private fun writeSettings() {
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            putBoolean("use_time_remaining", useTimeRemaining)
            commit()
        }
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true
    }

    override fun onResume() {
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        if (lockedUI) { // precaution
            Log.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()

        syncThemeToWebView()
        super.onResume()
    }

    private fun savePosition() {
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            Log.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        MPVLib.command(arrayOf("write-watch-later-config"))
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        Log.v(TAG, "Audio focus changed: $type ($source)")
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        val useKeepOpen = when (noUIPauseMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
        if (useKeepOpen) {
            // don't pause but set keep-open so mpv doesn't exit while the user is doing stuff
            val oldValue = MPVLib.getPropertyString("keep-open")
            MPVLib.setPropertyBoolean("keep-open", true)
            return {
                oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
            }
        }

        // Pause playback during UI dialogs
        val wasPlayerPaused = player.paused ?: true
        player.paused = true
        return {
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        return useAudioUI || btnSelected != -1 || userIsOperatingSeekbar
    }

    /** Make controls visible, also controls the timeout until they fade. */
    private fun showControls() {
        if (lockedUI) {
            Log.w(TAG, "cannot show UI in locked mode")
            return
        }

        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().cancel()
        binding.topControls.animate().cancel()

        // reset controls alpha to be visible
        binding.controls.alpha = 1f
        binding.topControls.alpha = 1f

        if (binding.controls.visibility != View.VISIBLE) {
            binding.controls.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        // add a new callback to hide the controls once again
        if (!controlsShouldBeVisible())
            fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        if (binding.syncPanel.visibility == View.VISIBLE) {
            val location = IntArray(2)
            binding.syncPanel.getLocationOnScreen(location)
            val panelLeft = location[0]
            
            if (ev.action == MotionEvent.ACTION_DOWN) {
                if (ev.rawX < panelLeft) {
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    isTouchOutsidePanelStarted = true
                    return true
                } else {
                    isTouchOutsidePanelStarted = false
                }
            } else if (isTouchOutsidePanelStarted) {
                if (ev.action == MotionEvent.ACTION_UP) {
                    val dx = ev.rawX - touchStartX
                    val dy = ev.rawY - touchStartY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    isTouchOutsidePanelStarted = false
                    if (dist < 15f) {
                        binding.syncPanel.visibility = View.GONE
                        updateControlsMargins()
                    }
                }
                return true
            }
        }

        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            // ideally we'd want to know if the event was delivered to controls, but we can't
            if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted)
                showControls()
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls) {
            toggleControls()
        }
        return true
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsButtonGroup, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides a default binding)
            'j' -> cycleSub()
            '#' -> cycleAudio()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> player.cyclePause()

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> player.cyclePause()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            create().show()
        }
    }

    private fun updateControlsMargins() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val syncPanelVisible = binding.syncPanel.visibility == View.VISIBLE

        binding.syncPanel.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (isLandscape) {
                Utils.convertDp(this@MPVActivity, 340f)
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
        }

        val extraRightMargin = if (syncPanelVisible && isLandscape) Utils.convertDp(this, 340f) else 0

        binding.controls.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = if (!controlsAtBottom) Utils.convertDp(this@MPVActivity, 60f) else 0
            leftMargin = if (!controlsAtBottom) Utils.convertDp(this@MPVActivity, if (isLandscape) 60f else 24f) else 0
            rightMargin = leftMargin + extraRightMargin
        }

        binding.topControls.updateLayoutParams<MarginLayoutParams> {
            rightMargin = extraRightMargin
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager.currentWindowMetrics
            gestures.setMetrics(wm.bounds.width().toFloat(), wm.bounds.height().toFloat())
        } else @Suppress("DEPRECATION") {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            gestures.setMetrics(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }

        // Adjust control margins dynamically
        updateControlsMargins()
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        Log.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true)
        }
    }

    private fun playlistPrev() = MPVLib.command(arrayOf("playlist-prev"))
    private fun playlistNext() = MPVLib.command(arrayOf("playlist-next"))

    private fun showToast(msg: String, cancel: Boolean = false) {
        if (cancel)
            toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null && u.isHierarchical && !u.isRelative)
                resolveUri(u)
            else null
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }

            Intent.ACTION_SEND -> {
                // Handle single shared file or text link
                var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (parsed == null) {
                    parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        Uri.parse(it.trim())
                    }
                }

                safeResolveUri(parsed)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple shared files
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    val paths = uris.mapNotNull { uri ->
                        safeResolveUri(uri)
                    }
                    if (paths.size == 1) {
                        return paths[0]
                    } else if (!paths.isEmpty()) {
                        // Use a memory playlist
                        val memoryUri = "memory://#EXTM3U\n${paths.joinToString("\n")}\n"
                        Log.v(TAG, "Created memory playlist URI (${paths.size})")
                        return memoryUri
                    }
                }
                return null
            }

            else -> {
                // Custom intent from MainScreenFragment
                intent.getStringExtra("filepath")
            }
        }
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> translateContentUri(data)
            // mpv supports data URIs but needs data:// to pass it through correctly
            "data" -> "data://${data.schemeSpecificPart}"
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh",
            "tcp", "udp", "lavf", "ftp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                if (path != null) {
                    Log.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        fun pushOption(key: String, value: String) {
            onloadCommands.add(arrayOf("set", "file-local-options/${key}", value))
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // Note: these only apply to the first file, it's not clear what the semantics for a
        // playlist should be.

        if (extras.getByte("decode_mode") == 2.toByte())
            pushOption("hwdec", "no")
        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        extras.getInt("position", 0).let {
            if (it > 0)
                pushOption("start", "${it / 1000f}")
        }
        extras.getString("title", "").let {
            if (!it.isNullOrEmpty())
                pushOption("force-media-title", it)
        }
        // TODO: `headers` would be good, maybe `tls_verify`
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        showToast(msg, true)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub(); TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks.getValue(type)
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
        val restore = pauseForDialog()
        val impl = SubTrackDialog(player)
        lateinit var dialog: AlertDialog
        impl.listener = { it, secondary ->
            if (secondary)
                player.secondarySid = it.mpvId
            else
                player.sid = it.mpvId
            dialog.dismiss()
            trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun openPlaylistMenu(restore: StateRestoreCallback) {
        val impl = PlaylistDialog(player)
        lateinit var dialog: AlertDialog

        impl.listeners = object : PlaylistDialog.Listeners {
            private fun openFilePicker(skip: Int) {
                openFilePickerFor(RCODE_LOAD_FILE, "", skip) { result, data ->
                    if (result == RESULT_OK) {
                        val path = data!!.getStringExtra("path")!!
                        MPVLib.command(arrayOf("loadfile", path, "append"))
                        impl.refresh()
                    }
                }
            }
            override fun pickFile() = openFilePicker(FilePickerActivity.FILE_PICKER)

            override fun openUrl() {
                val helper = Utils.OpenUrlDialog(this@MPVActivity)
                with (helper) {
                    builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                        MPVLib.command(arrayOf("loadfile", helper.text, "append"))
                        impl.refresh()
                    }
                    builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                    create().show()
                }
            }

            override fun onItemPicked(item: MPVView.PlaylistItem) {
                MPVLib.setPropertyInt("playlist-pos", item.index)
                dialog.dismiss()
            }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun pickDecoder() {
        val restore = pauseForDialog()

        val items = mutableListOf(
            Pair("HW (mediacodec-copy)", "mediacodec-copy"),
            Pair("SW", "no")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))
        val hwdecActive = player.hwdecActive
        val selectedIndex = items.indexOfFirst { it.second == hwdecActive }
        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex ) { dialog, idx ->
                MPVLib.setPropertyString("hwdec", items[idx].second)
                dialog.dismiss()
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed") {
            restore()
        }
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
        showControls()
    }

    data class MenuItem(@IdRes val idRes: Int, val handler: () -> Boolean)
    private fun genericMenu(
            @LayoutRes layoutRes: Int, buttons: List<MenuItem>, hiddenButtons: Set<Int>,
            restoreState: StateRestoreCallback, postInflate: ((View) -> Unit)? = null) {
        lateinit var dialog: AlertDialog

        val builder = AlertDialog.Builder(this)
        val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)

        for (button in buttons) {
            val buttonView = dialogView.findViewById<Button>(button.idRes)
            buttonView.setOnClickListener {
                val ret = button.handler()
                if (ret) // restore state immediately
                    restoreState()
                dialog.dismiss()
            }
        }

        hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

        postInflate?.invoke(dialogView)

        if (Utils.visibleChildren(dialogView) == 0) {
            Log.w(TAG, "Not showing menu because it would be empty")
            restoreState()
            return
        }

        Utils.handleInsetsAsPadding(dialogView)

        with (builder) {
            setView(dialogView)
            setOnCancelListener { restoreState() }
            dialog = create()
        }
        dialog.show()
    }

    private fun openTopMenu() {
        val restoreState = pauseForDialog()

        fun addExternalThing(cmd: String, result: Int, data: Intent?) {
            if (result != RESULT_OK)
                return
            // file picker may return a content URI or a bare file path
            val path = data!!.getStringExtra("path")!!
            val path2 = if (path.startsWith("content://"))
                translateContentUri(Uri.parse(path))
            else
                path
            if (cmd == "sub-add")
                addSubtitleTrack(path2, "cached", "menu-file")
            else
                MPVLib.command(arrayOf(cmd, path2, "cached"))
        }

        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.audioBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                        addExternalThing("audio-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.compatBtn) {
                    isAudioCompatEnabled = !isAudioCompatEnabled
                    if (isAudioCompatEnabled) {
                        MPVLib.setPropertyString("audio-channels", "stereo")
                        MPVLib.setPropertyString("ao", "opensles")
                        showToast("音频兼容: 开 (立体声 + OpenSL ES)")
                    } else {
                        MPVLib.setPropertyString("audio-channels", "stereo")
                        MPVLib.setPropertyString("ao", "audiotrack,opensles")
                        showToast("音频兼容: 关 (自动默认)")
                    }
                    // Force audio output and track re-initialization to apply driver switch immediately
                    MPVLib.command(arrayOf("ao-reload"))
                    val currentAid = player.aid
                    if (currentAid != -1) {
                        player.aid = -1
                        eventUiHandler.postDelayed({
                            runOnUiThread {
                                player.aid = currentAid
                            }
                        }, 200)
                    }
                    // Broadcast to other MPV clients in the room
                    val js = "if(window.ws&&ws.readyState===1){ws.send(JSON.stringify({type:'audio-compat',enabled:${isAudioCompatEnabled}}))}" 
                    binding.syncWebView.evaluateJavascript(js, null)
                    val aoName = if (isAudioCompatEnabled) "opensles" else "audiotrack,opensles"
                    Log.d(SYNC_TAG, "[audio-compat] toggled to $isAudioCompatEnabled, ao=$aoName, broadcast sent")
                    true
                },
                MenuItem(R.id.subBtn) {
                    val subUrl = onlineSubtitleUrl
                    if (!subUrl.isNullOrEmpty()) {
                        appliedOnlineSubtitleGeneration = 0
                        applyOnlineSubtitleIfReady(mediaLoadGeneration, "menu")
                        restoreState()
                    } else {
                        openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                            addExternalThing("sub-add", result, data)
                            restoreState()
                        }
                    }; false
                },
                MenuItem(R.id.playlistBtn) {
                    openPlaylistMenu(restoreState); false
                },
                MenuItem(R.id.backgroundBtn) {
                    // restoring state may (un)pause so do that first
                    restoreState()
                    backgroundPlayMode = "always"
                    player.paused = false
                    moveTaskToBack(true)
                    false
                },
                MenuItem(R.id.chapterBtn) {
                    val chapters = player.loadChapters()
                    if (chapters.isEmpty())
                        return@MenuItem true
                    val chapterArray = chapters.map {
                        val timecode = Utils.prettyTime(it.time.roundToInt())
                        if (!it.title.isNullOrEmpty())
                            getString(R.string.ui_chapter, it.title, timecode)
                        else
                            getString(R.string.ui_chapter_fallback, it.index+1, timecode)
                    }.toTypedArray()
                    val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
                    with (AlertDialog.Builder(this)) {
                        setSingleChoiceItems(chapterArray, selectedIndex) { dialog, item ->
                            MPVLib.setPropertyInt("chapter", chapters[item].index)
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
                MenuItem(R.id.chapterPrev) {
                    MPVLib.command(arrayOf("add", "chapter", "-1")); true
                },
                MenuItem(R.id.chapterNext) {
                    MPVLib.command(arrayOf("add", "chapter", "1")); true
                },
                MenuItem(R.id.advancedBtn) { openAdvancedMenu(restoreState); false },
                MenuItem(R.id.orientationBtn) {
                    autoRotationMode = "manual"
                    cycleOrientation()
                    true
                }
        )

        if (!isPlayingAudio)
            hiddenButtons.add(R.id.backgroundBtn)
        if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
            hiddenButtons.add(R.id.rowChapter)
        /******/

        genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, restoreState) { dialogView ->
            // Dynamically set compatBtn text to reflect current state
            dialogView.findViewById<Button>(R.id.compatBtn)?.text =
                if (isAudioCompatEnabled) "音频兼容: 开" else "音频兼容: 关"
        }
    }

    private fun genericPickerDialog(
        picker: PickerDialog, @StringRes titleRes: Int, property: String,
        restoreState: StateRestoreCallback
    ) {
        val dialog = with(AlertDialog.Builder(this)) {
            setTitle(titleRes)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                picker.number?.let {
                    if (picker.isInteger())
                        MPVLib.setPropertyInt(property, it.toInt())
                    else
                        MPVLib.setPropertyDouble(property, it)
                }
            }
            setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            setOnDismissListener { restoreState() }
            create()
        }

        picker.number = MPVLib.getPropertyDouble(property)
        dialog.show()
    }

    private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.subSeekPrev) {
                    MPVLib.command(arrayOf("sub-seek", "-1")); true
                },
                MenuItem(R.id.subSeekNext) {
                    MPVLib.command(arrayOf("sub-seek", "1")); true
                },
                MenuItem(R.id.statsBtn) {
                    MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle")); true
                },
                MenuItem(R.id.aspectBtn) {
                    val ratios = resources.getStringArray(R.array.aspect_ratios)
                    with (AlertDialog.Builder(this)) {
                        setItems(R.array.aspect_ratio_names) { dialog, item ->
                            if (ratios[item] == "panscan") {
                                MPVLib.setPropertyString("video-aspect-override", "-1")
                                MPVLib.setPropertyDouble("panscan", 1.0)
                            } else {
                                MPVLib.setPropertyString("video-aspect-override", ratios[item])
                                MPVLib.setPropertyDouble("panscan", 0.0)
                            }
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
        )

        val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
        for (i in 1..3) {
            buttons.add(MenuItem(statsButtons[i-1]) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-$i")); true
            })
        }

        // contrast, brightness and others get a -100 to 100 slider
        val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
        val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
        val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
        basicIds.forEachIndexed { index, id ->
            buttons.add(MenuItem(id) {
                val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
                genericPickerDialog(slider, basicTitles[index], basicProps[index], restoreState)
                false
            })
        }

        // audio / sub delay get a decimal picker
        buttons.add(MenuItem(R.id.audioDelayBtn) {
            val picker = DecimalPickerDialog(-600.0, 600.0)
            genericPickerDialog(picker, R.string.audio_delay, "audio-delay", restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subDelayBtn) {
            val picker = SubDelayDialog(-600.0, 600.0)
            val dialog = with(AlertDialog.Builder(this)) {
                setTitle(R.string.sub_delay)
                val inflater = LayoutInflater.from(context)
                setView(picker.buildView(inflater))
                setPositiveButton(R.string.dialog_ok) { _, _ ->
                    picker.delay1?.let { player.subDelay = it }
                    picker.delay2?.let { player.secondarySubDelay = it }
                }
                setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                setOnDismissListener { restoreState() }
                create()
            }

            picker.delay1 = player.subDelay ?: 0.0
            picker.delay2 = if (player.secondarySid != -1) player.secondarySubDelay else null
            dialog.show()
            false
        })

        if (player.vid == -1)
            hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
        if (player.aid == -1 || player.vid == -1)
            hiddenButtons.add(R.id.audioDelayBtn)
        if (player.sid == -1)
            hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek))
        /******/

        genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, restoreState)
    }

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, title: String, skip: Int?, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("allow_document", true)
        skip?.let { intent.putExtra("skip", it) }
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path") ?: ""
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }
    private fun openFilePickerFor(requestCode: Int, @StringRes titleRes: Int, callback: ActivityResultCallback) {
        openFilePickerFor(requestCode, getString(titleRes), null, callback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateOrientation()
        updateMetadataDisplay()
        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.v(TAG, "Audio UI: $useAudioUI")

        val seekbarGroup = binding.controlsSeekbarGroup
        val buttonGroup = binding.controlsButtonGroup

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(seekbarGroup, R.id.prevBtn, buttonGroup, 0)
            Utils.viewGroupMove(seekbarGroup, R.id.nextBtn, buttonGroup, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(buttonGroup, audioButtons)

            // Show song title and more metadata
            binding.controlsTitleGroup.visibility = View.VISIBLE
            Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.titleTextView, R.id.minorTitleTextView))
            updateMetadataDisplay()

            showControls()
        } else {
            Utils.viewGroupMove(buttonGroup, R.id.prevBtn, seekbarGroup, 0)
            Utils.viewGroupMove(buttonGroup, R.id.nextBtn, seekbarGroup, -1)

            Utils.viewGroupReorder(buttonGroup, videoButtons)

            // Show title only depending on settings
            if (showMediaTitle) {
                binding.controlsTitleGroup.visibility = View.VISIBLE
                Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.fullTitleTextView))
                updateMetadataDisplay()
            } else {
                binding.controlsTitleGroup.visibility = View.GONE
            }

            hideControls() // do NOT use fade runnable
        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            binding.titleTextView.text = psc.meta.formatTitle()
            binding.minorTitleTextView.text = psc.meta.formatArtistAlbum()
        }
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position * SEEK_BAR_PRECISION

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration * SEEK_BAR_PRECISION
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (paused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateDecoderButton() {
        binding.cycleDecoderBtn.text = when (player.hwdecActive) {
            "mediacodec" -> "HW+"
            "no" -> "SW"
            else -> "HW"
        }
    }

    private fun updateSpeedButton() {
        binding.cycleSpeedBtn.text = getString(R.string.ui_speed, psc.speed)
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount
        val plPos = psc.playlistPos

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            binding.prevBtn.visibility = View.GONE
            binding.nextBtn.visibility = View.GONE
            return
        }
        binding.prevBtn.visibility = View.VISIBLE
        binding.nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        binding.prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        binding.nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun updateOrientation(initial: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        val ratio = player.getVideoAspect()?.toFloat() ?: 0f
        if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            player.paused = true
        }
        override fun onPlay() {
            player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
            https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> player.loadTracks()
            "current-tracks/audio/selected", "current-tracks/video/image" -> updateAudioUI()
            "hwdec-current" -> updateDecoderButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=pause value=$value")
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
                dumpSeekProbeState("pause")
            }
            "seeking" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=seeking value=$value")
                mpvSeeking = value
                val wasProgrammatic = isProgrammaticSeek
                if (!value) {
                    // Query exact time-pos synchronously from MPV to prevent stale/truncated currentTime inside the WebView seeked event
                    val exactTime = player.timePos ?: (psc.position.toDouble() / 1000.0)
                    Log.d(SYNC_TAG, "[mpv-state] seeking finished, sending exact time-pos = $exactTime")
                    syncPlayerStateToWebView("time-pos", exactTime)
                    if (isBufferProbeActive) {
                        pendingProgrammaticSeekFinish = wasProgrammatic
                        Log.d(SYNC_TAG, "[programmatic-seek] defer-finish reason=buffer-probe-active")
                        dumpSeekProbeState("seeking")
                        return
                    }
                }
                syncPlayerStateToWebView("seeking", value, wasProgrammatic)
                if (!value && !isBufferProbeActive) {
                    isProgrammaticSeek = false
                    pendingProgrammaticSeekFinish = false
                    Log.d(SYNC_TAG, "[programmatic-seek] clear")
                }
                dumpSeekProbeState("seeking")
            }
            "paused-for-cache" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=paused-for-cache value=$value")
                mpvPausedForCache = value
                syncPlayerStateToWebView("paused-for-cache", value)
                dumpSeekProbeState("paused-for-cache")
            }
            "eof-reached" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=eof-reached value=$value")
                mpvEofReached = value
                syncPlayerStateToWebView("eof-reached", value)
                dumpSeekProbeState("eof-reached")
            }
            "vo-configured" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=vo-configured value=$value")
                mpvHasVideoOut = value
                syncPlayerStateToWebView("has-video-out", value)
                dumpSeekProbeState("vo-configured")
                if (value) {
                    updateOrientation()
                }
            }
            "mute" -> updateAudioUI()
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "cache-buffering-state" -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCacheLogTime >= 250) {
                    lastCacheLogTime = now
                    Log.d(SYNC_TAG, "[mpv-state] property=cache-buffering-state value=$value")
                }
                mpvCacheBufferingState = value.toInt()
                syncPlayerStateToWebView("cache-buffering-state", value)
                dumpSeekProbeState("cache-buffering-state")
            }
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> {
                val now = SystemClock.elapsedRealtime()
                val isRemote = isRemoteSeekSync
                val inSeekSync = activeSeekId != null

                if (isRemote || inSeekSync || now - lastTimePosUiUpdateTime >= 250L) {
                    lastTimePosUiUpdateTime = now
                    if (now - lastTimePosLogTime >= 250L) {
                        lastTimePosLogTime = now
                        Log.d(SYNC_TAG, "[mpv-state] property=time-pos value=$value")
                    }
                    updatePlaybackPos(psc.positionSec)
                    isRemoteSeekSync = false
                    val exactTime = value
                    syncPlayerStateToWebView("time-pos", exactTime, isRemote)
                    dumpSeekProbeState("time-pos")
                }
            }
            "duration/full" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=duration/full value=${psc.durationSec}")
                updatePlaybackDuration(psc.durationSec)
                syncPlayerStateToWebView("duration/full", psc.durationSec)
                dumpSeekProbeState("duration/full")
            }
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "speed" -> {
                Log.d(SYNC_TAG, "[mpv-state] property=speed value=$value")
                updateSpeedButton()
                val isRemote = isRemoteSpeedSync
                isRemoteSpeedSync = false
                syncPlayerStateToWebView("speed", value, isRemote)
                dumpSeekProbeState("speed")
            }
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        // empty
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        val updated = psc.update(property, value)
        if (updated) {
            if (property == "time-pos") {
                val now = SystemClock.elapsedRealtime()
                if (now - lastMediaSessionUpdateTime >= 500L) {
                    lastMediaSessionUpdateTime = now
                    updateMediaSession()
                }
            } else {
                updateMediaSession()
            }
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        val updated = psc.update(property, value)
        if (updated) {
            if (property == "time-pos") {
                val now = SystemClock.elapsedRealtime()
                if (now - lastMediaSessionUpdateTime >= 500L) {
                    lastMediaSessionUpdateTime = now
                    updateMediaSession()
                }
            } else {
                updateMediaSession()
            }
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            Log.d(SYNC_TAG, "[mpv-event] END_FILE playbackHasStarted=$playbackHasStarted path=${syncUrlSummary(MPVLib.getPropertyString("path") ?: "")}")
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN) {
            Log.d(SYNC_TAG, "[mpv-event] SHUTDOWN playbackHasStarted=$playbackHasStarted syncPanelVisible=${binding.syncPanel.visibility == View.VISIBLE}")
            finishWithResult(if (playbackHasStarted) RESULT_OK else RESULT_CANCELED)
        }

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            startedMediaGeneration = mediaLoadGeneration
            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)
            applyOnlineSubtitleIfReady(startedMediaGeneration, "start-file")
            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }

            playbackHasStarted = true
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO ||
            prefix.contains("ffmpeg", ignoreCase = true) ||
            prefix.contains("stream", ignoreCase = true) ||
            prefix.contains("demux", ignoreCase = true)
        ) {
            Log.d(SYNC_TAG, "[mpv-log][$prefix][$level] ${text.trim()}")
        }
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeek = 0

    private fun fadeGestureText() {
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                pausedForSeek = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return
                if (smoothSeekGesture && pausedForSeek == 0) {
                    pausedForSeek = if (psc.pause) 2 else 1
                    if (pausedForSeek == 1)
                        player.paused = true
                }

                val newPosExact = (initialSeek + diff).coerceIn(0f, duration)
                val newPos = newPosExact.roundToInt()
                val newDiff = (newPosExact - initialSeek).roundToInt()
                if (smoothSeekGesture) {
                    player.timePos = newPosExact.toDouble() // (exact seek)
                } else {
                    // seek faster than assigning to timePos but less precise
                    MPVLib.command(arrayOf("seek", "$newPosExact", "absolute+keyframes"))
                }
                // Note: don't call updatePlaybackPos() here because mpv will seek a timestamp
                // actually present in the file, and not the exact one we specified.

                val posText = Utils.prettyTime(newPos)
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val lp = window.attributes
                val newBright = (initialBright + diff).coerceIn(0f, 1f)
                lp.screenBrightness = newBright
                window.attributes = lp

                gestureTextView.text = getString(R.string.ui_brightness, (newBright * 100).roundToInt())
            }
            PropertyChange.Finalize -> {
                if (pausedForSeek == 1)
                    player.paused = false
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> player.cyclePause()
            PropertyChange.Custom -> {
                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }


    private fun syncUrlSummary(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: ""
            val path = uri.encodedPath ?: ""
            val queryKeys = uri.queryParameterNames.joinToString(",")
            "host=$host path=$path queryKeys=$queryKeys"
        } catch (e: Exception) {
            "urlHash=${url.hashCode()}"
        }
    }

    private fun dumpSeekProbeState(reason: String) {
        if (activeSeekId == null) return
        val now = SystemClock.elapsedRealtime()
        if (reason == "time-pos" || reason == "cache-buffering-state") {
            if (now - lastDumpProbeLogTime < 250) return
        }
        lastDumpProbeLogTime = now
        Log.d(SYNC_TAG, "[seek-probe] seekId=${activeSeekId ?: "none"} reason=$reason seeking=$mpvSeeking pausedForCache=$mpvPausedForCache voConfigured=$mpvHasVideoOut cache=$mpvCacheBufferingState timePos=${psc.positionSec}")
    }

    private fun syncPlayerStateToWebView(property: String, value: Any, isRemoteSyncOrProgrammatic: Boolean = false) {
        if (isBufferProbeActive) {
            if (property == "pause" || property == "time-pos" || property == "seeking" || property == "paused-for-cache") {
                Log.d("MPVActivity", "[buffer-probe-suppressed] property=$property value=$value")
                return
            }
        }
        // Log.d(SYNC_TAG, "[android->web] property=$property value=$value isRemoteSync=$isRemoteSyncOrProgrammatic")
        val script = when (property) {
            "pause" -> "if (typeof player !== 'undefined' && typeof player._setPausedNative === 'function') { player._setPausedNative($value, $isRemoteSyncOrProgrammatic); }"
            "time-pos" -> "if (typeof player !== 'undefined' && typeof player._setCurrentTimeNative === 'function') { player._setCurrentTimeNative($value, $isRemoteSyncOrProgrammatic); }"
            "duration/full" -> "if (typeof player !== 'undefined' && typeof player._setDurationNative === 'function') { player._setDurationNative($value); }"
            "speed" -> "if (typeof player !== 'undefined' && typeof player._setSpeedNative === 'function') { player._setSpeedNative($value, $isRemoteSyncOrProgrammatic); }"
            "seeking" -> "if (typeof player !== 'undefined' && typeof player._updateMpvState === 'function') { player._updateMpvState('$property', $value, $isRemoteSyncOrProgrammatic); }"
            "paused-for-cache", "cache-buffering-state", "eof-reached", "has-video-out", "buffer-probing" -> "if (typeof player !== 'undefined' && typeof player._updateMpvState === 'function') { player._updateMpvState('$property', $value); }"
            else -> null
        }
        script?.let {
            runOnUiThread {
                binding.syncWebView.evaluateJavascript(it, null)
            }
        }
    }

    private fun resolveSyncUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("/")) return url

        val serverUrl = binding.syncServerUrlInput.text.toString().trim()
        val base = if (serverUrl.endsWith("/")) serverUrl.substring(0, serverUrl.length - 1) else serverUrl
        return base + url
    }

    private fun clearOnlineSubtitleState() {
        onlineSubtitleUrl = null
        pendingOnlineSubtitleUrl = null
        pendingOnlineSubtitleTitle = null
        pendingOnlineSubtitleGeneration = 0
        appliedOnlineSubtitleUrl = null
        appliedOnlineSubtitleGeneration = 0
    }

    private fun queueOnlineSubtitle(url: String, title: String) {
        val absoluteUrl = resolveSyncUrl(url) ?: return
        val generation = mediaLoadGeneration
        onlineSubtitleUrl = absoluteUrl
        pendingOnlineSubtitleUrl = absoluteUrl
        pendingOnlineSubtitleTitle = title
        pendingOnlineSubtitleGeneration = generation
        Log.d(SYNC_TAG, "[subtitle] queued gen=$generation url=${syncUrlSummary(absoluteUrl)} title=$title")
        applyOnlineSubtitleIfReady(generation, "bridge")
    }

    private fun applyOnlineSubtitleIfReady(generation: Int, reason: String) {
        val subUrl = pendingOnlineSubtitleUrl ?: onlineSubtitleUrl ?: return
        if (generation != mediaLoadGeneration) {
            Log.d(SYNC_TAG, "[subtitle] skip stale reason=$reason subtitleGen=$generation mediaGen=$mediaLoadGeneration")
            return
        }
        if (startedMediaGeneration < generation) {
            Log.d(SYNC_TAG, "[subtitle] wait reason=$reason subtitleGen=$generation startedGen=$startedMediaGeneration")
            return
        }
        if (appliedOnlineSubtitleGeneration == generation && appliedOnlineSubtitleUrl == subUrl) {
            Log.d(SYNC_TAG, "[subtitle] skip duplicate reason=$reason gen=$generation")
            return
        }

        eventUiHandler.postDelayed({
            val delayedUrl = pendingOnlineSubtitleUrl ?: onlineSubtitleUrl ?: return@postDelayed
            if (generation != mediaLoadGeneration || startedMediaGeneration < generation) {
                Log.d(SYNC_TAG, "[subtitle] skip delayed stale reason=$reason subtitleGen=$generation mediaGen=$mediaLoadGeneration startedGen=$startedMediaGeneration")
                return@postDelayed
            }
            if (appliedOnlineSubtitleGeneration == generation && appliedOnlineSubtitleUrl == delayedUrl) {
                return@postDelayed
            }

            try {
                addSubtitleTrack(delayedUrl, "select", reason)
                appliedOnlineSubtitleUrl = delayedUrl
                appliedOnlineSubtitleGeneration = generation
                pendingOnlineSubtitleUrl = null
                pendingOnlineSubtitleTitle = null
                Log.d(SYNC_TAG, "[subtitle] sub-add ok reason=$reason gen=$generation url=${syncUrlSummary(delayedUrl)}")
            } catch (e: Exception) {
                Log.e(TAG, "online subtitle sub-add failed: ${e.message}", e)
            }
        }, 300L)
    }

    private fun addSubtitleTrack(url: String, flag: String, reason: String) {
        Log.d(SYNC_TAG, "[subtitle] mpv sub-add reason=$reason flag=$flag url=${syncUrlSummary(url)}")
        MPVLib.command(arrayOf("sub-add", url, flag))
    }

    private fun loadVideoWithHeaders(url: String, mode: String = "replace", title: String? = null) {
        mediaLoadGeneration += 1
        clearOnlineSubtitleState()
        currentLoadedUrl = url
        Log.d(SYNC_TAG, "[loadfile] ${syncUrlSummary(url)} mode=$mode")

        // Auto-hide syncPanel when a video URL is received from WebView.
        // This fires at the right time (when video is about to play), unlike
        // vo-configured which fires too early on surface creation.
        if (binding.syncPanel.visibility == View.VISIBLE) {
            Log.d(SYNC_TAG, "[ui] auto-hiding syncPanel on loadVideoWithHeaders")
            binding.syncPanel.visibility = View.GONE
            updateControlsMargins()
        }
        
        val isBaiduPcs = url.contains("baidupcs.com") || url.contains("pcs.baidu.com")
        Log.d(SYNC_TAG, "[headers] isBaiduPcs=$isBaiduPcs")
        try {
            // Only Baidu PCS needs custom headers (special UA, no Referer).
            // For ALL other URLs (Bilibili, HLS, any direct link), do NOT
            // modify user-agent or http-header-fields — let mpv use its
            // native defaults. Setting them to empty string would clear
            // mpv's default UA and break playback on many CDNs/servers.
            if (isBaiduPcs) {
                MPVLib.setPropertyString("user-agent", "pan.baidu.com")
                MPVLib.setPropertyString("http-header-fields", "")
            }
            MPVLib.setPropertyString("force-media-title", title ?: "")

            MPVLib.command(arrayOf("loadfile", url, mode))
        } catch (e: Exception) {
            Log.e(TAG, "loadVideoWithHeaders: failed to execute loadfile command", e)
        }
    }

    inner class AndroidBridge {
        private var activeDownloadThread: Thread? = null
        private var isAndroidDownloading = false

        @JavascriptInterface
        fun getDownloadPath(): String {
            val sharedPrefs = getDefaultSharedPreferences(applicationContext)
            return sharedPrefs.getString("download_path", "") ?: ""
        }

        @JavascriptInterface
        fun selectDownloadPath(): String {
            val defaultPath = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "sync_mpv_downloads").absolutePath
            var resultPath = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            
            runOnUiThread {
                val input = android.widget.EditText(this@MPVActivity)
                val sharedPrefs = getDefaultSharedPreferences(applicationContext)
                val saved = sharedPrefs.getString("download_path", "")
                input.setText(if (saved.isNullOrEmpty()) defaultPath else saved)
                
                android.app.AlertDialog.Builder(this@MPVActivity)
                    .setTitle("设置保存路径")
                    .setMessage("请输入本地保存的绝对路径：")
                    .setView(input)
                    .setPositiveButton("确定") { _, _ ->
                        val path = input.text.toString().trim()
                        if (path.isNotEmpty()) {
                            val dir = File(path)
                            if (!dir.exists()) {
                                dir.mkdirs()
                            }
                            sharedPrefs.edit().putString("download_path", path).apply()
                            resultPath = path
                            showToast("保存路径设置成功: $path")
                            val escapedPath = org.json.JSONObject.quote(path)
                            binding.syncWebView.evaluateJavascript("javascript:if(document.getElementById('downloadPathInput')) document.getElementById('downloadPathInput').value = $escapedPath;", null)
                        }
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        latch.countDown()
                    }
                    .setOnCancelListener {
                        latch.countDown()
                    }
                    .show()
            }
            try {
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {}
            return resultPath
        }

        @JavascriptInterface
        fun cancelDownload() {
            runOnUiThread {
                activeDownloadThread?.interrupt()
                isAndroidDownloading = false
            }
        }

        @JavascriptInterface
        fun downloadFile(url: String, fileName: String, headersJson: String) {
            if (isAndroidDownloading) {
                sendDownloadStatusToJs(fileName, 0.0, 0, 0, 0.0, "error", "当前已有一个正在进行的下载任务，请先取消或等待其完成。")
                return
            }
            val downloadDir = getDownloadPath()
            if (downloadDir.isEmpty()) {
                sendDownloadStatusToJs(fileName, 0.0, 0, 0, 0.0, "error", "下载路径未设置。")
                return
            }
            isAndroidDownloading = true
            activeDownloadThread = Thread {
                val partFile = File(downloadDir, "$fileName.part")
                val finalFile = File(downloadDir, fileName)
                try {
                    sendDownloadStatusToJs(fileName, 0.0, 0, 0, 0.0, "start", "开始下载...")
                    val urlObj = java.net.URL(url)
                    val conn = urlObj.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 20000
                    conn.instanceFollowRedirects = true

                    if (headersJson.isNotEmpty()) {
                        try {
                            val json = org.json.JSONObject(headersJson)
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val key = keys.next() as String
                                val value = json.getString(key)
                                conn.setRequestProperty(key, value)
                            }
                        } catch (e: Exception) {
                            Log.e("AndroidBridge", "Failed to parse headersJson: ${e.message}")
                        }
                    }

                    conn.connect()
                    val responseCode = conn.responseCode
                    if (responseCode !in 200..299) {
                        throw Exception("HTTP $responseCode")
                    }

                    val contentLength = conn.contentLengthLong
                    val inputStream = conn.inputStream
                    val outputStream = java.io.FileOutputStream(partFile)

                    val buffer = ByteArray(81920)
                    var downloadedBytes = 0L
                    var lastUpdateTime = System.currentTimeMillis()
                    var bytesSinceLastSpeedCheck = 0L
                    var currentSpeedMBs = 0.0
                    var lastSpeedTime = System.currentTimeMillis()

                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException()
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        bytesSinceLastSpeedCheck += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastSpeedTime >= 1000) {
                            currentSpeedMBs = (bytesSinceLastSpeedCheck / 1024.0 / 1024.0) / ((now - lastSpeedTime) / 1000.0)
                            bytesSinceLastSpeedCheck = 0
                            lastSpeedTime = now
                        }

                        if (now - lastUpdateTime >= 2000) {
                            val percent = if (contentLength > 0) {
                                Math.min(99.9, (downloadedBytes * 100.0 / contentLength))
                            } else {
                                0.0
                            }
                            sendDownloadStatusToJs(fileName, percent, downloadedBytes, contentLength, currentSpeedMBs, "progress", "")
                            lastUpdateTime = now
                        }
                    }

                    outputStream.close()
                    inputStream.close()

                    if (finalFile.exists()) {
                        finalFile.delete()
                    }
                    partFile.renameTo(finalFile)
                    sendDownloadStatusToJs(fileName, 100.0, downloadedBytes, contentLength, 0.0, "done", finalFile.absolutePath)
                } catch (e: InterruptedException) {
                    try { if (partFile.exists()) partFile.delete() } catch(ex: Exception) {}
                    sendDownloadStatusToJs(fileName, 0.0, 0, 0, 0.0, "error", "下载被取消。")
                } catch (e: Exception) {
                    try { if (partFile.exists()) partFile.delete() } catch(ex: Exception) {}
                    sendDownloadStatusToJs(fileName, 0.0, 0, 0, 0.0, "error", e.message ?: "下载失败")
                } finally {
                    isAndroidDownloading = false
                    activeDownloadThread = null
                }
            }
            activeDownloadThread?.start()
        }

        private fun sendDownloadStatusToJs(fileName: String, percent: Double, downloaded: Long, total: Long, speed: Double, status: String, message: String) {
            runOnUiThread {
                val escapedFile = org.json.JSONObject.quote(fileName)
                val escapedMsg = org.json.JSONObject.quote(message)
                val percentStr = String.format(java.util.Locale.US, "%.1f", percent)
                val speedStr = String.format(java.util.Locale.US, "%.1f", speed)
                val script = "javascript:window.onDownloadProgress?.($escapedFile, $percentStr, $downloaded, $total, $speedStr, '$status', $escapedMsg);"
                binding.syncWebView.evaluateJavascript(script, null)
            }
        }

        @JavascriptInterface
        fun onThemeToggle(isLight: Boolean) {
            runOnUiThread {
                val prefs = getDefaultSharedPreferences(applicationContext)
                prefs.edit().putBoolean("night_mode", !isLight).apply()
                Log.d("AndroidBridge", "onThemeToggle: isLight=$isLight -> night_mode=${!isLight}")
            }
        }

        @JavascriptInterface
        fun onVideoSrcChanged(url: String, title: String) {
            Log.d(SYNC_TAG, "[web->android] onVideoSrcChanged url=${syncUrlSummary(url)} title=$title")
            runOnUiThread {
                val serverUrl = binding.syncServerUrlInput.text.toString().trim()
                var absoluteUrl = if (url.startsWith("/")) {
                    val base = if (serverUrl.endsWith("/")) serverUrl.substring(0, serverUrl.length - 1) else serverUrl
                    base + url
                } else {
                    url
                }

                if (url.startsWith("blob:") && pendingLocalFileUrl != null) {
                    absoluteUrl = pendingLocalFileUrl!!
                    pendingLocalFileUrl = null
                    Log.d(SYNC_TAG, "[web->android] redirected blob url to local absoluteUrl=$absoluteUrl")
                }

                onlineSubtitleUrl = null
                loadVideoWithHeaders(absoluteUrl, "replace", title)
            }
        }

        @JavascriptInterface
        fun onVideoPlay(isRemoteSync: Boolean) {
            Log.d(SYNC_TAG, "[web->android] onVideoPlay isRemoteSync=$isRemoteSync")
            runOnUiThread {
                if (isRemoteSync) isRemotePlaySync = true
                player.paused = false
            }
        }

        @JavascriptInterface
        fun onVideoPause(isRemoteSync: Boolean) {
            Log.d(SYNC_TAG, "[web->android] onVideoPause isRemoteSync=$isRemoteSync")
            runOnUiThread {
                if (isRemoteSync) isRemotePauseSync = true
                player.paused = true
            }
        }

        @JavascriptInterface
        fun onVideoSeek(seconds: Double, isRemoteSync: Boolean) {
            Log.d(SYNC_TAG, "[web->android] onVideoSeek seconds=$seconds isRemoteSync=$isRemoteSync")
            runOnUiThread {
                val oldGen = bufferProbeGeneration
                bufferProbeGeneration++
                if (isBufferProbeActive) {
                    isBufferProbeActive = false
                    Log.d("MPVActivity", "[buffer-probe-cancelled] oldGen=$oldGen newGen=$bufferProbeGeneration due to new seek target=$seconds")
                    syncPlayerStateToWebView("buffer-probing", false)
                }

                // Block the ready check immediately in WebView
                mpvSeeking = true
                if (isRemoteSync) {
                    isProgrammaticSeek = true
                    syncPlayerStateToWebView("seeking", true, true)
                } else {
                    syncPlayerStateToWebView("seeking", true, false)
                }

                if (isRemoteSync) isRemoteSeekSync = true
                player.timePos = seconds

                val isPostCompleteCorrection = isRemoteSync && activeSeekId.isNullOrEmpty()
                val url = currentLoadedUrl
                val isRemote = url != null && (url.startsWith("http://") || url.startsWith("https://"))
                if (isRemote && !isPostCompleteCorrection) {
                    Log.d(SYNC_TAG, "[buffer-probe-trigger] start buffer probe for seconds=$seconds isRemoteSync=$isRemoteSync activeSeekId=$activeSeekId")
                    runBufferProbe(seconds, bufferProbeGeneration)
                } else if (isRemote) {
                    Log.d(SYNC_TAG, "[buffer-probe-skip] skipped remote=$isRemoteSync activeSeekId=$activeSeekId isPostCompleteCorrection=$isPostCompleteCorrection")
                }
            }
        }

        @JavascriptInterface
        fun onVideoSpeedChange(speed: Double, isRemoteSync: Boolean) {
            Log.d(SYNC_TAG, "[web->android] onVideoSpeedChange speed=$speed isRemoteSync=$isRemoteSync")
            runOnUiThread {
                if (isRemoteSync) isRemoteSpeedSync = true
                player.playbackSpeed = speed
            }
        }

        @JavascriptInterface
        fun onSubtitleLoaded(url: String, title: String) {
            Log.d(SYNC_TAG, "[web->android] onSubtitleLoaded url=${syncUrlSummary(url)} title=$title")
            runOnUiThread {
                queueOnlineSubtitle(url, title)
            }
        }
        
        @JavascriptInterface
        fun requestMpvState() {
            runOnUiThread {
                syncPlayerStateToWebView("seeking", mpvSeeking, isProgrammaticSeek)
                syncPlayerStateToWebView("paused-for-cache", mpvPausedForCache)
                syncPlayerStateToWebView("cache-buffering-state", mpvCacheBufferingState)
                syncPlayerStateToWebView("eof-reached", mpvEofReached)
                syncPlayerStateToWebView("has-video-out", mpvHasVideoOut)
                val exactTime = player.timePos ?: (psc.position.toDouble() / 1000.0)
                syncPlayerStateToWebView("time-pos", exactTime)
            }
        }

        @JavascriptInterface
        fun setActiveSeekId(seekId: String) {
            runOnUiThread {
                activeSeekId = if (seekId.isEmpty()) null else seekId
                Log.d(SYNC_TAG, "[web->android] setActiveSeekId id=$seekId")
                dumpSeekProbeState("set-active-seek-id")
                
                // Control native Syncing overlay visibility
                if (activeSeekId != null) {
                    binding.nativeSyncOverlay.visibility = View.VISIBLE
                } else {
                    binding.nativeSyncOverlay.visibility = View.GONE
                }
            }
        }
        
        @JavascriptInterface
        fun onDanmakuReceived(text: String, color: String) {
            // Placeholder
        }

        @JavascriptInterface
        fun setAudioCompatibility(enabled: Boolean) {
            Log.d(SYNC_TAG, "[web->android] setAudioCompatibility enabled=$enabled")
            runOnUiThread {
                isAudioCompatEnabled = enabled
                if (enabled) {
                    MPVLib.setPropertyString("audio-channels", "stereo")
                    MPVLib.setPropertyString("ao", "opensles")
                    showToast("远端已开启音频兼容 (立体声 + OpenSL ES)")
                } else {
                    MPVLib.setPropertyString("audio-channels", "stereo")
                    MPVLib.setPropertyString("ao", "audiotrack,opensles")
                    showToast("远端已关闭音频兼容 (自动默认)")
                }
                // Force audio output and track re-initialization to apply driver switch immediately
                MPVLib.command(arrayOf("ao-reload"))
                val currentAid = player.aid
                if (currentAid != -1) {
                    player.aid = -1
                    eventUiHandler.postDelayed({
                        runOnUiThread {
                            player.aid = currentAid
                        }
                    }, 200)
                }
            }
        }
    }

    private fun runBufferProbe(targetSeconds: Double, gen: Int) {
        Log.d("MPVActivity", "[buffer-probe-start] seekId=remoteSeek target=$targetSeconds gen=$gen")
        isBufferProbeActive = true
        bufferProbeTarget = targetSeconds
        originalMuteState = MPVLib.getPropertyBoolean("mute") == true

        syncPlayerStateToWebView("buffer-probing", true)
        MPVLib.setPropertyBoolean("mute", true)

        eventUiHandler.postDelayed({
            runOnUiThread {
                if (bufferProbeGeneration != gen) {
                    Log.d("MPVActivity", "[buffer-probe-cancelled] Play step skipped for gen=$gen (current=$bufferProbeGeneration)")
                    return@runOnUiThread
                }
                Log.d("MPVActivity", "[buffer-probe-play] gen=$gen unpausing MPV to force buffering")
                player.paused = false
                
                // Start dynamic polling ticks after unpausing
                startBufferProbePolling(targetSeconds, gen, 0)
            }
        }, 150)
    }

    private fun startBufferProbePolling(targetSeconds: Double, gen: Int, tick: Int) {
        val maxTicks = 180 // 18 seconds total
        if (bufferProbeGeneration != gen) {
            Log.d("MPVActivity", "[buffer-probe-cancelled] Polling loop stopped for gen=$gen (current=$bufferProbeGeneration)")
            return
        }

        if (tick >= maxTicks) {
            Log.d("MPVActivity", "[buffer-probe-timeout] Dynamic buffering timed out after 18s for gen=$gen. Proceeding with best-effort pause.")
            finishBufferProbe(targetSeconds, gen)
            return
        }

        val seeking = mpvSeeking
        val pausedForCache = mpvPausedForCache
        val cacheState = mpvCacheBufferingState
        val hasVideoOut = mpvHasVideoOut

        if (!seeking && !pausedForCache && cacheState >= 100 && hasVideoOut) {
            Log.d("MPVActivity", "[buffer-probe-success] Buffer target reached at tick $tick (${tick * 100}ms) for gen=$gen. seeking=$seeking, pausedForCache=$pausedForCache, cacheState=$cacheState, hasVideoOut=$hasVideoOut")
            finishBufferProbe(targetSeconds, gen)
            return
        }

        if (tick % 10 == 0) {
            Log.d("MPVActivity", "[buffer-probe-polling] Gen $gen at tick $tick: seeking=$seeking, pausedForCache=$pausedForCache, cacheState=$cacheState, hasVideoOut=$hasVideoOut")
        }

        // Post next tick in 100ms
        eventUiHandler.postDelayed({
            runOnUiThread {
                startBufferProbePolling(targetSeconds, gen, tick + 1)
            }
        }, 100)
    }

    private fun finishBufferProbe(targetSeconds: Double, gen: Int) {
        if (bufferProbeGeneration != gen) return

        Log.d("MPVActivity", "[buffer-probe-pause-seekback] gen=$gen pausing MPV and seeking back")
        player.paused = true
        isProgrammaticSeek = true
        player.timePos = targetSeconds

        // Wait 250ms for the seek to settle, then restore mute and finish probe
        eventUiHandler.postDelayed({
            runOnUiThread {
                if (bufferProbeGeneration != gen) return@runOnUiThread
                Log.d("MPVActivity", "[buffer-probe-end] gen=$gen restoring mute=$originalMuteState and finishing probe")
                MPVLib.setPropertyBoolean("mute", originalMuteState)
                isBufferProbeActive = false
                syncPlayerStateToWebView("buffer-probing", false)
                
                val programmatic = isProgrammaticSeek || pendingProgrammaticSeekFinish
                Log.d(SYNC_TAG, "[programmatic-seek] flush-finish seeking=$mpvSeeking programmatic=$programmatic")
                syncPlayerStateToWebView("seeking", mpvSeeking, programmatic)
                syncPlayerStateToWebView("paused-for-cache", mpvPausedForCache)
                syncPlayerStateToWebView("cache-buffering-state", mpvCacheBufferingState)
                syncPlayerStateToWebView("eof-reached", mpvEofReached)
                syncPlayerStateToWebView("has-video-out", mpvHasVideoOut)
                if (!mpvSeeking) {
                    isProgrammaticSeek = false
                    pendingProgrammaticSeekFinish = false
                }
            }
        }, 250)
    }

    fun getWebViewUserAgent(): String {
        return binding.syncWebView.settings.userAgentString ?: ""
    }

    private fun getMonkeyPatchJs(): String {
        return """
            (function() {
                // Intercept and mock video sources globally to prevent sidebar/WebView playback
                var originalSetAttribute = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if (name === 'src' && value && value !== 'about:blank' && !value.startsWith('data:')) {
                        if (this.tagName === 'SOURCE') {
                            this._mockSrc = value;
                            console.log("SyncTV [source-setAttribute] " + value);
                            try {
                                var title = document.title || "Video";
                                AndroidBridge.onVideoSrcChanged(value, title);
                            } catch(e) { console.error(e); }
                            originalSetAttribute.call(this, 'src', 'data:video/mp4;base64,');
                            return;
                        } else if (this.tagName === 'VIDEO') {
                            if (!value.startsWith('blob:')) {
                                this._mockSrc = value;
                                console.log("SyncTV [video-setAttribute] " + value);
                                try {
                                    var title = document.title || "Video";
                                    AndroidBridge.onVideoSrcChanged(value, title);
                                } catch(e) { console.error(e); }
                                originalSetAttribute.call(this, 'src', 'data:video/mp4;base64,');
                                return;
                            }
                        }
                    }
                    originalSetAttribute.call(this, name, value);
                };

                var originalGetAttribute = Element.prototype.getAttribute;
                Element.prototype.getAttribute = function(name) {
                    if (name === 'src') {
                        if (this.tagName === 'SOURCE' || this.tagName === 'VIDEO') {
                            if (this._mockSrc) return this._mockSrc;
                        }
                    }
                    return originalGetAttribute.call(this, name);
                };

                var nativeSourceSrc = Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype, 'src');
                if (nativeSourceSrc && nativeSourceSrc.set) {
                    Object.defineProperty(HTMLSourceElement.prototype, 'src', {
                        get: function() { return this._mockSrc || ""; },
                        set: function(val) {
                            if (!val || val === 'about:blank' || val.startsWith('data:')) return;
                            this._mockSrc = val;
                            console.log("SyncTV [source-src-set] " + val);
                            try {
                                var title = document.title || "Video";
                                AndroidBridge.onVideoSrcChanged(val, title);
                            } catch(e) { console.error(e); }
                            nativeSourceSrc.set.call(this, 'data:video/mp4;base64,');
                        }
                    });
                } else {
                    try {
                        Object.defineProperty(HTMLSourceElement.prototype, 'src', {
                            get: function() { return this._mockSrc || ""; },
                            set: function(val) {
                                if (!val || val === 'about:blank' || val.startsWith('data:')) return;
                                this._mockSrc = val;
                                console.log("SyncTV [source-src-set-fallback] " + val);
                                try {
                                    var title = document.title || "Video";
                                    AndroidBridge.onVideoSrcChanged(val, title);
                                } catch(e) { console.error(e); }
                            }
                        });
                    } catch(e) { console.error("HTMLSourceElement override failed", e); }
                }

                // Intercept <video>.src property setter (catches player.src = url bypass)
                var nativeVideoSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (nativeVideoSrc && nativeVideoSrc.set) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        configurable: true,
                        get: function() {
                            if (this.tagName === 'VIDEO' && this._mockSrc) return this._mockSrc;
                            return nativeVideoSrc.get ? nativeVideoSrc.get.call(this) : '';
                        },
                        set: function(val) {
                            if (this.tagName !== 'VIDEO') {
                                if (nativeVideoSrc.set) nativeVideoSrc.set.call(this, val);
                                return;
                            }
                            if (!val || val === 'about:blank' || val.startsWith('data:') || val.startsWith('blob:')) {
                                if (nativeVideoSrc.set) nativeVideoSrc.set.call(this, val);
                                return;
                            }
                            this._mockSrc = val;
                            console.log("SyncTV [video-src-set] " + val);
                            try {
                                var title = document.title || "Video";
                                AndroidBridge.onVideoSrcChanged(val, title);
                            } catch(e) { console.error(e); }
                            if (nativeVideoSrc.set) nativeVideoSrc.set.call(this, 'data:video/mp4;base64,');
                        }
                    });
                }

                // Periodic style application depending on pathname
                function applyStyles() {
                    if (window.location.pathname.indexOf('/room/') !== -1) {
                        if (!document.getElementById('synctv-custom-style')) {
                            var style = document.createElement('style');
                            style.id = 'synctv-custom-style';
                            style.type = 'text/css';
                            style.innerHTML = ' \
                                .topbar { display: none !important; } \
                                .theater { display: none !important; } \
                                .layout { grid-template-columns: 1fr !important; gap: 0 !important; } \
                                .side { grid-template-columns: 1fr !important; gap: 10px !important; } \
                                .panel { padding: 12px !important; margin-bottom: 10px !important; } \
                                ::-webkit-scrollbar { display: none !important; } \
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
                    if (!p) return;

                    // Periodic scan to intercept any unpatched sources in DOM
                    var videoSrc = originalGetAttribute.call(p, 'src');
                    if (videoSrc && videoSrc !== 'about:blank' && !videoSrc.startsWith('data:') && !videoSrc.startsWith('blob:')) {
                        p._mockSrc = videoSrc;
                        console.log("SyncTV [periodic-video-scan] " + videoSrc);
                        try {
                            var title = document.title || "Video";
                            AndroidBridge.onVideoSrcChanged(videoSrc, title);
                        } catch(e) { console.error(e); }
                        originalSetAttribute.call(p, 'src', 'data:video/mp4;base64,');
                    }

                    var sources = p.querySelectorAll('source');
                    for (var i = 0; i < sources.length; i++) {
                        var s = sources[i];
                        var sourceSrc = originalGetAttribute.call(s, 'src');
                        if (sourceSrc && sourceSrc !== 'about:blank' && !sourceSrc.startsWith('data:')) {
                            s._mockSrc = sourceSrc;
                            console.log("SyncTV [periodic-source-scan] " + sourceSrc);
                            try {
                                var title = document.title || "Video";
                                AndroidBridge.onVideoSrcChanged(sourceSrc, title);
                            } catch(e) { console.error(e); }
                            originalSetAttribute.call(s, 'src', 'data:video/mp4;base64,');
                        }
                    }

                    if (p._isMocked) return;
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
                        hasVideoOut: false,
                        bufferProbing: false,
                        timePos: 0
                    };
                    
                    p._updateMpvState = function(key, value, isProgrammatic) {
                        if (key === 'seeking') {
                            var oldSeeking = this.mpvState.seeking;
                            this.mpvState.seeking = value;
                            console.log('SyncTV [mpv-state] seeking=' + value + ' programmatic=' + !!isProgrammatic);
                            if (oldSeeking !== value) {
                                if (typeof isSyncing !== 'undefined' && !isSyncing && !isProgrammatic) {
                                    if (value) {
                                        this.dispatchEvent(new Event('seeking'));
                                    } else {
                                        this.dispatchEvent(new Event('seeked'));
                                    }
                                }
                            }
                        }
                        else if (key === 'paused-for-cache') this.mpvState.pausedForCache = value;
                        else if (key === 'cache-buffering-state') this.mpvState.cacheBufferingState = value;
                        else if (key === 'eof-reached') this.mpvState.eofReached = value;
                        else if (key === 'has-video-out') this.mpvState.hasVideoOut = value;
                        else if (key === 'buffer-probing') this.mpvState.bufferProbing = value;
                        else if (key === 'time-pos') this.mpvState.timePos = value;
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
                        if (this.mpvState) this.mpvState.timePos = time;
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

                    function safeDefineProperty(obj, prop, desc) {
                        try {
                            Object.defineProperty(obj, prop, desc);
                        } catch(e) {
                            console.error("Define " + prop + " failed", e);
                        }
                    }

                    safeDefineProperty(p, 'src', {
                        get: function() {
                            if (this._mockSrc) return this._mockSrc;
                            var firstSource = this.querySelector('source');
                            if (firstSource && firstSource._mockSrc) return firstSource._mockSrc;
                            return "";
                        },
                        set: function(val) {
                            if (!val || val === 'about:blank' || val.startsWith('data:')) return;
                            this._mockSrc = val;
                            try {
                                var title = document.title || "Video";
                                AndroidBridge.onVideoSrcChanged(val, title);
                            } catch(e) { console.error(e); }
                        }
                    });
                    
                    safeDefineProperty(p, 'currentTime', {
                        get: function() { return this._mockCurrentTime; },
                        set: function(val) {
                            this._mockCurrentTime = val;
                            if (this._syncingFromNative) return;
                            try {
                                var isSyncingVar = typeof isSyncing !== 'undefined' ? isSyncing : false;
                                if (!isSyncingVar && typeof startSeekSync === 'function') {
                                    var playing = !this.paused;
                                    var speed = typeof roomSpeed !== 'undefined' ? roomSpeed : 1.0;
                                    startSeekSync(val, playing, speed);
                                }
                            } catch(e) { console.error("SyncTV [currentTime-local-broadcast] failed:", e); }
                            try {
                                AndroidBridge.onVideoSeek(val, typeof isSyncing !== 'undefined' ? isSyncing : false);
                            } catch(e) {}
                        }
                    });

                    safeDefineProperty(p, 'duration', {
                        get: function() { return this._mockDuration; },
                        set: function() {}
                    });

                    safeDefineProperty(p, 'paused', {
                        get: function() { return this._mockPaused; },
                        set: function() {}
                    });

                    safeDefineProperty(p, 'seeking', {
                        get: function() { return this.mpvState ? this.mpvState.seeking : false; }
                    });

                    safeDefineProperty(p, 'playbackRate', {
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
                }, 500);

                // Intercept WebSocket messages to forward audio-compat to native MPV
                var _origWsSetter = Object.getOwnPropertyDescriptor(window, 'ws');
                var _audioCompatPatched = false;
                setInterval(function() {
                    if (_audioCompatPatched || !window.ws || !window.ws.onmessage) return;
                    var origOnMessage = window.ws.onmessage;
                    window.ws.onmessage = function(e) {
                        try {
                            var msg = JSON.parse(e.data);
                            if (msg.type === 'audio-compat' && typeof msg.enabled === 'boolean') {
                                console.log('SyncTV [audio-compat] received enabled=' + msg.enabled);
                                try { AndroidBridge.setAudioCompatibility(msg.enabled); } catch(err) {}
                            }
                        } catch(ex) {}
                        origOnMessage.call(this, e);
                    };
                    _audioCompatPatched = true;
                    console.log('SyncTV [audio-compat] WebSocket onmessage patched');
                }, 1000);
            })();
        """.trimIndent()
    }

    private fun syncThemeToWebView() {
        val prefs = getDefaultSharedPreferences(applicationContext)
        val nightMode = prefs.getBoolean("night_mode", false)
        val themeStr = if (nightMode) "dark" else "light"
        binding.syncWebView.post {
            binding.syncWebView.evaluateJavascript("""
                (function() {
                    localStorage.setItem('theme', '$themeStr');
                    if ('$themeStr' === 'light') {
                        document.documentElement.classList.add('light-mode');
                    } else {
                        document.documentElement.classList.remove('light-mode');
                    }
                    var toggle = document.getElementById('themeToggle');
                    if (toggle) {
                        toggle.innerHTML = '$themeStr' === 'light' ? '☀️' : '🌙';
                    }
                })();
            """.trimIndent(), null)
        }
    }

    companion object {
        private const val TAG = "mpv"
        private const val SYNC_TAG = "SyncTV"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // how long controls fade to disappear (ms)
        private const val CONTROLS_FADE_DURATION = 500L
        // resolution (px) of the thumbnail displayed with playback notification
        private const val THUMB_SIZE = 384
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        private const val RCODE_WEB_FILE_CHOOSER = 1003
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2
    }
}
