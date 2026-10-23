#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdint>
#include <vector>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <malloc.h>

// NDK headers hide malloc_trim below the cmake API level. Declare it weakly so
// the release build still links, and fall back to mallopt(M_PURGE).
extern "C" {
int malloc_trim(size_t pad) __attribute__((weak));
int mallopt(int option, int value) __attribute__((weak));
}
#ifndef M_PURGE
#define M_PURGE (-101)
#endif

static void release_native_heap() {
    if (malloc_trim) malloc_trim(0);
    if (mallopt) mallopt(M_PURGE, 0);
}

#include "llama.h"

#define TAG "XENA-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_mu;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static bool g_backend = false;
static std::string g_last_err = "ok";
static std::atomic<bool> g_cancel_generation{false};

static void set_err(const char *m) {
    g_last_err = m ? m : "unknown";
    LOGE("%s", g_last_err.c_str());
}

static void free_all_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

static size_t utf8_sequence_length(unsigned char lead) {
    if (lead <= 0x7f) return 1;
    if (lead >= 0xc2 && lead <= 0xdf) return 2;
    if (lead >= 0xe0 && lead <= 0xef) return 3;
    if (lead >= 0xf0 && lead <= 0xf4) return 4;
    return 1;
}

static bool valid_utf8_sequence(const std::string& text, size_t offset, size_t length) {
    const auto lead = static_cast<unsigned char>(text[offset]);
    if (length == 1) return lead <= 0x7f;
    for (size_t j = 1; j < length; ++j) {
        const auto next = static_cast<unsigned char>(text[offset + j]);
        if ((next & 0xc0) != 0x80) return false;
    }
    const auto second = static_cast<unsigned char>(text[offset + 1]);
    if (lead == 0xe0 && second < 0xa0) return false;
    if (lead == 0xed && second >= 0xa0) return false;
    if (lead == 0xf0 && second < 0x90) return false;
    if (lead == 0xf4 && second > 0x8f) return false;
    return true;
}

static size_t complete_utf8_prefix(const std::string& text) {
    size_t i = 0;
    while (i < text.size()) {
        const size_t length = utf8_sequence_length(static_cast<unsigned char>(text[i]));
        if (i + length > text.size()) break;
        if (!valid_utf8_sequence(text, i, length)) { ++i; continue; }
        i += length;
    }
    return i;
}

