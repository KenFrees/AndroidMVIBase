package com.ttxz.base

import com.ttxz.base.common.application.BaseApplication
import dagger.hilt.android.HiltAndroidApp
import com.ttxz.base.logging.Logging
import com.ttxz.base.logging.LogConfig

@HiltAndroidApp
class AppApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        Logging.initialize(this, LogConfig(isDebugLog = true, logcatMinLevel = null))
    }
}
