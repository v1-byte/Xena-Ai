#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "XENA-llama-stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static bool g_loaded = false;
static std::string g_path;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_xena_ai_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jclass,
        jstring modelPath, jint n_ctx, jint n_batch, jint n_threads,
        jboolean useMmap, jboolean useMlock) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    g_path = path ? path : "";
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("stub loadModel path=%s ctx=%d batch=%d threads=%d", g_path.c_str(), (int)n_ctx, (int)n_batch, (int)n_threads);
    // Stub: "succeed" only if path looks like a file name (real inference still not available)
    // Return false so UI shows honest state; generate will explain.
    g_loaded = false;
    (void)useMmap; (void)useMlock;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xena_ai_LlamaBridge_nativeIsLoaded(JNIEnv *, jclass) {
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xena_ai_LlamaBridge_nativeFreeModel(JNIEnv *, jclass) {
    g_loaded = false;
    g_path.clear();
}

JNIEXPORT jstring JNICALL
Java_com_xena_ai_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass,
        jstring prompt, jfloat temp, jfloat topP, jfloat repeatPenalty) {
    (void)prompt; (void)temp; (void)topP; (void)repeatPenalty;
    const char *msg =
        "[XENA] Runtime native masih STUB.\n"
        "Ganti app/src/main/cpp dengan build llama.cpp + JNI asli, "
        "atau taruh libllama.so (arm64-v8a) di jniLibs.\n"
        "UI, konektor, dan auto-load path sudah siap.";
    return env->NewStringUTF(msg);
}

} // extern "C"
