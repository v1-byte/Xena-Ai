# Xena-AI UI v1.2 — Release notes

Tanggal: 2026-10-20  
Sumber: index.html (72937 bytes) dijadikan `app/src/main/assets/index.html`.

## Isi UI
- Splash + shell 4 tab (Chat, Model, Konektor, Atur)
- Tema: dark / light / hacker / cyberpunk
- Chat streaming demo + native `Android.chat`
- Model: auto load, progress, storage
- Konektor: GitHub, Cloudflare, Meta, HuggingFace (token lokal)
- Pengaturan lengkap + PIN lock

## Bridge native yang diharapkan UI
| Metode | Keterangan |
|--------|------------|
| `Android.chat(prompt, paramsJson)` | Generate; callback `window.xenaToken(t)` + `window.xenaDone()` |
| `Android.stop()` | Hentikan generate |
| `Android.loadModel()` | Muat GGUF; callback `window.xenaModelReady(ok)` |
| `Android.storageInfo()` | JSON `{used,total}` GB |

Interface name di WebView: **`Android`** (bukan AndroidBridge).

## Build
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

Masih diperlukan: `app/src/main/jniLibs/arm64-v8a/libllama.so` agar native chat/load jalan.
