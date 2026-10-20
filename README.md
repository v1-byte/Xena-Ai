# Xena-AI

Aplikasi Android untuk model **GGUF lokal** (target Qwen2.5-Coder 7B Q3_K_M ~3.55 GB).  
UI HTML v1.2 full-screen WebView + bridge Kotlin `window.Android`.

## Status v1.3.6
- UI WebView (Chat / Model / Konektor / Atur)
- Model manager untuk unduh dan memilih beberapa model GGUF
- Import model memakai Android file picker
- JNI bridge dibangun dengan llama.cpp melalui CMake
- Default hemat RAM: context 1024, batch 32, threads 2, mmap aktif, mlock mati

## Struktur
```
app/src/main/
  assets/index.html          # UI utama
  java/com/xena/ai/          # MainActivity, LlamaBridge, AppPrefs, ModelAutoLoader, Connectors
  cpp/                       # JNI bridge dan konfigurasi CMake
  jniLibs/arm64-v8a/         # Opsional: taruh .so prebuilt di sini
```

## Build lokal
```bash
# Butuh JDK 17 + Android SDK + NDK 26 + CMake 3.22
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Model di HP
Unduh model dari tab **Model** agar langsung tersimpan di `Android/data/com.xena.ai/files/models/`. Untuk file yang sudah ada di Download, pilih **Pilih GGUF dari Download**; Android 10+ tidak mengizinkan aplikasi memindai folder Download secara langsung. Impor membuat salinan privat, lalu aplikasi menawarkan untuk menghapus file sumber setelah berhasil.

Model yang tersedia:
- Qwen2.5-Coder 1.5B Q4_K_M
- Qwen2.5-Coder 3B Q3_K_M
- Qwen2.5-Coder 7B Q2_K
- Qwen2.5-Coder 7B Q3_K_M

## Connectors
Token disimpan lokal (SharedPreferences). GitHub test mengecek repo & `libllama.so`.

## RAM 6 GB
Context 1024 · batch 32 · threads 2 · temp 0.2 · mmap on · mlock off

## APK updater
Updater hanya mengunduh release dengan versi lebih baru, memeriksa SHA-256 bila tersedia, lalu membuka installer Android. Android tetap meminta konfirmasi pemasangan; instalasi diam-diam tidak didukung untuk aplikasi biasa.

GitHub Actions membuat release bertanda tangan hanya jika empat repository secrets berikut sudah diatur:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Gunakan keystore yang sama dengan APK yang sudah terpasang. Tanpa tanda tangan yang cocok, Android menolak update di atas instalasi lama. Jangan gunakan APK debug untuk update antar-build.

## Lisensi
Private project.
