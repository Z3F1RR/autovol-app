package io.github.z3f1rr.autovol

import android.app.Application

class AutoVolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoVol.init(this)
        Notifications.createChannels(this)
    }
}
