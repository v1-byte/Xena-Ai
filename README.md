# Xena-AI

Aplikasi Android untuk menjalankan model **GGUF lokal** via `llama.cpp`.  
UI HTML custom di WebView + bridge Kotlin. Model **tidak** dibundel di APK.

## Fokus v1.0.7
- **Anti-Error** system prompt (tidak pernah kosong)
- **Auto Mode** — auto load GGUF yang sudah ada di HP
- Setting aman **RAM 6GB**: ctx 2048, batch 128, threads 4, temp 0.2
- UI full HTML (`app/src/main/assets/index.html`)

## Model
Target: `qwen2.5-coder-7b-instruct-q3_k_m.gguf` (~3.55 GB)

Lokasi yang dicari `ModelAutoLoader`:
- `context.filesDir/qwen2.5-coder-7b-instruct-q3_k_m.gguf`
- `context.filesDir/model.gguf`
- `/data/data/com.xena.ai/files/...`
- migrasi `/data/data/com.dte.ai/files/...`
- `Download/qwen2.5-coder-7b-instruct-q3_k_m.gguf`

## Native library
Butuh `libllama.so` (arm64-v8a) di:
```
app/src/main/jniLibs/arm64-v8a/libllama.so
```
Tanpa file ini, auto-load akan gagal dengan pesan native/lib.

## Build
```bash
git clone https://github.com/v1-byte/Xena-Ai.git
cd Xena-Ai
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Catatan RAM 6+6
- Matikan Extended RAM (+6GB virtual) jika lag
- Battery → Unrestricted
- Sisakan ~10 GB storage
- Tutup WA/Chrome/game sebelum buka app

## Lisensi
Private project.
