package com.airplayserver.tv

/**
 * Callbacks invoked by the native receiver (airplay_jni.c) on its protocol
 * threads. Strings arrive as UTF-8 bytes; method names and signatures are
 * looked up by JNI, so keep them in sync with airplay_jni.c.
 */
interface NativeListener {
    fun onConnected(name: ByteArray, deviceId: ByteArray)
    fun onDisconnected(name: ByteArray, deviceId: ByteArray)

    /** Annex-B H.264. [isCodecConfig] is true for the SPS+PPS packet and for keyframes. */
    fun onVideoData(data: ByteArray, isCodecConfig: Boolean, pts: Long)

    /** Interleaved signed 16-bit PCM. */
    fun onAudioData(pcm: ByteArray, sampleRate: Int, channels: Int)

    /** AirPlay volume in dB: 0 is full volume, -144 is mute. */
    fun onVolume(volumeDb: Float)
    fun onAudioFlush()
    fun onSenderPaused(paused: Boolean)
    fun onVideoGeometry(sourceWidth: Float, sourceHeight: Float, outputWidth: Float, outputHeight: Float)
}

object NativeBridge {
    init {
        System.loadLibrary("airplayserver")
    }

    /** Starts the RAOP and AirPlay servers. Returns a handle, or 0 on failure. */
    @JvmStatic
    external fun nativeStart(
        listener: NativeListener,
        hardwareAddress: ByteArray,
        pairingSeed: ByteArray,
        displayWidth: Int,
        displayHeight: Int,
        verbose: Boolean,
    ): Long

    @JvmStatic external fun nativeGetRaopPort(handle: Long): Int
    @JvmStatic external fun nativeGetAirplayPort(handle: Long): Int

    /** Ends the active sender's session. */
    @JvmStatic external fun nativeDisconnect(handle: Long)

    @JvmStatic external fun nativeStop(handle: Long)
}
