# Xena-AI

**v1.5.1** — Menambahkan pembersihan RAM dan sisa unduhan, animasi pembersih, serta pintasan Model AI dari menu chat. Server Mode memakai Groq Chat Completions; Mode GGUF lokal tetap tersedia.

- Panduan Groq: [`docs/MIGRASI_GROQ_v1.5.0.md`](docs/MIGRASI_GROQ_v1.5.0.md)
- Unduh APK: [GitHub Releases](https://github.com/v1-byte/Xena-Ai/releases)

## Koneksi Groq

Buat API key dari [Groq Console](https://console.groq.com/keys). Di aplikasi, buka **Atur → Koneksi Groq**, masukkan key yang diawali `gsk_`, lalu tekan **Simpan API key**. Aktifkan **Server Mode: ON** dan gunakan **Tes koneksi**.

Groq menerbitkan model `openai/gpt-oss-20b` sebagai production model. Kuota Free dibatasi menurut model dan organisasi; dokumentasi mencantumkan 1.000 permintaan/hari dan 8.000 token/menit untuk model ini. Developer tier menggunakan harga per token dan mensyaratkan metode pembayaran. Batas dan harga dapat berubah; cek [model/harga](https://console.groq.com/docs/models), [rate limits](https://console.groq.com/docs/rate-limits), dan billing akun sebelum penggunaan. Tombol tes koneksi juga mengirim satu permintaan inference.

Migrasi ini menghapus konektor inference HF dan konfigurasi Space lama. Hugging Face masih dipakai sebagai sumber unduhan beberapa berkas model GGUF di katalog lokal; berkas tersebut terpisah dari koneksi chat Groq.

## Mode lokal

Mode lokal memakai katalog GGUF 1.5B/3B/7B yang bisa diunduh dari tab **Model**. Di HP dengan RAM 6 GB, profil awal memakai context 1024, batch 32, dan 2 threads. Model lokal tidak diperlukan saat Server Mode aktif.

## Pembersih RAM & sisa unduhan

Di tab **Model** (atau menu sudut FAB di chat):

- **Bersihkan RAM** — unload model lokal, `System.gc()`, clear cache WebView.
- **Hapus Sisa Download** — hapus `.part` / `.part.properties` dan GGUF tidak valid (tidak menyentuh model aktif).
- **Bersihkan Semua** — keduanya, dengan animasi orb + spark.

Tombol **Model AI** di menu sudut FAB membuka tab Model dan menampilkan label model aktif.

## Build lokal

Butuh JDK 17, Android SDK, NDK 26, dan CMake 3.22:

```bash
./gradlew assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.
