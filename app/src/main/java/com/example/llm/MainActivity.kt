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
import androidx.compose.ui.graphics.Color
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.URL
import java.net.HttpURLConnection
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.widget.Toast

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

fun isGgufValid(file: File): Boolean {
    if (!file.exists() || file.length() < 1000000) return false // Must be > 0 bytes
    // Check GGUF Magic Header bytes (0x46 0x47 0x55 0x47 -> "GGUF")
    return try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4) return false
            val magic = String(header)
            magic == "GGUF"
        }
    } catch (e: Exception) {
        false
    }
}

@Composable
fun DashboardScreen(onStartService: () -> Unit, onStopService: () -> Unit) {
    val context = LocalContext.current
    val serverState by ServerStatus.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var modelUrl by remember { mutableStateOf("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") }
    var downloadProgress by remember { mutableStateOf(0f) }
    var hardwareStats by remember { mutableStateOf("Fetching stats...") }
    var isDownloading by remember { mutableStateOf(false) }
    
    val targetFile = remember { File(context.filesDir, "model.gguf") }
    var isDownloaded by remember { mutableStateOf(targetFile.exists()) }

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
            Button(
                onClick = {
                    if (isDownloading) return@Button
                    isDownloading = true
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val url = URL(modelUrl)
                            val connection = url.openConnection() as HttpURLConnection
                            connection.connect()
                            val fileLength = connection.contentLength
                            
                            val file = File(context.filesDir, "model.gguf")
                            val input = java.io.BufferedInputStream(url.openStream())
                            val output = java.io.FileOutputStream(file)
                            
                            val data = ByteArray(1024 * 64)
                            var total: Long = 0
                            var count: Int
                            
                            while (input.read(data).also { count = it } != -1) {
                                total += count
                                if (fileLength > 0) {
                                    downloadProgress = (total.toFloat() / fileLength.toFloat())
                                }
                                output.write(data, 0, count)
                            }
                            output.flush()
                            output.close()
                            input.close()
                            
                            if (!isGgufValid(targetFile)) {
                                throw Exception("Downloaded file is not a valid GGUF model!")
                            }
                            
                            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putString("model_path", targetFile.absolutePath).apply()
                            
                            withContext(Dispatchers.Main) {
                                isDownloaded = true
                                Toast.makeText(context, "Model downloaded & saved successfully!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isDownloading = false
                        }
                    }
                },
                enabled = !isDownloading && !isDownloaded
            ) { 
                Text(
                    if (isDownloaded) "Already Downloaded" 
                    else if (isDownloading) "Downloading..." 
                    else "Download"
                ) 
            }
            val buttonText = when (serverState) {
                ServerState.STOPPED, ServerState.ERROR -> "Start Server"
                ServerState.STARTING -> "Initializing Engine..."
                ServerState.RUNNING -> "Stop Server"
            }
            val buttonEnabled = when (serverState) {
                ServerState.STARTING -> false
                else -> isDownloaded && isGgufValid(targetFile)
            }
            Button(
                onClick = {
                    if (serverState == ServerState.RUNNING) {
                        onStopService()
                    } else {
                        onStartService()
                    }
                },
                enabled = buttonEnabled
            ) { Text(buttonText) }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val dotColor = when (serverState) {
                ServerState.STOPPED -> Color.Red
                ServerState.STARTING -> Color.Yellow
                ServerState.RUNNING -> Color(0xFF00FF66)
                ServerState.ERROR -> Color.Magenta
            }
            val statusText = when (serverState) {
                ServerState.STOPPED -> "OFFLINE"
                ServerState.STARTING -> "LOADING MODEL..."
                ServerState.RUNNING -> "ONLINE (127.0.0.1:8080)"
                ServerState.ERROR -> "SERVER ERROR"
            }
            
            if (serverState == ServerState.STARTING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = dotColor, strokeWidth = 2.dp)
            } else {
                Box(modifier = Modifier.size(12.dp).background(dotColor, shape = androidx.compose.foundation.shape.CircleShape))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(statusText, color = dotColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        TerminalView()
    }
}
