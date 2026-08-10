#include <jni.h>
#include <string>
#include <thread>
#include <sched.h>
#include <sys/mman.h>
#include <android/log.h>
#include <vector>
#include <algorithm>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaBridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaBridge", __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;

static JavaVM* g_jvm = nullptr;
static jclass g_loggerClass = nullptr;
static jmethodID g_loggerMethodID = nullptr;

JNIEnv* getJniEnv() {
    JNIEnv* env = nullptr;
    if (!g_jvm) return nullptr;
    int getEnvStat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

void llama_log_callback(ggml_log_level level, const char * text, void * user_data) {
    if (text == NULL || strlen(text) == 0) return;
    
    JNIEnv *env = getJniEnv();
    if (env && g_loggerMethodID && g_loggerClass) {
        // text from llama.cpp often ends with newline, strip it to prevent double-spacing
        std::string str(text);
        if (!str.empty() && str.back() == '\n') {
            str.pop_back();
        }
        jstring jmsg = env->NewStringUTF(str.c_str());
        env->CallStaticVoidMethod(g_loggerClass, g_loggerMethodID, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/example/llm/TerminalLogger");
    if (env->ExceptionCheck() || localClass == nullptr) {
        env->ExceptionClear();
        return JNI_ERR;
    }
    
    g_loggerClass = (jclass)env->NewGlobalRef(localClass);
    env->DeleteLocalRef(localClass);
    
    g_loggerMethodID = env->GetStaticMethodID(g_loggerClass, "log", "(Ljava/lang/String;)V");
    if (env->ExceptionCheck() || g_loggerMethodID == nullptr) {
        env->ExceptionClear();
        return JNI_ERR;
    }
    
    llama_log_set(llama_log_callback, nullptr);
    
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llm_LLMService_initEngine(JNIEnv* env, jobject /* this */, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    std::string pathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);
    
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP; // Allows OS to page weights safely
    model_params.n_gpu_layers = 99; // Try Vulkan GPU offload first

    LOGI("Attempting model load with Vulkan GPU acceleration...");
    bool gpuSuccess = false;
    try {
        g_model = llama_model_load_from_file(pathStr.c_str(), model_params);
        if (g_model) {
            gpuSuccess = true;
            LOGI("Model loaded successfully with Vulkan GPU offload.");
        }
    } catch (const std::exception& e) {
        LOGE("Vulkan GPU load failed with exception: %s", e.what());
        g_model = nullptr;
    } catch (...) {
        LOGE("Vulkan GPU load failed with unknown C++ exception.");
        g_model = nullptr;
    }

    // Gracefully fall back to ARM CPU if Vulkan load failed or threw an exception
    if (!g_model) {
        LOGI("Falling back to CPU model loading...");
        llama_log_callback(GGML_LOG_LEVEL_WARN, "[WARN] Vulkan GPU offload unsupported or failed. Falling back to CPU inference.", nullptr);
        
        model_params.n_gpu_layers = 0; // CPU only
        try {
            g_model = llama_model_load_from_file(pathStr.c_str(), model_params);
        } catch (const std::exception& e) {
            LOGE("CPU model load failed with exception: %s", e.what());
            g_model = nullptr;
        } catch (...) {
            LOGE("CPU model load failed with unknown exception.");
            g_model = nullptr;
        }
    }

    if (!g_model) {
        LOGE("Model load failed on both GPU and CPU: %s", pathStr.c_str());
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096; // Constrained context for 6GB RAM devices
    int hardwareThreads = std::thread::hardware_concurrency();
    ctx_params.n_threads = (hardwareThreads > 0) ? std::min(4, hardwareThreads) : 4;
    ctx_params.type_k = GGML_TYPE_Q8_0; // Force Q8_0 KV Cache to save RAM
    ctx_params.type_v = GGML_TYPE_Q8_0;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Context creation failed.");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Llama backend initialized successfully (GPU=%s, threads=%d).", gpuSuccess ? "true" : "false", ctx_params.n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llm_LLMService_destroyEngine(JNIEnv* env, jobject /* this */) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}

// In a full implementation, you would expose a streaming token generator here.

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llm_LLMService_generateResponse(JNIEnv* env, jobject /* this */, jstring promptStr) {
    if (!g_model || !g_ctx) return env->NewStringUTF("Error: Model not loaded");

    const char* prompt = env->GetStringUTFChars(promptStr, 0);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(promptStr, prompt);

    // Remove KV cache clear for now since API changed, tracking is automatic
    
    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens_list(prompt_str.length() + 4);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.length(), tokens_list.data(), tokens_list.size(), true, false);
    if (n_tokens < 0) {
        tokens_list.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.length(), tokens_list.data(), tokens_list.size(), true, false);
    }
    tokens_list.resize(n_tokens);

    if (n_tokens == 0) {
        return env->NewStringUTF("");
    }

    llama_batch batch = llama_batch_get_one(tokens_list.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        return env->NewStringUTF("Error: llama_decode failed");
    }

    std::string result = "";
    int n_max_predict = 128; // Limit for demo
    
    struct llama_sampler* smpl = llama_sampler_init_greedy();
    
    for (int i = 0; i < n_max_predict; i++) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, new_token_id);
        
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }
        
        char buf[128] = {0};
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n >= 0) {
            result += std::string(buf, n);
        }
        
        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
    }
    
    llama_sampler_free(smpl);

    return env->NewStringUTF(result.c_str());
}
