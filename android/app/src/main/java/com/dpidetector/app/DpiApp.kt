package com.dpidetector.app

import android.app.Application
import com.dpidetector.app.data.DpiEngine

class DpiApp : Application() {
    lateinit var engine: DpiEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = DpiEngine(this)
    }
}
