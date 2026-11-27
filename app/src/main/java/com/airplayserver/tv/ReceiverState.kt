package com.airplayserver.tv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide receiver state shared between the service and the UI. */
object ReceiverState {
    enum class Status { STOPPED, STARTING, READY, CONNECTED, ERROR }

    data class Snapshot(
        val status: Status = Status.STOPPED,
        val receiverName: String = "",
        val addresses: List<String> = emptyList(),
        val clientName: String? = null,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val senderPaused: Boolean = false,
        val error: String? = null,
    )

    /** Video decoder and audio output live here so the activity can attach its surface. */
    val video = VideoDecoder { width, height ->
        update { it.copy(videoWidth = width, videoHeight = height) }
    }
    val audio = AudioPlayer()

    @Volatile
    var current = Snapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(transform: (Snapshot) -> Snapshot) {
        synchronized(this) { current = transform(current) }
        main.post {
            val snapshot = current
            listeners.forEach { it(snapshot) }
        }
    }

    /** Adds a listener (called on the main thread) and delivers the current state. */
    fun observe(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeObserver(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}
