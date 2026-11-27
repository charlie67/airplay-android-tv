package com.airplayserver.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale

/** Home screen while waiting, and the full-screen mirroring view while connected. */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var videoView: SurfaceView
    private lateinit var home: View
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var addressView: TextView
    private lateinit var retryButton: Button
    private lateinit var settingsButton: Button
    private lateinit var infoView: TextView
    private lateinit var pausedView: TextView
    private lateinit var hintView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var state = ReceiverState.current
    private var lastBackPress = 0L
    private var lastStatsTime = 0L
    private var lastFrames = 0L
    private var lastBytes = 0L

    private val observer: (ReceiverState.Snapshot) -> Unit = { render(it) }
    private var backCallback: Any? = null
    private val hideHint = Runnable { hintView.visibility = View.GONE }
    private val statsTick = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        videoView = findViewById(R.id.video)
        home = findViewById(R.id.home)
        nameView = findViewById(R.id.receiver_name)
        statusView = findViewById(R.id.status)
        addressView = findViewById(R.id.address)
        retryButton = findViewById(R.id.retry)
        settingsButton = findViewById(R.id.settings)
        infoView = findViewById(R.id.info)
        pausedView = findViewById(R.id.paused)
        hintView = findViewById(R.id.hint)

        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        retryButton.setOnClickListener { AirPlayService.restart(this) }

        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                ReceiverState.video.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                ReceiverState.video.detachSurface()
            }
        })
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) root.post { layoutVideo() }
        }
        settingsButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        AirPlayService.start(this)
        ReceiverState.observe(observer)
    }

    override fun onStop() {
        ReceiverState.removeObserver(observer)
        handler.removeCallbacks(statsTick)
        super.onStop()
    }

    private fun render(snapshot: ReceiverState.Snapshot) {
        val previous = state
        state = snapshot
        val connected = snapshot.status == ReceiverState.Status.CONNECTED
        val showVideo = connected && snapshot.videoWidth > 0 && snapshot.videoHeight > 0

        nameView.text = snapshot.receiverName.ifEmpty { Prefs(this).receiverName }
        statusView.text = when (snapshot.status) {
            ReceiverState.Status.STOPPED, ReceiverState.Status.STARTING -> getString(R.string.status_starting)
            ReceiverState.Status.READY ->
                if (snapshot.addresses.isEmpty()) getString(R.string.status_no_network)
                else getString(R.string.status_ready, snapshot.receiverName)
            ReceiverState.Status.CONNECTED ->
                getString(R.string.status_connected, snapshot.clientName ?: getString(R.string.unknown_device))
            ReceiverState.Status.ERROR -> snapshot.error ?: getString(R.string.error_start_failed)
        }
        addressView.text = if (snapshot.addresses.isEmpty()) "" else
            getString(R.string.address_line, snapshot.addresses.joinToString("  ·  "))
        retryButton.visibility = if (snapshot.status == ReceiverState.Status.ERROR) View.VISIBLE else View.GONE

        home.visibility = if (showVideo) View.GONE else View.VISIBLE
        interceptBack(showVideo)
        pausedView.visibility = if (showVideo && snapshot.senderPaused) View.VISIBLE else View.GONE
        pausedView.text = getString(R.string.paused_on, snapshot.clientName ?: getString(R.string.unknown_device))
        if (!showVideo) {
            infoView.visibility = View.GONE
            hintView.visibility = View.GONE
            if (currentFocus == null) settingsButton.requestFocus()
        }

        if (connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (previous.videoWidth != snapshot.videoWidth || previous.videoHeight != snapshot.videoHeight) {
            layoutVideo()
        }
    }

    /** Fits the video surface to the screen while keeping the stream's aspect ratio. */
    private fun layoutVideo() {
        val containerW = root.width
        val containerH = root.height
        val videoW = state.videoWidth
        val videoH = state.videoHeight
        val params = videoView.layoutParams as FrameLayout.LayoutParams
        if (containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
        } else if (containerW.toLong() * videoH > containerH.toLong() * videoW) {
            params.height = containerH
            params.width = (containerH.toLong() * videoW / videoH).toInt()
        } else {
            params.width = containerW
            params.height = (containerW.toLong() * videoH / videoW).toInt()
        }
        videoView.layoutParams = params
    }

    private val videoShowing get() = home.visibility != View.VISIBLE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!videoShowing) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // On API 33+ BACK arrives through the OnBackInvokedCallback instead.
                onBackDuringVideo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                toggleInfo()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (videoShowing) {
            onBackDuringVideo()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** First BACK shows a hint; a second one within 3 s ends the session. */
    private fun onBackDuringVideo() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackPress < 3000) {
            lastBackPress = 0
            hintView.visibility = View.GONE
            AirPlayService.disconnect(this)
        } else {
            lastBackPress = now
            hintView.setText(R.string.hint_back_to_stop)
            hintView.visibility = View.VISIBLE
            handler.removeCallbacks(hideHint)
            handler.postDelayed(hideHint, 3000)
        }
    }

    private fun interceptBack(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        if (enabled && backCallback == null) {
            val callback = OnBackInvokedCallback { onBackDuringVideo() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!enabled && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    private fun toggleInfo() {
        if (infoView.visibility == View.VISIBLE) {
            infoView.visibility = View.GONE
            handler.removeCallbacks(statsTick)
        } else {
            lastStatsTime = 0
            infoView.visibility = View.VISIBLE
            handler.removeCallbacks(statsTick)
            handler.post(statsTick)
        }
    }

    private fun updateInfo() {
        val video = ReceiverState.video
        val now = SystemClock.elapsedRealtime()
        val frames = video.framesRendered
        val bytes = video.bytesReceived
        var fps = 0.0
        var mbps = 0.0
        if (lastStatsTime != 0L && now > lastStatsTime) {
            val seconds = (now - lastStatsTime) / 1000.0
            fps = (frames - lastFrames).coerceAtLeast(0) / seconds
            mbps = (bytes - lastBytes).coerceAtLeast(0) * 8 / seconds / 1_000_000
        }
        lastStatsTime = now
        lastFrames = frames
        lastBytes = bytes
        infoView.text = getString(
            R.string.info_overlay,
            state.clientName ?: getString(R.string.unknown_device),
            state.videoWidth, state.videoHeight,
            String.format(Locale.US, "%.0f", fps),
            String.format(Locale.US, "%.1f", mbps),
            video.decoderName,
            state.receiverName,
            String.format(Locale.US, "%.0f", video.decodeLatencyMs),
        )
    }
}
