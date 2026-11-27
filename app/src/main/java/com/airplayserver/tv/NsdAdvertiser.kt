package com.airplayserver.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the receiver over mDNS/DNS-SD with Android's NsdManager,
 * replacing Bonjour for Windows. TXT records match AirPlayServerLib's
 * dnssd.c / dnssdint.h / global.h.
 */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val active = mutableListOf<NsdManager.RegistrationListener>()

    @Synchronized
    fun register(name: String, hardwareAddress: ByteArray, raopPort: Int, airplayPort: Int) {
        unregister()
        val features = "0x%X,0x%X".format(FEATURES_1, FEATURES_2)

        val raop = NsdServiceInfo().apply {
            serviceName = hex(hardwareAddress, upper = true, separator = "") + "@" + name
            serviceType = "_raop._tcp"
            port = raopPort
            setAttribute("txtvers", "1")
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,3")
            setAttribute("et", "0,3,5")
            setAttribute("sv", "false")
            setAttribute("da", "true")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("pw", "false")
            setAttribute("vn", "3")
            setAttribute("tp", "TCP,UDP")
            setAttribute("md", "0,1,2")
            setAttribute("vs", VERSION)
            setAttribute("sm", "false")
            setAttribute("ek", "1")
            setAttribute("sf", "0x4")
            setAttribute("ft", features)
            setAttribute("am", MODEL)
        }
        val airplay = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_airplay._tcp"
            port = airplayPort
            setAttribute("srcvers", VERSION)
            setAttribute("deviceid", hex(hardwareAddress, upper = false, separator = ":"))
            setAttribute("features", features)
            setAttribute("model", MODEL)
            setAttribute("flags", "0x4")
            setAttribute("pw", "false")
            setAttribute("vv", "2")
        }
        registerOne(raop)
        registerOne(airplay)
    }

    @Synchronized
    fun unregister() {
        for (listener in active) {
            runCatching { nsd.unregisterService(listener) }
        }
        active.clear()
    }

    private fun registerOne(info: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(TAG, "Advertising ${registered.serviceName} (${info.serviceType}) on port ${info.port}")
            }

            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Could not advertise ${info.serviceType}: error $errorCode")
                synchronized(this@NsdAdvertiser) { active.remove(this) }
            }

            override fun onServiceUnregistered(unregistered: NsdServiceInfo) {}
            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            active.add(listener)
        } catch (e: Exception) {
            Log.e(TAG, "registerService failed for ${info.serviceType}", e)
        }
    }

    private fun hex(bytes: ByteArray, upper: Boolean, separator: String): String =
        bytes.joinToString(separator) {
            val s = "%02x".format(it.toInt() and 0xFF)
            if (upper) s.uppercase() else s
        }

    companion object {
        private const val TAG = "NsdAdvertiser"
        // Mirroring + audio only; URL/HLS playback is not advertised (global.h).
        private const val FEATURES_1 = 0x5A7FFEE6L
        private const val FEATURES_2 = 0x0L
        private const val MODEL = "AppleTV14,1"
        private const val VERSION = "845.5.1"
    }
}
