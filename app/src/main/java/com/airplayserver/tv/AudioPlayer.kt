package com.airplayserver.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Plays decoded AirPlay PCM through AudioTrack on its own thread, so the
 * network thread never blocks on audio output. Keeps latency bounded by
 * dropping the oldest audio when the backlog grows.
 */
class AudioPlayer {
    private class Chunk(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

    private val queue = ArrayBlockingQueue<Chunk>(QUEUE_CAPACITY)
    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var flushRequested = false
    @Volatile private var gain = 1f
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0

    // Audio-thread-only bookkeeping for keeping playback close to live.
    private var framesWritten = 0L
    private var droppedChunks = 0
    private var bufferedSumMs = 0L
    private var bufferedSamples = 0
    private var statsWindowStart = 0L

    fun enqueue(pcm: ByteArray, sampleRate: Int, channels: Int) {
        ensureThread()
        val chunk = Chunk(pcm, sampleRate, channels)
        if (!queue.offer(chunk)) {
            queue.poll()
            queue.offer(chunk)
        }
    }

    /** AirPlay volume in dB: 0 is full volume; -144 (or below -30) is mute. */
    fun setVolumeDb(volumeDb: Float) {
        gain = if (volumeDb <= -30f) 0f else 10f.pow(volumeDb / 20f).coerceIn(0f, 1f)
        synchronized(lock) { track?.setVolume(gain) }
    }

    fun flush() {
        queue.clear()
        flushRequested = true
    }

    fun stop() {
        val current: Thread?
        synchronized(lock) {
            running = false
            current = thread
            thread = null
        }
        current?.interrupt()
        current?.join(500)
        queue.clear()
        gain = 1f
    }

    private fun ensureThread() {
        synchronized(lock) {
            if (running) return
            running = true
            thread = Thread(::playLoop, "AudioOutput").also { it.start() }
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                val chunk = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                // Catch up if output fell behind the sender.
                while (queue.size > MAX_BACKLOG) queue.poll()
                val output = trackFor(chunk.sampleRate, chunk.channels) ?: continue
                if (flushRequested) {
                    flushRequested = false
                    runCatching { output.pause(); output.flush(); output.play() }
                    framesWritten = 0
                }
                val bytesPerFrame = chunk.channels * 2
                if (shouldDrop(output, chunk.sampleRate)) {
                    droppedChunks++
                    continue
                }
                val written = output.write(chunk.pcm, 0, chunk.pcm.size)
                if (written > 0) framesWritten += written / bytesPerFrame
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio output failed", e)
        } finally {
            synchronized(lock) {
                track?.let { runCatching { it.stop() }; it.release() }
                track = null
                trackRate = 0
                trackChannels = 0
                if (thread == Thread.currentThread()) {
                    thread = null
                    running = false
                }
            }
        }
    }

    /**
     * The TV's minimum AudioTrack buffer can hold a quarter second or more, and
     * a full buffer means audio plays that far behind the picture. Keep at most
     * [TARGET_LATENCY_MS] queued ahead of the playback position, skipping a
     * ~11 ms packet when the sender gets ahead.
     */
    private fun shouldDrop(output: AudioTrack, sampleRate: Int): Boolean {
        val played = output.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val bufferedMs = ((framesWritten - played).coerceAtLeast(0) * 1000 / sampleRate)
        val now = System.nanoTime()
        bufferedSumMs += bufferedMs
        bufferedSamples++
        if (now - statsWindowStart >= 5_000_000_000L) {
            if (statsWindowStart != 0L) {
                Log.i(TAG, "Audio buffered avg ${bufferedSumMs / bufferedSamples} ms, " +
                    "skipped $droppedChunks packets")
            }
            bufferedSumMs = 0
            bufferedSamples = 0
            droppedChunks = 0
            statsWindowStart = now
        }
        // Until playback starts the head does not move; let the start threshold fill.
        if (played == 0L) return false
        return bufferedMs > TARGET_LATENCY_MS
    }

    private fun trackFor(sampleRate: Int, channels: Int): AudioTrack? {
        track?.let { if (trackRate == sampleRate && trackChannels == channels) return it }
        synchronized(lock) {
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) {
                Log.e(TAG, "Unsupported audio format $sampleRate Hz x $channels")
                return null
            }
            // The smallest buffer the device allows; the fill level is managed in shouldDrop().
            val bufferBytes = minBuffer
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val created = runCatching { builder.build() }.getOrElse {
                Log.e(TAG, "Could not create AudioTrack", it)
                return null
            }
            created.setVolume(gain)
            if (Build.VERSION.SDK_INT >= 31) {
                // Start playing after a few packets instead of after a full buffer.
                runCatching { created.setStartThresholdInFrames(START_THRESHOLD_FRAMES) }
            }
            created.play()
            framesWritten = 0
            track = created
            trackRate = sampleRate
            trackChannels = channels
            Log.i(TAG, "Audio output $sampleRate Hz, $channels ch, buffer $bufferBytes bytes")
            return created
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val QUEUE_CAPACITY = 96 // ~1 s of 480-sample frames
        private const val MAX_BACKLOG = 8     // ~90 ms waiting to be written
        private const val TARGET_LATENCY_MS = 100
        private const val START_THRESHOLD_FRAMES = 1440 // ~33 ms
    }
}
