package com.example.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class LLMService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var ktorServer: ApplicationEngine? = null

    init {
        System.loadLibrary("llama-bridge")
    }

    external fun initEngine(modelPath: String): Boolean
    external fun destroyEngine()

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LLMService::ExecutionWakelock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes max initially, renew dynamically*/)

        val modelPath = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("model_path", "")
        if (modelPath != null && modelPath.isNotEmpty()) {
            initEngine(modelPath)
        }

        startLocalServer()
    }

    private fun startLocalServer() {
        ktorServer = embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
            routing {
                post("/v1/chat/completions") {
                    // Simulating an SSE stream for completion
                    call.respondTextWriter(contentType = io.ktor.http.ContentType.Text.EventStream) {
                        write("data: {\"choices\":[{\"delta\":{\"content\":\"Hello from Android NDK!\"}}]}\n\n")
                        flush()
                        delay(100)
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }.start(wait = false)
    }

    private fun createNotification(): Notification {
        val channelId = "llm_service_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "LLM Engine", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("LLM Engine Active")
            .setContentText("Localhost API running at 127.0.0.1:8080")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        ktorServer?.stop(1000, 2000)
        destroyEngine()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
