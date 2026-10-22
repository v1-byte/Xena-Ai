# Xena-AI — Sistem Terkini

**Status dokumen:** aktif, mengikuti branch `main` dan APK `v1.6.2`.

## Ringkasan

Xena-AI adalah aplikasi Android hybrid: model GGUF dapat berjalan lokal melalui llama.cpp/JNI, sementara Groq Server Mode tersedia untuk perangkat atau penggunaan yang memerlukan inference cloud. UI utama tetap memakai WebView full-screen dengan bridge Kotlin `window.Android`.

## Auto-update APK

`UpdateChecker` memeriksa GitHub Releases secara berkala dan saat aplikasi kembali aktif. Endpoint publik yang digunakan:

```text
https://api.github.com/repos/v1-byte/Xena-Ai/releases/latest
```

Alurnya:

1. Membaca `tag_name` dan asset APK release.
2. Memilih `app-release.apk` terlebih dahulu; jika tidak ada, memilih asset APK release yang sesuai.
3. Membandingkan semantic version dengan versi APK terpasang.
4. Mengunduh APK ke cache aplikasi.
5. Memeriksa ukuran file dan sertifikat signing agar APK tidak menggantikan aplikasi dengan kunci berbeda.
6. Jika izin install belum aktif, membuka Settings Android dan menyimpan APK sebagai pending install.
7. Saat pengguna kembali ke aplikasi setelah mengaktifkan izin, installer Android dibuka otomatis.
8. Pengguna tetap mengonfirmasi instalasi karena Android melarang silent self-update untuk aplikasi biasa.

Semua release produksi harus memakai keystore yang sama. CI memakai `XENA_SIGNING_KEYSTORE_BASE64` dan `XENA_SIGNING_KEYSTORE_PASSWORD`. Release debug lama mungkin membutuhkan satu kali migrasi uninstall/install.

## UI aktif

UI saat ini memiliki empat tab:

- **Chat:** chat lokal/Groq, streaming callback, markdown ringan, code block, copy/save/push GitHub, riwayat, prompt, tema, dan command palette.
- **Model:** katalog GGUF, download resumable, pause/resume/cancel, validasi file, impor dari Download, pencarian file lokal, load, benchmark, dan pembersih.
- **Konektor:** GitHub, Cloudflare, Meta, dan pengujian koneksi token lokal.
- **Atur:** prompt sistem, parameter inference, tema, Groq Server Mode, PIN, diagnostik, dan informasi versi.

Tema yang tersedia: dark, light, hacker, dan cyberpunk. Layout menggunakan kartu responsif, status dot, bottom navigation, splash screen, progress bar, orb/spark animation, dan code block gelap.

## Memory management

Pembersih RAM bekerja pada resource yang dimiliki aplikasi, bukan menjanjikan seluruh RAM perangkat kosong. Operasi utama:

- unload model dan context llama.cpp;
- native cleanup dan `malloc_trim` bila tersedia;
- WebView cache cleanup;
- pemantauan `onTrimMemory`;
- mode ringan dengan konfigurasi inference konservatif saat tekanan RAM tinggi;
- preflight RAM sebelum memuat GGUF besar.

Tindakan pengguna tersedia di tab Model: **Bersihkan RAM**, **Hapus Sisa Download**, dan **Bersihkan Semua**.

## Model file manager

Pencarian model memeriksa folder Download Android, `Download/Xena-AI/Models`, penyimpanan app-specific, dan file yang pernah diimpor. File GGUF divalidasi menggunakan metadata dan magic number sebelum dipindahkan atau dimuat. File parsial tidak dianggap sebagai model aktif.

Download model mendukung byte range/resume ketika server mengizinkan, mempertahankan metadata parsial, dan menghapus hasil yang invalid.

## Keamanan

- Groq API key disimpan dengan Android Keystore.
- Tidak ada PAT GitHub di source code atau APK.
- APK updater memverifikasi signing certificate sebelum instalasi.
- Aplikasi hanya meminta permission yang diperlukan untuk internet, media/model, kamera, dan package installation sesuai alur Android.
- Chat lokal tidak dikirim ke cloud kecuali pengguna memilih Groq Server Mode.

## Release CI

Workflow `.github/workflows/build.yml`:

- membaca `versionName` dari Gradle;
- menjalankan test UI/model/release;
- membangun `assembleRelease`;
- memakai stable keystore GitHub Actions;
- memverifikasi APK dan `lib/arm64-v8a/libllama.so`;
- membandingkan signing certificate dengan release sebelumnya;
- membuat GitHub Release dengan asset `app-release.apk`.

## Batasan yang harus tetap dijelaskan

Auto-update tidak berarti update diam-diam tanpa instalasi. Native code, Gradle, dan asset APK memerlukan paket APK baru. Yang otomatis adalah deteksi dan pengunduhan; konfirmasi installer Android tetap diperlukan. Perubahan UI berbasis asset juga ikut diperbarui melalui APK release.
