#include <jni.h>
#include <string>
#include <thread>
#include <sched.h>
#include <sys/mman.h>
#include <android/log.h>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaBridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaBridge", __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llm_LLMService_initEngine(JNIEnv* env, jobject /* this */, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    
    llama_backend_init();

    // Aggressively pin to Prime/Performance cores (e.g., 6 and 7 on 8-core Snapdragon)
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(6, &cpuset);
    CPU_SET(7, &cpuset);
    if (sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) != 0) {
        LOGE("Failed to pin threads to performance cores.");
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mlock = true; // Prevent weights from being swapped to ZRAM/storage
    model_params.n_gpu_layers = 99; // Maximize Vulkan offload

    g_model = llama_model_load_from_file(path, model_params);
    if (!g_model) {
        LOGE("Model load failed: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096; // Constrained context for 6GB RAM devices
    ctx_params.n_threads = 2; // Match pinned prime cores
    ctx_params.type_k = GGML_TYPE_Q8_0; // Force Q8_0 KV Cache to save RAM
    ctx_params.type_v = GGML_TYPE_Q8_0;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Context creation failed.");
        llama_free_model(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("Llama backend initialized securely in memory.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llm_LLMService_destroyEngine(JNIEnv* env, jobject /* this */) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
    llama_backend_free();
}

// In a full implementation, you would expose a streaming token generator here.
