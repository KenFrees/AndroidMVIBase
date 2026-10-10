package com.ttxz.base

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.ttxz.base.logging.Logging
import com.ttxz.base.logging.LogConfig

@HiltAndroidApp
class BaseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logging.initialize(this, LogConfig(isDebugLog = true, logcatMinLevel = null))
    }
}
