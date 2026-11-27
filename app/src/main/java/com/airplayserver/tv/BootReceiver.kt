package com.airplayserver.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver at boot when "Start when the TV turns on" is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.startOnBoot && prefs.backgroundMode) AirPlayService.start(context)
    }
}
