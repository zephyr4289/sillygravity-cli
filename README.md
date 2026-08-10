# SillyGravity 🌌

SillyGravity is a powerful, on-device Local LLM (Large Language Model) Inference Server designed specifically for Android. By leveraging the highly optimized `llama.cpp` engine with native Vulkan GPU acceleration, SillyGravity allows you to run advanced AI models directly on your smartphone—no cloud, no subscriptions, and complete privacy.

SillyGravity runs a background Ktor server that exposes an **OpenAI-compatible HTTP API** (`/v1/chat/completions`) over localhost. This makes it incredibly easy to connect external CLI agents, Termux scripts, or local web apps directly to the on-device AI.

---

## ✨ Features

- **🚀 Native Vulkan GPU Acceleration:** Offload heavy tensor computations directly to your mobile GPU (Snapdragon Adreno, MediaTek Mali, etc.) using `ggml-vulkan`.
- **🔌 OpenAI-Compatible API:** Drop-in replacement for OpenAI endpoints. Tools pointing to `http://127.0.0.1:8080/v1` will seamlessly interact with your local model.
- **📱 Beautiful Reactive UI:** Built with modern Jetpack Compose. Features a real-time status badge and an auto-scrolling terminal window that pipes native C++ diagnostics and HTTP request logs directly to your screen.
- **🛡️ Bulletproof Background Execution:** Utilizes Android 14+ `FOREGROUND_SERVICE_SPECIAL_USE` APIs and Partial WakeLocks to ensure the LLM continues generating responses even when minimized.
- **🧠 Efficient Memory Management:** Safely maps heavy 1GB+ GGUF weights into Android virtual memory (`mmap`) while requesting `largeHeap` allocations to prevent OS OOM kills.

---

## 📸 Interface

The app provides a streamlined dashboard:
1. **Model Downloader:** Enter a direct URL to a `.gguf` file (e.g., Qwen 1.5B Instruct) and download it directly to internal storage.
2. **Dynamic Status Badge:** Instantly know if the engine is `OFFLINE`, `LOADING MODEL...`, `ONLINE`, or if a `SERVER ERROR` occurred.
3. **Live Terminal Logs:** Watch the magic happen. The embedded terminal displays native `llama.cpp` initialization times, Vulkan driver states, and incoming `/v1/chat/completions` HTTP requests.

---

## 🛠️ Architecture

SillyGravity bridges three primary layers:
1. **The Native Engine (C++):** A JNI bridge (`LlamaBridge.cpp`) interfacing directly with `llama.cpp`. It handles Vulkan initialization, memory mapping, and token generation.
2. **The Background Service (Kotlin):** A robust Android `Service` running a lightweight `Ktor` Netty server on port `8080`.
3. **The User Interface (Compose):** A reactive UI that subscribes to a global `TerminalLogger` state flow, ensuring zero-lag UI updates and smooth auto-scrolling.

---

## 🚀 Getting Started

### 1. Build and Install
SillyGravity is built via GitHub Actions CI/CD. Simply push to the `main` branch to trigger an automatic release build, or build it locally using Android Studio/Gradle.

### 2. Download a Model
Open the app and provide a URL to a quantized GGUF model. For mobile devices, it is highly recommended to use **Q4_K_M** or **Q8_0** quants of 1B - 3B parameter models (e.g., Qwen2.5-1.5B, Llama-3.2-1B, or Gemma-2B).

### 3. Start the Server
Tap **Start Server**. Watch the terminal as the model loads into RAM and the Ktor socket binds to `127.0.0.1:8080`.

### 4. Connect an Agent
From Termux or any local HTTP client, send a standard OpenAI payload:
```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "Hello, SillyGravity!"}
    ]
  }'
```

---

## ⚠️ Notes & Limitations

- **RAM Requirements:** Ensure your device has at least 6GB of physical RAM. Allocating a 1.5B parameter model + KV cache requires approximately 1.5GB - 2GB of contiguous memory.
- **Background Restrictions:** OEM battery savers (like MIUI or ColorOS) may attempt to kill the server if left running in the background for hours. The app prompts to ignore battery optimizations on launch to mitigate this.

---

*Built with ❤️ using `llama.cpp`, Kotlin, Ktor, and Jetpack Compose.*
