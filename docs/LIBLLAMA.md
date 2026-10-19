# libllama.so (v1.2.1+)

APK sekarang **membangun** `libllama.so` dari [llama.cpp](https://github.com/ggerganov/llama.cpp) saat compile (CMake FetchContent + JNI `jni_bridge.cpp`).

## API native
- `nativeLoadModel(path, n_ctx, n_batch, n_threads, mmap, mlock)`
- `nativeGenerate(prompt, temp, topP, repeatPenalty)`
- `nativeIsLoaded()` / `nativeFreeModel()`

## Model
Letakkan GGUF di HP, mis.  
`/storage/emulated/0/Download/qwen2.5-coder-7b-instruct-q3_k_m.gguf`

## Catatan
Build CI lebih lama (~10–30 menit) karena compile llama.cpp untuk arm64-v8a.
