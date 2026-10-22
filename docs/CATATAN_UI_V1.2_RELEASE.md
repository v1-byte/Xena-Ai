# Xena-AI UI — Catatan Terkini

**Versi UI:** v1.5.6
**Sumber utama:** `app/src/main/assets/index.html` dan `app/src/main/assets/model-manager.js`
**Shell:** Android WebView full-screen dengan bridge Kotlin `window.Android`

Dokumen v1.2 sebelumnya sudah diperbarui. UI yang dipaketkan pada branch `main` sekarang mencakup seluruh fitur berikut.

## Navigasi dan tampilan

- Splash screen dengan progress dan status boot.
- Header XENA dengan status online/offline.
- Bottom navigation empat tab: **Chat**, **Model**, **Konektor**, **Atur**.
- Tema dark, light, hacker, dan cyberpunk.
- Layout responsif untuk layar HP, safe-area inset, kartu, progress bar, status dot, toast, haptic feedback, dan animasi thinking.
- Command palette untuk membuka fungsi umum termasuk cek update APK.

## Chat

- Chat lokal melalui `Android.chat` dengan callback token dan selesai.
- Groq Server Mode melalui bridge native.
- Riwayat chat tersimpan di local storage UI.
- Bubble user/assistant, markdown dasar, list, quote, code block, copy, simpan, jalankan HTML, dan push GitHub.
- Tombol stop saat inference berlangsung.
- Attachment file teks dan input suara jika WebView mendukung Speech Recognition.
- Empty state dengan quick prompt dan status model aktif.

## Model manager

- Katalog GGUF 1.5B, 3B, dan 7B termasuk WhiteRabbitNeo V3.
- Download dengan progress, kecepatan, ETA, pause, resume, cancel, dan retry.
- Byte-range/resumable download dengan `.part` dan metadata.
- Validasi GGUF sebelum model dianggap siap.
- Import dari Android Download melalui SAF dan pencarian file model lokal.
- Informasi storage, benchmark, status load, dan peringatan RAM.
- Pembersih **Bersihkan RAM**, **Hapus Sisa Download**, dan **Bersihkan Semua**.
- Pemilih mode ringan untuk mengurangi risiko force close pada perangkat dengan RAM terbatas.

## Konektor

Konektor aktif di UI:

- GitHub
- Cloudflare
- Meta

Token konektor disimpan lokal. Token tidak ditanam di source code atau APK. Konektor inference Hugging Face sudah dihapus dari alur chat; Hugging Face hanya menjadi sumber file GGUF publik.

## Pengaturan

- System prompt dan template prompt.
- Context, batch, threads, dan temperature.
- Groq API key dengan penyimpanan terenkripsi Android Keystore.
- Groq Server Mode ON/OFF dan Tes koneksi.
- Tema, ukuran teks, radius, animasi, efek visual, haptic, dan Enter-to-send.
- PIN lock, export/import setting, reset setting, dan reset semua data.
- Informasi perangkat, RAM, CPU, jaringan, dan versi APK aktual.

## Auto-update APK

Menu command palette dan pemeriksaan startup memanggil updater native. Updater:

1. Mengecek GitHub Releases terbaru.
2. Mengunduh `app-release.apk` yang memiliki versi lebih baru.
3. Memverifikasi signing certificate.
4. Membuka installer Android resmi.
5. Melanjutkan installer setelah izin sumber tidak dikenal diaktifkan.

Android tetap menampilkan konfirmasi instalasi. Tidak ada silent install untuk APK biasa.

## Bridge native utama

| Metode | Fungsi |
|---|---|
| `Android.chat(prompt, paramsJson)` | Generate lokal/Groq dan callback `window.xenaToken`/`window.xenaDone` |
| `Android.stop()` | Menghentikan generate |
| `Android.loadModel()` | Menyiapkan dan memuat GGUF |
| `Android.storageInfo()` | Mengembalikan `{used,total}` dalam GB |
| `Android.checkUpdate(force)` | Mengecek dan mengunduh update APK |
| `Android.getAppVersion()` | Mengembalikan versi APK terpasang |
| `Android.isModelReady()` | Status model lokal |
| `Android.saveConfig(...)` | Menyimpan parameter inference |

## Build

```bash
./gradlew assembleDebug
```

Output debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Untuk update produksi gunakan `app-release.apk` dari GitHub Releases yang dibangun oleh workflow signing stable. File `libllama.so` arm64 dipaketkan melalui build native.
