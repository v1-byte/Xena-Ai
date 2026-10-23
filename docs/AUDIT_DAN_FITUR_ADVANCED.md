# Audit Total dan Fitur Advanced Xena-AI

## Audit baseline v1.5.8

Audit statis mencakup UI WebView, bridge Kotlin, JNI llama.cpp, model loader/downloader, RAM guard, konektor, updater, manifest, dan CI. Test UI/model/release baseline: **11/11 lulus**.

Temuan terbesar:

- chat memakai flag stop global sehingga jawaban lama dapat masuk ke chat baru;
- banyak thread mentah menahan Activity/WebView setelah lifecycle berakhir;
- callback error dikirim sebagai teks jawaban dan tidak memiliki kategori retry;
- context percakapan tidak dikirim ke engine, walaupun UI menampilkan riwayat;
- top-p, repeat penalty, dan max token dari UI sebagian diabaikan;
- model terakhir di preference dapat dianggap aktif sebelum native load berhasil;
- `System.loadLibrary` hanya menangkap `Exception`, bukan `LinkageError`;
- metrik RAM dapat menghitung cache dua kali;
- token konektor lama tersimpan plaintext di SharedPreferences dan token UI tersimpan di localStorage;
- WebView bridge belum memiliki policy navigation yang ketat;
- updater/model supply chain masih memerlukan signed manifest dan digest untuk hardening lanjutan;
- workflow signing masih perlu dipisah dari job build umum dan action perlu dipin immutable SHA.

## Yang diterapkan pada gelombang ini

### 1. Chat engine single-flight dan request ownership

Setiap chat mendapat `requestId` monotonik. Hanya request aktif yang boleh mengirim token, selesai, atau error ke WebView. Tombol Stop membatalkan request lama dan menyelesaikan promise UI tanpa membiarkan callback stale masuk ke chat berikutnya. Executor chat dibatasi satu worker dan dihentikan saat Activity dihancurkan.

Kontrak callback baru kompatibel ke belakang:

```javascript
window.xenaToken(requestId, text)
window.xenaDone(requestId)
window.xenaError(requestId, code, userMessage, retryable)
```

Error tidak lagi disimpan sebagai jawaban AI mentah. Pesan yang ditampilkan sudah disanitasi untuk auth, rate limit, timeout, OOM, dan error engine.

### 2. Context percakapan nyata

UI mengirim beberapa pasangan user/assistant terakhir sebagai konteks terbatas sebelum prompt aktif. Ini membuat model lokal dan Groq memahami percakapan tanpa mengirim seluruh history. Batas saat ini adalah sembilan pesan sebelumnya dan tetap mengikuti pilihan pengguna pada mode cloud.

### 3. Parameter inference end-to-end

Top-p dan max token diteruskan ke Groq. Repeat penalty diteruskan ke JNI dan dipasang pada sampler llama.cpp. Temperature tetap divalidasi di native/provider. Context, batch, dan thread tetap merupakan parameter load-time sehingga perubahan besar dapat memerlukan reload model.

### 4. RAM dan model state lebih akurat

- `LlamaBridge` menyimpan `loadedModelPath` hanya setelah native load berhasil.
- daftar model tidak lagi menandai model B aktif hanya karena preference berubah saat engine masih memuat model A;
- `LinkageError` native ditangani sebagai error terkontrol;
- JNI memvalidasi path/prompt dan sampler allocation;
- laporan `freedBytes` tidak lagi menambahkan metrik cache yang sudah tercermin di PSS/available memory.

### 5. Lifecycle dan WebView hardening

- executor chat dihentikan pada `onDestroy`;
- JavaScript interfaces dilepas saat Activity dihancurkan;
- callback `evaluateJavascript` memeriksa lifecycle dan menangkap error di dalam runnable;
- navigasi WebView tidak boleh meninggalkan asset lokal Xena-AI.

### 6. Credential storage konektor

Token GitHub, Cloudflare, dan Meta yang disimpan native sekarang memakai AES-GCM dengan Android Keystore dan connector ID di-allowlist. Token plaintext lama dimigrasikan sekali lalu dihapus. Tahap lanjutan yang masih direkomendasikan adalah native connector broker agar token tidak pernah berada di JS/localStorage sama sekali.

## Diterapkan pada hardening v1.6.2

- Native connector broker untuk uji GitHub, Cloudflare, dan Meta; token tidak dikembalikan lewat JavaScript bridge.
- Token konektor pada UI tidak lagi disimpan di localStorage; hanya sesi memori, sedangkan penyimpanan native memakai Android Keystore.
- Groq Server Mode memakai SSE streaming native dan callback token request-scoped.
- Updater memvalidasi HTTPS host allowlist, batas ukuran APK, SHA-256 asset GitHub bila tersedia, package name APK, dan signing certificate.
- Model activation memiliki hook SHA-256 katalog; entry katalog yang memiliki digest akan diverifikasi sebelum GGUF aktif.
- Android backup dimatikan untuk mencegah secret/WebView state keluar lewat backup.
- Password signing tidak lagi dimasukkan ke `GITHUB_ENV`; hanya tersedia pada langkah Gradle signing.

## Tahap lanjutan yang direkomendasikan

1. Native connector broker: UI hanya menerima hasil API, bukan token.
2. Signed model catalog + SHA-256 per GGUF.
3. Signed updater manifest/TUF, host/redirect allowlist, package/versionCode check.
4. Streaming SSE Groq dan callback token native llama.cpp agar streaming benar-benar token-level.
5. WorkManager/app-scoped downloader agar download tidak hidup di Activity.
6. Protected release job, immutable action SHA, SBOM, CodeQL, secret scan, dan artifact attestation.
7. Instrumentation test untuk rotation, process death, OOM, updater, bridge origin, dan credential migration.


## Startup v1.6.3

Splash dipersingkat menjadi maksimal 4,8 detik, memiliki fallback untuk JavaScript error/unhandled rejection, dan tidak lagi mengunci layar apabila init UI gagal. Auto-load model dipindahkan setelah splash selesai agar pembukaan aplikasi tidak tersendat oleh native model loading.


Progress angka splash sekarang berhenti deterministik ketika error/error promise terjadi; splash langsung menampilkan status selesai dan ditutup.


## Performa v1.6.3

Efek blur dan backdrop-filter berat pada splash dikurangi, ring/animasi dibuat GPU-friendly, dan perangkat dengan CPU <=4 core atau RAM <=4 GB otomatis memakai mode animasi ringan serta mematikan efek visual mahal.


## Animasi v1.6.4

Animasi pembuka dikembalikan dalam bentuk ringan: ring tetap bergerak, background tetap terlihat dengan opacity rendah, dan error resource nonfatal tidak lagi langsung menutup splash. Blur berat tetap tidak digunakan.


## UI utama v1.6.5

Tampilan lengkap dari lampiran pengguna dijadikan asset utama. Struktur Chat, Model, Konektor, Atur, katalog model, download manager, pencarian model, dan console dipertahankan. Startup fail-safe, animasi ringan, request ID native, konteks chat, dan sesi token native tetap aktif.


## Splash dan Download Manager v1.6.6

Splash kini memakai animasi CSS indeterminate, watchdog timeout, dan tidak lagi bergantung pada voice synthesis saat startup. Download Manager diperbarui menjadi panel profesional dengan status READY/DOWNLOADING/PAUSED/COMPLETE, metrik progres/kecepatan/status, ETA, kontrol Jeda/Lanjut, Verifikasi, Batal, serta preset ukuran unduhan agar progres tidak NaN.
