package com.almus.studio

import android.app.Application

class AlmusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
