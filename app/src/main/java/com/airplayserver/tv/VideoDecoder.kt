package com.airplayserver.tv

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.264 decoding of the mirroring stream with MediaCodec, rendering
 * straight to the activity's Surface (replaces FFmpeg + SDL on Windows).
 *
 * Senders only send a keyframe when a stream starts, so the decoder must not
 * lose its reference frames when the UI goes away. While no Surface is
 * attached the codec keeps decoding into an off-screen ImageReader, and the
 * frames since the last keyframe are cached so a decoder can be rebuilt.
 */
class VideoDecoder(private val onVideoSize: (Int, Int) -> Unit) {
    private class Frame(val data: ByteArray)

    private val lock = Object()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var renderEnabled = false
    private var codecConfig: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var streamWidth = 1920
    private var streamHeight = 1080

    private val gop = ArrayList<Frame>()
    private var gopBytes = 0L
    private var gopValid = false
    private var offscreen: ImageReader? = null
    private var failures = 0

    @Volatile var framesRendered = 0L
        private set
    @Volatile var bytesReceived = 0L
        private set
    @Volatile var decoderName: String = ""
        private set

    /** Average time from queueing a frame to rendering it, over the last stats window. */
    @Volatile var decodeLatencyMs = 0.0
        private set
    private var latencySumUs = 0L
    private var latencyMaxUs = 0L
    private var latencyCount = 0
    private var latencyWindowStart = 0L