static jstring utf8_to_java_string(JNIEnv *env, const std::string& text) {
    std::vector<jchar> utf16;
    utf16.reserve(text.size());
    size_t i = 0;
    while (i < text.size()) {
        const auto lead = static_cast<unsigned char>(text[i]);
        const size_t length = utf8_sequence_length(lead);
        uint32_t codepoint = 0xfffd;
        if (i + length <= text.size() && valid_utf8_sequence(text, i, length)) {
            if (length == 1) codepoint = lead;
            else if (length == 2) codepoint = ((lead & 0x1f) << 6) | (static_cast<unsigned char>(text[i + 1]) & 0x3f);
            else if (length == 3) codepoint = ((lead & 0x0f) << 12) | ((static_cast<unsigned char>(text[i + 1]) & 0x3f) << 6) | (static_cast<unsigned char>(text[i + 2]) & 0x3f);
            else codepoint = ((lead & 0x07) << 18) | ((static_cast<unsigned char>(text[i + 1]) & 0x3f) << 12) | ((static_cast<unsigned char>(text[i + 2]) & 0x3f) << 6) | (static_cast<unsigned char>(text[i + 3]) & 0x3f);
            i += length;
        } else {
            ++i;
        }
        if (codepoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
}

static void emit_stream_text(JNIEnv *env, jobject callback, jmethodID on_token, const std::string& text) {
    if (!callback || !on_token || text.empty()) return;
    jstring chunk = utf8_to_java_string(env, text);
    if (!chunk) return;
    env->CallVoidMethod(callback, on_token, chunk);
    env->DeleteLocalRef(chunk);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_xena_ai_LlamaBridge_nativeLastError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    return env->NewStringUTF(g_last_err.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_xena_ai_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jclass,
        jstring modelPath, jint n_ctx, jint n_batch, jint n_threads,
        jboolean useMmap, jboolean useMlock) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!modelPath) {
        set_err("Path model kosong.");
        return JNI_FALSE;
    }
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        set_err("Gagal membaca path model dari Java.");
        return JNI_FALSE;
    }
    LOGI("loadModel path=%s ctx=%d batch=%d thr=%d mmap=%d",
         path, (int)n_ctx, (int)n_batch, (int)n_threads, (int)useMmap);

    // basic path check
    FILE *fp = fopen(path, "rb");
    if (!fp) {
        set_err("Tidak bisa buka file GGUF (path/izin). Salin ke folder app.");
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
    fseek(fp, 0, SEEK_END);
    long sz = ftell(fp);
    fclose(fp);
    if (sz < 50 * 1024 * 1024L) {
        set_err("File GGUF terlalu kecil / rusak.");
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
    LOGI("gguf size bytes=%ld", sz);

    if (!g_backend) {
        llama_backend_init();
        g_backend = true;
    }
    free_all_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = (useMmap == JNI_TRUE);
    mparams.use_mlock = (useMlock == JNI_TRUE);

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!g_model) {
        set_err("llama_model_load_from_file gagal (RAM kurang / GGUF tidak didukung). Coba ctx lebih kecil atau tutup app lain.");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 1024;
    cparams.n_batch = n_batch > 0 ? (uint32_t)n_batch : 64;
    cparams.n_threads = n_threads > 0 ? (int32_t)n_threads : 2;
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        set_err("llama_init_from_model gagal (RAM KV/context). Turunkan context ke 512–1024.");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    g_last_err = "ok";
    LOGI("model loaded ok ctx=%d", (int)cparams.n_ctx);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xena_ai_LlamaBridge_nativeIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xena_ai_LlamaBridge_nativeFreeModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    free_all_locked();
    release_native_heap();
}

JNIEXPORT void JNICALL
Java_com_xena_ai_LlamaBridge_nativeCancelGeneration(JNIEnv *, jclass) {
    // nativeGenerate holds g_mu while decoding, so cancellation must not lock it.
    g_cancel_generation.store(true, std::memory_order_release);
}

JNIEXPORT jstring JNICALL
Java_com_xena_ai_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass,
        jstring jprompt, jfloat temp, jfloat topP, jfloat repeatPenalty, jobject callback) {
    jmethodID on_token = nullptr;
    if (callback) {
        jclass callback_class = env->GetObjectClass(callback);
        if (callback_class) {
            on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(callback_class);
        }
        if (!on_token) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return env->NewStringUTF("[XENA] Callback streaming tidak tersedia");
        }
    }
    std::lock_guard<std::mutex> lock(g_mu);
    g_cancel_generation.store(false, std::memory_order_release);
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("[XENA] Model belum dimuat");
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) return env->NewStringUTF("[XENA] Prompt kosong atau gagal dibaca");
    std::string prompt_str = prompt ? prompt : "";
    env->ReleaseStringUTFChars(jprompt, prompt);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens;
    tokens.resize(prompt_str.size() + 64);
    int n = llama_tokenize(vocab, prompt_str.c_str(), (int32_t)prompt_str.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    if (n < 0) {
        tokens.resize((size_t)(-n));
        n = llama_tokenize(vocab, prompt_str.c_str(), (int32_t)prompt_str.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (n < 0) {
        return env->NewStringUTF("[XENA] Gagal tokenize prompt");
    }
    tokens.resize((size_t)n);

    llama_kv_cache_clear(g_ctx);

    int32_t n_batch = (int32_t)llama_n_batch(g_ctx);
    if (n_batch < 1) n_batch = 64;

    for (int i = 0; i < (int)tokens.size(); i += n_batch) {
        if (g_cancel_generation.load(std::memory_order_acquire)) return env->NewStringUTF("[XENA_CANCELLED]");
        int n_eval = std::min(n_batch, (int)tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
        if (llama_decode(g_ctx, batch) != 0) {
            return env->NewStringUTF("[XENA] llama_decode gagal (prompt)");
        }
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    if (!smpl) return env->NewStringUTF("[XENA] Sampler tidak dapat dibuat karena RAM tidak cukup");
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0.f ? topP : 0.9f, 1));
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repeatPenalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp > 0.f ? temp : 0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    out.reserve(1024);
    std::string pending_utf8;
    const int max_new = 256;
    for (int i = 0; i < max_new; i++) {
        if (g_cancel_generation.load(std::memory_order_acquire)) {
            llama_sampler_free(smpl);
            return env->NewStringUTF("[XENA_CANCELLED]");
        }
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char buf[256];
        int npiece = llama_token_to_piece(vocab, id, buf, (int32_t)sizeof(buf), 0, true);
        if (npiece > 0) {
            out.append(buf, (size_t)npiece);
            if (callback && on_token) {
                pending_utf8.append(buf, (size_t)npiece);
                const size_t ready_bytes = complete_utf8_prefix(pending_utf8);
                if (ready_bytes > 0) {
                    emit_stream_text(env, callback, on_token, pending_utf8.substr(0, ready_bytes));
                    pending_utf8.erase(0, ready_bytes);
                }
            }
        }
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (g_cancel_generation.load(std::memory_order_acquire)) {
            llama_sampler_free(smpl);
            return env->NewStringUTF("[XENA_CANCELLED]");
        }
        if (llama_decode(g_ctx, batch) != 0) break;
        llama_sampler_accept(smpl, id);
    }
    llama_sampler_free(smpl);
    if (!pending_utf8.empty()) emit_stream_text(env, callback, on_token, pending_utf8);

    if (out.empty()) {
        return env->NewStringUTF("[XENA] Output kosong");
    }
    return utf8_to_java_string(env, out);
}

} // extern C
