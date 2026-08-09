package com.example.llm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestBatteryExemption()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        onStartService = {
                            startForegroundService(Intent(this, LLMService::class.java))
                        },
                        onStopService = {
                            stopService(Intent(this, LLMService::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@Composable
fun DashboardScreen(onStartService: () -> Unit, onStopService: () -> Unit) {
    var modelUrl by remember { mutableStateOf("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") }
    var downloadProgress by remember { mutableStateOf(0f) }
    var hardwareStats by remember { mutableStateOf("Fetching stats...") }

    // Minimal hardware polling (simulated loop in compose for brevity)
    LaunchedEffect(Unit) {
        while (true) {
            val temp = try {
                File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toInt() / 1000
            } catch (e: Exception) { 0 }
            hardwareStats = "Temp: ${temp}°C | Active Cores: ${Runtime.getRuntime().availableProcessors()}"
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Localhost Bridge Dashboard", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Hardware: $hardwareStats")
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = modelUrl,
            onValueChange = { modelUrl = it },
            label = { Text("Model GGUF URL") },
            modifier = Modifier.fillMaxWidth()
        )
        
        LinearProgressIndicator(progress = downloadProgress, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { /* Launch download coroutine logic */ }) { Text("Download") }
            Button(onClick = onStartService) { Text("Start Server") }
            Button(onClick = onStopService) { Text("Stop Server") }
        }
    }
}
