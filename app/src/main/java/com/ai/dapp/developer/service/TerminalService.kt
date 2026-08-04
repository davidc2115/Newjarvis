package com.ai.dapp.developer.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TerminalService : Service() {
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
}
