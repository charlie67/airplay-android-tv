package com.airplayserver.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Foreground service that owns the native AirPlay receiver and its mDNS
 * advertisement (the role of CAirServer + Bonjour on Windows).
 */
class AirPlayService : Service(), NativeListener {
    private val control = Executors.newSingleThreadExecutor { Thread(it, "ReceiverControl") }
    private lateinit var prefs: Prefs
    private lateinit var advertiser: NsdAdvertiser
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Touched only on the control thread, except for reads of handle.
    @Volatile private var handle = 0L
    private var advertisedAddresses = emptyList<String>()
    private var advertisedName = ""
    private var raopPort = 0
    private var airplayPort = 0
    @Volatile private var mirroring = false
    @Volatile private var pendingRestart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        advertiser = NsdAdvertiser(this)
        startInForeground()
        acquireNetworkLocks()
        watchNetwork()
        control.execute { startReceiver() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART -> control.execute {
                if (mirroring) {
                    pendingRestart = true
                } else {
                    stopReceiver()
                    startReceiver()
                }
            }
            ACTION_DISCONNECT -> control.execute {
                if (handle != 0L) NativeBridge.nativeDisconnect(handle)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        control.execute {
            stopReceiver()
            ReceiverState.update { it.copy(status = ReceiverState.Status.STOPPED, clientName = null) }
        }
        control.shutdown()
        multicastLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- receiver lifecycle (control thread) --------------------------------

    private fun startReceiver() {
        if (handle != 0L) return
        val name = prefs.receiverName
        ReceiverState.update {
            it.copy(status = ReceiverState.Status.STARTING, receiverName = name, error = null)
        }
        val (width, height) = prefs.displaySize()
        val verbose = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val hwaddr = prefs.hardwareAddress
        val started = runCatching {
            NativeBridge.nativeStart(this, hwaddr, prefs.pairingSeed, width, height, verbose)
        }.onFailure { Log.e(TAG, "nativeStart threw", it) }.getOrDefault(0L)
        if (started == 0L) {
            ReceiverState.update {
                it.copy(status = ReceiverState.Status.ERROR,
                    error = getString(R.string.error_start_failed))
            }
            return
        }
        handle = started
        raopPort = NativeBridge.nativeGetRaopPort(started)
        airplayPort = NativeBridge.nativeGetAirplayPort(started)
        advertisedName = name
        advertisedAddresses = currentAddresses()
        advertiser.register(name, hwaddr, raopPort, airplayPort)
        Log.i(TAG, "Receiver '$name' ready at ${advertisedAddresses.joinToString()} " +
            "(raop $raopPort, airplay $airplayPort, display ${width}x$height)")
        ReceiverState.update {
            it.copy(status = ReceiverState.Status.READY, addresses = advertisedAddresses,
                clientName = null, error = null)
        }
    }

    private fun stopReceiver() {
        advertiser.unregister()
        pendingRestart = false
        val current = handle
        handle = 0L
        if (current != 0L) NativeBridge.nativeStop(current)
        mirroring = false
        ReceiverState.video.reset()
        ReceiverState.audio.stop()
    }

    private fun onNetworkChanged() {
        val addresses = currentAddresses()
        ReceiverState.update { it.copy(addresses = addresses) }
        if (handle != 0L && addresses != advertisedAddresses && addresses.isNotEmpty()) {
            // Re-announce so senders learn the new address.
            advertisedAddresses = addresses
            advertiser.register(advertisedName, prefs.hardwareAddress, raopPort, airplayPort)
        }
    }

    // ---- native callbacks (protocol threads) --------------------------------

    override fun onConnected(name: ByteArray, deviceId: ByteArray) {
        val client = String(name, Charsets.UTF_8).ifBlank { getString(R.string.unknown_device) }
        Log.i(TAG, "Mirroring started from $client")
        mirroring = true
        ReceiverState.update {
            it.copy(status = ReceiverState.Status.CONNECTED, clientName = client, senderPaused = false)
        }
        bringToFront()
    }

    override fun onDisconnected(name: ByteArray, deviceId: ByteArray) {
        Log.i(TAG, "Mirroring ended from ${String(name, Charsets.UTF_8)}")
        mirroring = false
        ReceiverState.video.reset()
        ReceiverState.audio.stop()
        ReceiverState.update {
            it.copy(
                status = if (handle != 0L) ReceiverState.Status.READY else it.status,
                clientName = null, senderPaused = false,
            )
        }
        if (pendingRestart) {
            pendingRestart = false
            runCatching {
                control.execute {
                    stopReceiver()
                    startReceiver()
                }
            }
        }
    }

    override fun onVideoData(data: ByteArray, isCodecConfig: Boolean, pts: Long) {
        // The native "frame type 0" marks both the SPS/PPS packet and keyframes,
        // so route by content: parameter sets configure the decoder, slices decode.
        val types = H264.nalTypes(data)
        if (H264.NAL_SPS in types) ReceiverState.video.onCodecConfig(data)
        if (H264.NAL_IDR in types || H264.NAL_SLICE in types) ReceiverState.video.onFrame(data)
    }

    override fun onAudioData(pcm: ByteArray, sampleRate: Int, channels: Int) {
        ReceiverState.audio.enqueue(pcm, sampleRate, channels)
    }

    override fun onVolume(volumeDb: Float) = ReceiverState.audio.setVolumeDb(volumeDb)

    override fun onAudioFlush() = ReceiverState.audio.flush()

    override fun onSenderPaused(paused: Boolean) {
        ReceiverState.update { it.copy(senderPaused = paused) }
    }

    override fun onVideoGeometry(sourceWidth: Float, sourceHeight: Float, outputWidth: Float, outputHeight: Float) {
        Log.d(TAG, "Sender geometry source ${sourceWidth}x$sourceHeight output ${outputWidth}x$outputHeight")
    }

    // ---- helpers -----------------------------------------------------------

    /** Shows the mirroring screen when a device connects while the app is in the background. */
    private fun bringToFront() {
        if (App.visibleActivities > 0) return
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the receiver screen from the background", e)
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireNetworkLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock("AirPlayServer:mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "AirPlayServer:stream").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post()
            override fun onLost(network: Network) = post()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = post()
            private fun post() {
                runCatching { control.execute { onNetworkChanged() } }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun currentAddresses(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.filter { !it.isLoopbackAddress }
            ?.mapNotNull { it.hostAddress }
            ?: emptyList()
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        const val ACTION_RESTART = "com.airplayserver.tv.action.RESTART"
        const val ACTION_DISCONNECT = "com.airplayserver.tv.action.DISCONNECT"

        fun start(context: Context) = send(context, null)
        fun restart(context: Context) = send(context, ACTION_RESTART)
        fun disconnect(context: Context) = send(context, ACTION_DISCONNECT)

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
        }

        private fun send(context: Context, action: String?) {
            val intent = Intent(context, AirPlayService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the receiver service", e)
            }
        }
    }
}
