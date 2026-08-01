package com.example.sms

import android.app.Application
import com.example.sms.sms.NotificationHelper

class SmsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        NotificationHelper(this).ensureChannels()
    }
}
