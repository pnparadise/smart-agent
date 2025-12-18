package com.smart

import android.app.Application

class SmartAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SmartRuleManager.init(this)
    }
}
