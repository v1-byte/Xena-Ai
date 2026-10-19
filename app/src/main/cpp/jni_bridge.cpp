#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>

#include "llama.h"

#define TAG "XENA-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_mu;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static bool g_backend = false;

static void free_all_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_xena_ai_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jclass,
        jstring modelPath, jint n_ctx, jint n_batch, jint n_threads,
        jboolean useMmap, jboolean useMlock) {
    std::lock_guard<std::mutex> lock(g_mu);
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("loadModel path=%s ctx=%d batch=%d thr=%d mmap=%d mlock=%d",
         path, (int)n_ctx, (int)n_batch, (int)n_threads, (int)useMmap, (int)useMlock);

    if (!g_backend) {
        llama_backend_init();
        g_backend = true;
    }
    free_all_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = useMmap == JNI_TRUE;
    mparams.use_mlock = useMlock == JNI_TRUE;

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("failed to load model");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 2048;
    cparams.n_batch = n_batch > 0 ? (uint32_t)n_batch : 128;
    cparams.n_threads = n_threads > 0 ? (int32_t)n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("model loaded ok");
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
    LOGI("model freed");
}

JNIEXPORT jstring JNICALL
Java_com_xena_ai_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass,
        jstring jprompt, jfloat temp, jfloat topP, jfloat repeatPenalty) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("[XENA] Model belum dimuat");
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt_str = prompt ? prompt : "";
    env->ReleaseStringUTFChars(jprompt, prompt);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    // tokenize
    std::vector<llama_token> tokens;
    tokens.resize(prompt_str.size() + 32);
    int n = llama_tokenize(vocab, prompt_str.c_str(), (int32_t)prompt_str.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, prompt_str.c_str(), (int32_t)prompt_str.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (n < 0) {
        return env->NewStringUTF("[XENA] Gagal tokenize prompt");
    }
    tokens.resize(n);

    // clear memory / kv
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    // eval prompt in batches
    int32_t n_batch = (int32_t)llama_n_batch(g_ctx);
    if (n_batch < 1) n_batch = 128;

    for (int i = 0; i < (int)tokens.size(); i += n_batch) {
        int n_eval = std::min(n_batch, (int)tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
        if (llama_decode(g_ctx, batch) != 0) {
            return env->NewStringUTF("[XENA] llama_decode gagal (prompt)");
        }
    }

    // sampler chain
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        /*n_vocab*/ 0, /*special*/ LLAMA_TOKEN_NULL, /*linefeed*/ LLAMA_TOKEN_NULL,
        /*penalty_last_n*/ 64, /*penalty_repeat*/ repeatPenalty > 0 ? repeatPenalty : 1.1f,
        /*penalty_freq*/ 0.0f, /*penalty_present*/ 0.0f, false, false));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp > 0 ? temp : 0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    out.reserve(1024);
    const int max_new = 512;
    for (int i = 0; i < max_new; i++) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        char buf[256];
        int npiece = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (npiece > 0) out.append(buf, npiece);

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
        llama_sampler_accept(smpl, id);
    }
    llama_sampler_free(smpl);

    if (out.empty()) {
        return env->NewStringUTF("[XENA] Output kosong — coba context 2048 / model Q3");
    }
    return env->NewStringUTF(out.c_str());
}

} // extern C
