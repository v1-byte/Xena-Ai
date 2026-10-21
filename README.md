# Xena-AI

**v1.4.1** — default **Server Mode** (HuggingFace Space) untuk HP 6GB, dengan panel URL Space yang terlihat di tab **Atur**.
Local GGUF tetap opsional (1.5B / 3B / 7B).

- Dok migrasi: [`docs/MIGRASI_HF_SERVER_v1.4.0.md`](docs/MIGRASI_HF_SERVER_v1.4.0.md)
- Template Space: [`docs/hf-space/`](docs/hf-space/)

Aplikasi Android untuk model **GGUF lokal** (target Qwen2.5-Coder 7B Q3_K_M ~3.55 GB).  
UI HTML v1.2 full-screen WebView + bridge Kotlin `window.Android`.

## Status v1.2.0
- ✅ UI v1.2 (Chat / Model / Konektor / Atur)
- ✅ Auto-load model path
- ✅ Anti-error system prompt
- ✅ Connectors (token lokal)
- ✅ JNI library `libllama` (stub aman — ganti dengan llama.cpp asli untuk inference)
- ⚠️ Inference penuh butuh `libllama.so` hasil build llama.cpp (bukan stub)

## Struktur
```
app/src/main/
  assets/index.html          # UI utama
  java/com/xena/ai/          # MainActivity, LlamaBridge, AppPrefs, ModelAutoLoader, Connectors
  cpp/                       # JNI stub (libllama.so)
  jniLibs/arm64-v8a/         # Opsional: taruh .so prebuilt di sini (override stub)
```

## Build lokal
```bash
# Butuh JDK 17 + Android SDK + NDK 26 + CMake 3.22
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Atur koneksi Hugging Face
Di aplikasi buka tab **Atur** → **Koneksi Hugging Face Space**, masukkan URL HTTPS Space Anda (Space harus berstatus **Running**), lalu tekan **Simpan URL**. Aktifkan **Server Mode: ON** untuk memakai server tanpa memuat GGUF lokal. Tombol **Tes koneksi** mengecek apakah Space merespons.

## Model di HP
Letakkan salah satu:
- `Android/data/com.xena.ai/files/qwen2.5-coder-7b-instruct-q3_k_m.gguf`
- `/storage/emulated/0/Download/qwen2.5-coder-7b-instruct-q3_k_m.gguf`
- Migrasi dari `com.dte.ai/files/...`

## Ganti stub → llama.cpp asli
1. Build `libllama.so` + JNI bindings untuk package `com.xena.ai.LlamaBridge`
2. Metode native: `nativeLoadModel`, `nativeGenerate`, `nativeIsLoaded`, `nativeFreeModel`
3. Taruh di `app/src/main/jniLibs/arm64-v8a/libllama.so` **atau** ganti `app/src/main/cpp/`

## Connectors
Token disimpan lokal (SharedPreferences). GitHub test mengecek repo & `libllama.so`.

## RAM 6 GB
Context 2048 · batch 128 · threads 4 · temp 0.2 · mmap on · mlock off

## Lisensi
Private project.