    fun attachSurface(target: Surface) = synchronized(lock) {
        surface = target
        val current = codec
        if (current != null) {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    current.setOutputSurface(target)
                    renderEnabled = true
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "setOutputSurface failed; rebuilding decoder", e)
            }
            releaseCodecLocked()
        }
        startCodecLocked()
    }

    fun detachSurface() = synchronized(lock) {
        surface = null
        renderEnabled = false
        val current = codec ?: return
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                current.setOutputSurface(offscreenSurface())
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not park decoder off-screen", e)
        }
        releaseCodecLocked()
    }

    fun onCodecConfig(data: ByteArray) = synchronized(lock) {
        if (codecConfig != null && data.contentEquals(codecConfig)) return
        val sets = H264.splitParameterSets(data)
        if (sets == null) {
            Log.w(TAG, "Ignoring codec config without SPS/PPS")
            return
        }
        codecConfig = data
        sps = sets.first
        pps = sets.second
        H264.parseSpsSize(sets.first)?.let { (w, h) ->
            streamWidth = w
            streamHeight = h
        }
        Log.i(TAG, "New stream ${streamWidth}x$streamHeight")
        clearGopLocked()
        failures = 0
        releaseCodecLocked()
        startCodecLocked()
    }

    fun onFrame(data: ByteArray) = synchronized(lock) {
        bytesReceived += data.size
        val idr = H264.containsIdr(data)
        if (idr) {
            clearGopLocked()
            gopValid = true
        }
        if (gopValid) {
            if (gop.size >= MAX_GOP_FRAMES || gopBytes + data.size > MAX_GOP_BYTES) {
                clearGopLocked()
            } else {
                gop.add(Frame(data))
                gopBytes += data.size
            }
        }
        if (codec == null && surface != null && sps != null) {
            startCodecLocked()
            // The replay of the cached GOP already included this frame.
            if (gopValid) return
        }
        val current = codec ?: return
        queueLocked(current, data)
    }

    /** Called when a session ends. */
    fun reset() = synchronized(lock) {
        releaseCodecLocked()
        codecConfig = null
        sps = null
        pps = null
        clearGopLocked()
        offscreen?.close()
        offscreen = null
        framesRendered = 0
        bytesReceived = 0
        onVideoSize(0, 0)
    }

    private fun clearGopLocked() {
        gop.clear()
        gopBytes = 0
        gopValid = false
    }

    private fun startCodecLocked() {
        val spsData = sps ?: return
        val ppsData = pps ?: return
        val target = surface ?: return
        if (codec != null) return
        if (failures >= MAX_FAILURES) return
        try {
            val created = createConfiguredCodec(target)
            created.start()
            codec = created
            decoderName = created.name
            renderEnabled = true
            running = true
            latencyWindowStart = System.nanoTime()
            Log.i(TAG, "Decoder ${created.name} started for ${streamWidth}x$streamHeight")
            outputThread = Thread({ drainOutput(created) }, "VideoOutput").also { it.start() }
            // Replay frames since the last keyframe so the picture is complete.
            if (gopValid) {
                for (frame in gop) queueLocked(created, frame.data)
            }
        } catch (e: Exception) {
            failures++
            Log.e(TAG, "Could not start H.264 decoder", e)
            releaseCodecLocked()
        }
    }

    private fun baseFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MIME, streamWidth, streamHeight).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(streamWidth * streamHeight, 2 * 1024 * 1024))
        }

    /**
     * Mirroring needs every frame shown as soon as it is decoded. Players'
     * default decoders hold frames back for smooth playback, which with a
     * sender that only sends frames on screen changes adds seconds of delay.
     */
    private fun lowLatencyFormat(): MediaFormat = baseFormat().apply {
        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (Build.VERSION.SDK_INT >= 23) {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toFloat())
        }
        // Vendor switches (ignored by decoders that don't know them).
        setInteger("vendor.low-latency.enable", 1)                 // Qualcomm, Amlogic
        setInteger("vendor.qti-ext-dec-low-latency.enable", 1)     // Qualcomm
        setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)     // Exynos
        setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
        setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
    }

    /** Tries decoders in order of preference: hardware low-latency first. */
    private fun createConfiguredCodec(target: Surface): MediaCodec {
        var lastError: Exception? = null
        for (name in decoderCandidates()) {
            for (format in listOf(lowLatencyFormat(), baseFormat())) {
                val created = try {
                    MediaCodec.createByCodecName(name)
                } catch (e: Exception) {
                    lastError = e
                    break
                }
                try {
                    created.configure(format, target, null, 0)
                    return created
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Decoder $name rejected configuration; trying next option")
                    runCatching { created.release() }
                }
            }
        }
        throw lastError ?: IllegalStateException("No H.264 decoder available")
    }

    private fun decoderCandidates(): List<String> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                runCatching {
                    info.getCapabilitiesForType(MIME).videoCapabilities
                        .isSizeSupported(streamWidth, streamHeight)
                }.getOrDefault(true)
        }
        fun isHardware(info: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }
        fun isLowLatency(info: MediaCodecInfo): Boolean {
            val name = info.name.lowercase()
            if ("lowlatency" in name || "low_latency" in name || "low-latency" in name) return true
            return Build.VERSION.SDK_INT >= 30 && runCatching {
                info.getCapabilitiesForType(MIME)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        }
        val ordered = infos.sortedBy { info ->
            when {
                isHardware(info) && isLowLatency(info) -> 0
                isHardware(info) -> 1
                else -> 2
            }
        }.map { it.name }
        Log.i(TAG, "H.264 decoder preference: ${ordered.joinToString()}")
        return ordered
    }

    private fun queueLocked(current: MediaCodec, data: ByteArray) {
        try {
            var index = -1
            val deadline = System.nanoTime() + INPUT_WAIT_NS
            while (index < 0 && System.nanoTime() < deadline) {
                index = current.dequeueInputBuffer(10_000)
            }
            if (index < 0) {
                Log.w(TAG, "Decoder input stalled; dropping a frame")
                return
            }
            val buffer = current.getInputBuffer(index) ?: return
            buffer.clear()
            if (data.size > buffer.capacity()) {
                Log.w(TAG, "Frame of ${data.size} bytes exceeds input buffer ${buffer.capacity()}")
                current.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buffer.put(data)
            current.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Decoder failed while queueing input", e)
            failures++
            releaseCodecLocked()
            startCodecLocked()
        }
    }

    private fun drainOutput(current: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                current.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                break
            }
            when {
                index >= 0 -> {
                    val render = renderEnabled
                    try {
                        // Show it now rather than at a presentation time.
                        if (render) current.releaseOutputBuffer(index, System.nanoTime())
                        else current.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        break
                    }
                    if (render) {
                        framesRendered++
                        if (failures > 0) failures = 0
                    }
                    recordLatency(info.presentationTimeUs)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportFormat(current)
            }
        }
    }

    /** Input timestamps are the queue time, so output time minus it is the decoder's delay. */
    private fun recordLatency(queuedAtUs: Long) {
        val now = System.nanoTime()
        val latency = now / 1000 - queuedAtUs
        if (latency in 0..10_000_000) {
            latencySumUs += latency
            latencyMaxUs = maxOf(latencyMaxUs, latency)
            latencyCount++
        }
        if (now - latencyWindowStart >= 5_000_000_000L && latencyCount > 0) {
            decodeLatencyMs = latencySumUs / latencyCount / 1000.0
            Log.i(TAG, "Decode latency avg %.1f ms, max %.1f ms over %d frames".format(
                decodeLatencyMs, latencyMaxUs / 1000.0, latencyCount))
            latencySumUs = 0
            latencyMaxUs = 0
            latencyCount = 0
            latencyWindowStart = now
        }
    }

    private fun reportFormat(current: MediaCodec) {
        val format = runCatching { current.outputFormat }.getOrNull() ?: return
        var width = format.getIntegerOr(MediaFormat.KEY_WIDTH, streamWidth)
        var height = format.getIntegerOr(MediaFormat.KEY_HEIGHT, streamHeight)
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        Log.i(TAG, "Decoder output ${width}x$height")
        onVideoSize(width, height)
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int) =
        if (containsKey(key)) getInteger(key) else fallback

    private fun releaseCodecLocked() {
        val current = codec ?: return
        codec = null
        running = false
        renderEnabled = false
        outputThread?.let {
            it.join(500)
            outputThread = null
        }
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private fun offscreenSurface(): Surface {
        val reader = offscreen ?: ImageReader.newInstance(
            streamWidth, streamHeight, ImageFormat.PRIVATE, 2
        ).also { reader ->
            reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, offscreenHandler)
            offscreen = reader
        }
        return reader.surface
    }

    companion object {
        private const val TAG = "VideoDecoder"

        /** Drains the off-screen reader; independent of the caller's thread. */
        private val offscreenHandler by lazy {
            Handler(HandlerThread("VideoOffscreen").apply { start() }.looper)
        }
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MAX_GOP_FRAMES = 900
        private const val MAX_GOP_BYTES = 48L * 1024 * 1024
        private const val INPUT_WAIT_NS = 250_000_000L
        private const val MAX_FAILURES = 3
    }
}
