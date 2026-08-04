package com.ai.dapp.developer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ai.dapp.developer.R
import com.ai.dapp.developer.ai.AIModelManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AIInferenceService : Service() {
    
    @Inject
    lateinit var modelManager: AIModelManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val _serviceStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Stopped)
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()
    
    companion object {
        const val CHANNEL_ID = "ai_inference_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_GENERATION = "com.ai.dapp.developer.START_GENERATION"
        const val ACTION_STOP_GENERATION = "com.ai.dapp.developer.STOP_GENERATION"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_MAX_TOKENS = "max_tokens"
    }
    
    sealed class ServiceStatus {
        object Stopped : ServiceStatus()
        object Running : ServiceStatus()
        data class Error(val message: String) : ServiceStatus()
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _serviceStatus.value = ServiceStatus.Running
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GENERATION -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
                val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 512)
                startGeneration(prompt, maxTokens)
            }
            ACTION_STOP_GENERATION -> {
                stopGeneration()
            }
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    private fun startGeneration(prompt: String, maxTokens: Int) {
        serviceScope.launch {
            try {
                val result = modelManager.generateText(prompt, maxTokens)
                result.onSuccess { response ->
                    // Broadcast result
                    val broadcastIntent = Intent("com.ai.dapp.developer.GENERATION_COMPLETE")
                    broadcastIntent.putExtra("response", response)
                    sendBroadcast(broadcastIntent)
                }.onFailure { error ->
                    _serviceStatus.value = ServiceStatus.Error(error.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _serviceStatus.value = ServiceStatus.Error(e.message ?: "Generation failed")
            }
        }
    }
    
    private fun stopGeneration() {
        // Stop current generation
        _serviceStatus.value = ServiceStatus.Stopped
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Inference Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service for AI model inference"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Inference Running")
            .setContentText("Processing AI requests")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        _serviceStatus.value = ServiceStatus.Stopped
    }
}
