package com.example.tidewidget

import android.app.Application
import com.example.tidewidget.widget.TideRefreshWorker

class TideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TideRefreshWorker.schedulePeriodic(this)
    }
}
