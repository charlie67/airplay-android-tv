package com.airplayserver.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class App : Application() {
    private val handler = Handler(Looper.getMainLooper())

    /** Stops the receiver after the UI goes away, unless background mode is on. */
    private val stopIfHidden = Runnable {
        if (visibleActivities == 0 && !Prefs(this).backgroundMode) AirPlayService.stop(this)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
                handler.removeCallbacks(stopIfHidden)
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivities--
                if (visibleActivities <= 0) {
                    visibleActivities = 0
                    handler.postDelayed(stopIfHidden, 1500)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        @Volatile
        var visibleActivities = 0
            private set
    }
}
