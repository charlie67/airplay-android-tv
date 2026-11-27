package com.airplayserver.tv

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Feeds a real H.264 stream (720x1560 High profile, one keyframe, like an
 * iPhone mirroring session) through VideoDecoder on the device's MediaCodec,
 * in the same shape the native receiver delivers it: an SPS+PPS packet, then
 * one Annex-B access unit per call.
 */
@RunWith(AndroidJUnit4::class)
class VideoDecoderTest {
    private lateinit var config: ByteArray
    private lateinit var frames: List<ByteArray>
    private val thread = HandlerThread("ImageReaders").apply { start() }
    private val readers = mutableListOf<ImageReader>()
    @Volatile private var reportedSize: Pair<Int, Int>? = null
    private val decoder = VideoDecoder { w, h -> if (w > 0) reportedSize = w to h }

    @Before
    fun loadStream() {
        val data = InstrumentationRegistry.getInstrumentation().context.assets
            .open("stream.h264").use { it.readBytes() }
        val sets = H264.splitParameterSets(data)
        assertNotNull("SPS/PPS not found", sets)
        config = sets!!.first + sets.second
        frames = splitAccessUnits(data)
        assertTrue("expected many frames, got ${frames.size}", frames.size >= 100)
    }

    @After
    fun tearDown() {
        decoder.reset()
        readers.forEach { it.close() }
        thread.quitSafely()
    }

    @Test
    fun parsesCroppedSpsSize() {
        val sps = H264.splitParameterSets(config)!!.first
        assertEquals(720 to 1560, H264.parseSpsSize(sps))
    }

    @Test
    fun decodesToSurface() {
        decoder.attachSurface(newSurface())
        decoder.onCodecConfig(config)
        frames.forEach { decoder.onFrame(it) }
        waitFor("frames rendered") { decoder.framesRendered >= frames.size - 10 }
        assertEquals(720 to 1560, reportedSize)
    }

    @Test
    fun survivesSurfaceLossMidStream() {
        // Stream starts before the UI has a surface: frames are cached.
        decoder.onCodecConfig(config)
        frames.take(30).forEach { decoder.onFrame(it) }
        assertEquals(0L, decoder.framesRendered)

        // Surface appears: the cached GOP is replayed from the keyframe.
        decoder.attachSurface(newSurface())
        frames.subList(30, 60).forEach { decoder.onFrame(it) }
        waitFor("replayed frames") { decoder.framesRendered >= 50 }

        // UI goes away mid-stream: the decoder parks off-screen and keeps going.
        decoder.detachSurface()
        frames.subList(60, 90).forEach { decoder.onFrame(it) }
        val beforeReattach = decoder.framesRendered

        // UI returns: decoding continues without a new keyframe.
        decoder.attachSurface(newSurface())
        frames.subList(90, frames.size).forEach { decoder.onFrame(it) }
        waitFor("frames after reattach") {
            decoder.framesRendered >= beforeReattach + (frames.size - 90) - 10
        }
    }

    private fun newSurface() = ImageReader.newInstance(720, 1560, ImageFormat.YUV_420_888, 3).also {
        it.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(thread.looper))
        readers.add(it)
    }.surface

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("timed out waiting for $what (rendered=${decoder.framesRendered})",
                System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    /** Splits at access unit delimiters and drops AUD/SPS/PPS, as the sender sends them separately. */
    private fun splitAccessUnits(data: ByteArray): List<ByteArray> {
        val offsets = H264.nalOffsets(data)
        val result = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        for ((i, offset) in offsets.withIndex()) {
            val end = if (i + 1 < offsets.size) startOf(data, offsets[i + 1]) else data.size
            val type = data[offset].toInt() and 0x1F
            if (type == 9) {
                if (current.size() > 0) result.add(current.toByteArray())
                current = ByteArrayOutputStream()
                continue
            }
            if (type == 7 || type == 8) continue
            current.write(byteArrayOf(0, 0, 0, 1))
            current.write(data, offset, end - offset)
        }
        if (current.size() > 0) result.add(current.toByteArray())
        return result
    }

    private fun startOf(data: ByteArray, payload: Int) =
        if (payload >= 4 && data[payload - 4].toInt() == 0) payload - 4 else payload - 3
}
