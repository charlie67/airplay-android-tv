package com.airplayserver.tv

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.Display
import java.security.SecureRandom

class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("airplay_settings", Context.MODE_PRIVATE)

    enum class Resolution(val key: String, val label: String, val width: Int, val height: Int) {
        MATCH_TV("auto", "Match TV", 0, 0),
        HD("720p", "1280 × 720", 1280, 720),
        FULL_HD("1080p", "1920 × 1080", 1920, 1080),
        QHD("1440p", "2560 × 1440", 2560, 1440),
        UHD("2160p", "3840 × 2160 (4K)", 3840, 2160);

        companion object {
            fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: FULL_HD
        }
    }

    var receiverName: String
        get() = sp.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(value) = sp.edit().putString(KEY_NAME, sanitizeName(value)).apply()

    var resolution: Resolution
        get() = Resolution.fromKey(sp.getString(KEY_RESOLUTION, null))
        set(value) = sp.edit().putString(KEY_RESOLUTION, value.key).apply()

    /** Keep the receiver discoverable after leaving the app. */
    var backgroundMode: Boolean
        get() = sp.getBoolean(KEY_BACKGROUND, false)
        set(value) = sp.edit().putBoolean(KEY_BACKGROUND, value).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_BOOT, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT, value).apply()

    /**
     * Android does not expose the real MAC address, so a random, stable,
     * locally administered one is used as the AirPlay device ID.
     */
    val hardwareAddress: ByteArray
        get() = persistentBytes(KEY_HWADDR, 6) { bytes ->
            bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        }

    /** Seed for the Ed25519 pairing identity, so senders keep recognising the TV. */
    val pairingSeed: ByteArray
        get() = persistentBytes(KEY_SEED, 32) {}

    /** The display size advertised to senders. */
    @Suppress("DEPRECATION")
    fun displaySize(): Pair<Int, Int> {
        val res = resolution
        if (res != Resolution.MATCH_TV) return res.width to res.height
        val display = app.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 1920 to 1080
        val (w, h) = if (Build.VERSION.SDK_INT >= 23) {
            display.mode.physicalWidth to display.mode.physicalHeight
        } else {
            val size = android.graphics.Point()
            display.getRealSize(size)
            size.x to size.y
        }
        var width = maxOf(w, h)
        var height = minOf(w, h)
        if (width > 3840) {
            height = height * 3840 / width
            width = 3840
        }
        return if (width <= 0 || height <= 0) 1920 to 1080 else width to height
    }

    private fun defaultName(): String {
        val name = Settings.Global.getString(app.contentResolver, "device_name")
        return sanitizeName(name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV")
    }

    private fun persistentBytes(key: String, size: Int, adjust: (ByteArray) -> Unit): ByteArray {
        synchronized(LOCK) {
            sp.getString(key, null)?.let { stored ->
                val decoded = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
                if (decoded != null && decoded.size == size) return decoded
            }
            val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
            adjust(bytes)
            sp.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
            return bytes
        }
    }

    companion object {
        private const val KEY_NAME = "receiver_name"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_BACKGROUND = "background_mode"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_HWADDR = "hardware_address"
        private const val KEY_SEED = "pairing_seed"
        private val LOCK = Any()

        /** mDNS labels are limited to 63 bytes; the RAOP name adds a 13-byte prefix. */
        private const val MAX_NAME_BYTES = 50

        fun sanitizeName(value: String): String {
            var name = value.replace(Regex("[\\p{Cntrl}@.]"), " ").trim().replace(Regex("\\s+"), " ")
            while (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
                name = name.dropLast(1)
            }
            return name.trim().ifEmpty { "Android TV" }
        }
    }
}
