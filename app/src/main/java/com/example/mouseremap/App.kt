package com.example.mouseremap

import android.app.Application
import rikka.shizuku.Shizuku

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Bridge.init(this)
        // Shizukuが(再)起動してbinderが届いたら自動で接続し直す
        Shizuku.addBinderReceivedListenerSticky { Bridge.bind() }
        Shizuku.addBinderDeadListener { Bridge.onDead() }
    }
}
