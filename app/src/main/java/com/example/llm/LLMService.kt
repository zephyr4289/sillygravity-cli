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
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

enum class ServerState {
    STOPPED,   // Server is offline
    STARTING,  // Loading model weights & binding Ktor socket
    RUNNING,   // Active and listening on 127.0.0.1:8080
    ERROR      // Model load or port binding failed
}

object ServerStatus {
    val state = MutableStateFlow(ServerState.STOPPED)
}

class LLMService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var ktorServer: ApplicationEngine? = null
    private var statsJob: kotlinx.coroutines.Job? = null

    init {
        System.loadLibrary("llama-bridge")
    }

    external fun initEngine(modelPath: String): Boolean
    external fun destroyEngine()
    external fun generateResponse(prompt: String): String

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, createNotification("Initializing..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification("Initializing..."))
        }
        
        TerminalLogger.log("Foreground service started.")
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LLMService::ExecutionWakelock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes max initially, renew dynamically*/)

        val modelPath = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("model_path", "")
        if (modelPath != null && modelPath.isNotEmpty()) {
            val file = File(modelPath)
            startServer(file)
        }

        startStatsLoop()
    }

    private fun startServer(modelFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServerStatus.state.value = ServerState.STARTING
                TerminalLogger.log("----------------------------------------")
                TerminalLogger.log("[INIT] Starting LLM Foreground Service...")
                TerminalLogger.log("[INIT] Verifying model file: ${modelFile.name}")
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    TerminalLogger.log("[ERROR] GGUF Model file not found or empty!")
                    ServerStatus.state.value = ServerState.ERROR
                    return@launch
                }
                
                TerminalLogger.log("[INIT] Loading model into native C++ memory...")
                val startTime = System.currentTimeMillis()
                
                // JNI Model Load Call
                val success = initEngine(modelFile.absolutePath)
                if (!success) {
                    TerminalLogger.log("[ERROR] JNI Native model initialization failed!")
                    ServerStatus.state.value = ServerState.ERROR
                    return@launch
                }
                
                val loadTime = System.currentTimeMillis() - startTime
                TerminalLogger.log("[SUCCESS] Engine loaded successfully in ${loadTime}ms")
                TerminalLogger.log("[NET] Binding Ktor HTTP Server to 127.0.0.1:8080...")
                
                startLocalServer()
                
                ServerStatus.state.value = ServerState.RUNNING
                TerminalLogger.log("[ONLINE] Server active at http://127.0.0.1:8080/v1")
                TerminalLogger.log("----------------------------------------")
            } catch (e: Exception) {
                TerminalLogger.log("[FATAL] Exception while starting server: ${e.localizedMessage}")
                ServerStatus.state.value = ServerState.ERROR
            }
        }
        startStatsLoop()
    }

    private fun startStatsLoop() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        statsJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val availMB = memInfo.availMem / (1024 * 1024)
                val totalMB = memInfo.totalMem / (1024 * 1024)
                
                updateNotification("RAM Free: ${availMB}MB / ${totalMB}MB")
                delay(3000)
            }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(text))
    }

    private fun startLocalServer() {
        ktorServer = embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
            routing {
                post("/v1/chat/completions") {
                    val body = call.receiveText()
                    // Hacky extraction of the last user prompt for demo purposes
                    val promptRegex = "\"content\"\\s*:\\s*\"(.*?)\"".toRegex()
                    val match = promptRegex.findAll(body).lastOrNull()
                    val prompt = match?.groups?.get(1)?.value ?: "Hello!"
                    
                    TerminalLogger.log("Incoming request: $prompt")
                    TerminalLogger.log("Generating response...")
                    
                    val rawResponse = generateResponse(prompt)
                    val responseText = rawResponse.replace("\n", "\\n").replace("\"", "\\\"")

                    call.respondTextWriter(contentType = io.ktor.http.ContentType.Text.EventStream) {
                        write("data: {\"choices\":[{\"delta\":{\"content\":\"$responseText\"}}]}\n\n")
                        flush()
                        delay(10)
                        write("data: [DONE]\n\n")
                        flush()
                    }
                    TerminalLogger.log("Response sent successfully.")
                }
            }
        }.start(wait = false)
        TerminalLogger.log("Ktor Server listening on 127.0.0.1:8080")
    }

    private fun createNotification(text: String = "Localhost API running at 127.0.0.1:8080"): Notification {
        val channelId = "llm_service_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "LLM Engine", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("LLM Engine Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        statsJob?.cancel()
        ktorServer?.stop(1000, 2000)
        destroyEngine()
        wakeLock?.let { if (it.isHeld) it.release() }
        ServerStatus.state.value = ServerState.STOPPED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
