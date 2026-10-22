# Xena-AI

**Versi aplikasi: v1.6.3** — aplikasi Android untuk menjalankan model GGUF lokal dengan opsi Groq Server Mode. Rilis ini juga memiliki pembersih RAM native, mode ringan untuk perangkat dengan memori terbatas, pemindai model yang sudah diunduh, dan pemeriksaan pembaruan APK dari GitHub Releases.

- APK terbaru: [GitHub Releases](https://github.com/v1-byte/Xena-Ai/releases)
- Catatan UI terbaru: [`docs/CATATAN_UI_V1.2_RELEASE.md`](docs/CATATAN_UI_V1.2_RELEASE.md)
- Audit total dan fitur advanced: [`docs/AUDIT_DAN_FITUR_ADVANCED.md`](docs/AUDIT_DAN_FITUR_ADVANCED.md)
- Catatan sistem dan auto-update: [`docs/SISTEM_TERKINI.md`](docs/SISTEM_TERKINI.md)
- Blueprint/roadmap jangka panjang: [`docs/BLUEPRINT_PRO.md`](docs/BLUEPRINT_PRO.md)
- Migrasi Groq: [`docs/MIGRASI_GROQ_v1.5.0.md`](docs/MIGRASI_GROQ_v1.5.0.md)

## Fitur aktif

### UI terbaru

Aplikasi memakai UI WebView full-screen dengan empat tab utama:

- **Chat** — percakapan lokal atau Groq, streaming jawaban, markdown, code block, salin/simpan kode, riwayat chat, tema, dan command palette.
- **Model** — katalog model GGUF, download resumable, pause/resume/cancel, validasi file, impor dari Download, pencarian file model, load, benchmark, dan kontrol pembersihan.
- **Konektor** — GitHub, Cloudflare, dan Meta dengan token lokal; konektor inference Hugging Face sudah dihapus dari sistem chat.
- **Atur** — prompt sistem, profil model, tema dark/light/hacker/cyberpunk, Groq Server Mode, PIN, preferensi, diagnostik, dan versi aplikasi.

Tampilan juga memiliki splash screen, status jaringan, bottom navigation, kartu model, progress transfer, animasi thinking, bubble chat, code block bergaya editor, haptic feedback, dan layout yang dioptimalkan untuk layar HP.

### Auto-update APK

Saat aplikasi dibuka atau kembali aktif, updater memeriksa release terbaru dari:

```text
https://api.github.com/repos/v1-byte/Xena-Ai/releases/latest
```

Jika tersedia versi baru, aplikasi mengunduh asset `app-release.apk`, memeriksa sertifikat signing APK, lalu membuka installer resmi Android. Android tetap meminta konfirmasi **Install**; aplikasi biasa tidak dapat mengganti APK secara diam-diam tanpa hak sistem/root.

Agar update in-place berhasil, semua APK release harus ditandatangani dengan keystore yang sama. Rilis lama yang masih memakai debug key mungkin memerlukan satu kali uninstall/install ulang. Setelah migrasi ke stable signing key, pembaruan berikutnya dapat dipasang tanpa menghapus data aplikasi, chat, konfigurasi, atau model GGUF.

### Mode lokal dan model

Mode lokal memakai katalog GGUF 1.5B/3B/7B. Download mendukung byte range/resume, file sementara `.part`, metadata transfer, validasi magic number GGUF, dan pencarian model dari:

- folder Download Android;
- `Download/Xena-AI/Models`;
- penyimpanan app-specific Xena-AI;
- file model yang sudah diimpor.

Pada perangkat RAM terbatas, aplikasi melakukan preflight sebelum memuat model. Model yang terlalu berat dapat ditolak untuk mencegah force close. Pengaturan konservatif v1.6.3 menggunakan context 1024, batch 32, dan 2 threads sebagai baseline 6 GB; konfigurasi dapat disesuaikan dari tab **Atur**.

### Pembersih RAM dan mode ringan

Pembersih RAM tidak hanya memanggil `System.gc()`. Komponen native melepas model llama.cpp, membersihkan context native, meminta trim heap, dan membersihkan cache WebView yang dapat direklamasi. `onTrimMemory()` juga memicu mode ringan ketika Android memberi tekanan memori.

Tersedia tiga tindakan di tab **Model**:

- **Bersihkan RAM** — unload model lokal dan membersihkan resource yang dapat direklamasi.
- **Hapus Sisa Download** — menghapus file `.part`, metadata parsial, dan GGUF invalid tanpa menyentuh model aktif.
- **Bersihkan Semua** — menjalankan kedua tindakan di atas.

Tidak ada pembersih RAM yang dapat menjamin seluruh RAM perangkat menjadi kosong karena sebagian RAM dikelola Android dan aplikasi lain. Sistem ini menargetkan resource Xena-AI yang memang dapat dilepas dengan aman.

## Koneksi Groq

Buat API key dari [Groq Console](https://console.groq.com/keys). Di aplikasi, buka **Atur → Koneksi Groq**, masukkan key yang diawali `gsk_`, simpan, lalu aktifkan **Server Mode** dan gunakan **Tes koneksi**.

API key Groq disimpan menggunakan Android Keystore. Key tidak dimasukkan ke source code, workflow, atau APK. Hugging Face hanya digunakan sebagai sumber file GGUF publik, bukan sebagai koneksi chat.

## Build lokal

Toolchain build:

- JDK 17
- Android SDK platform 34
- NDK 26.1.10909125
- CMake 3.22.1
- Gradle 8.2.1

Build debug:

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Build release membutuhkan keystore stable. CI membaca secret berikut dari GitHub Actions:

```text
XENA_SIGNING_KEYSTORE_BASE64
XENA_SIGNING_KEYSTORE_PASSWORD
```

Workflow release akan menaikkan tag berdasarkan `versionName`, membangun `app-release.apk`, memverifikasi `lib/arm64-v8a/libllama.so`, memeriksa signing certificate, dan membuat GitHub Release.

## Pengujian

Test statis proyek mencakup parsing UI, katalog model, download resumable, validasi GGUF, profil RAM, pembersih native, release workflow, dan updater APK:

```bash
node --test tests/model-download-release.test.mjs
```

## Privasi dan batasan

Chat lokal dan model GGUF tetap berada di perangkat. Groq Server Mode mengirim prompt ke Groq sesuai pilihan pengguna. Auto-update hanya mengunduh APK release dari repo resmi dan tetap menggunakan installer Android. Jangan menaruh PAT GitHub di source code, README, APK, atau pesan chat.

## Lisensi

Private project.
