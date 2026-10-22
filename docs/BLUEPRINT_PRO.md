# Xena-AI Blueprint Pro v2.0 — Roadmap

> **Penting:** dokumen ini adalah blueprint dan roadmap, bukan daftar fitur yang seluruhnya sudah aktif.

## Status implementasi saat ini — v1.5.6

Fitur berikut sudah aktif di branch `main` dan APK release terbaru:

- UI WebView full-screen empat tab: Chat, Model, Konektor, dan Atur.
- Tema dark, light, hacker, dan cyberpunk dengan splash, status jaringan, kartu, progress, dan animasi thinking.
- Model manager dengan download resumable, pause/resume/cancel, validasi GGUF, impor SAF, pencarian file model, benchmark, dan peringatan RAM.
- Pembersih RAM native: unload llama.cpp, native trim, clear cache WebView, dan mode ringan melalui `onTrimMemory`.
- Groq Server Mode dengan API key yang disimpan melalui Android Keystore.
- GitHub connector dan push kode dari code block menggunakan token lokal pengguna.
- Auto-update APK dari GitHub Releases dengan verifikasi signing certificate dan installer Android resmi.
- Workflow release berbasis versi yang membangun `app-release.apk` dengan stable signing key.

Lihat [`SISTEM_TERKINI.md`](SISTEM_TERKINI.md) dan [`CATATAN_UI_V1.2_RELEASE.md`](CATATAN_UI_V1.2_RELEASE.md) untuk dokumentasi fitur yang benar-benar aktif.

Bagian setelah status ini tetap dipertahankan sebagai ide desain dan roadmap V2.0. Item seperti Room, Hilt, WorkManager penuh, OAuth GitHub, Material You native, dan silent update bukan bagian dari APK v1.5.6.

================================================================================
XENA-AI SATU FILE FULL PRO - SEMUA JADI SATU (V2.0)
================================================================================


================================================================================
XENA-AI FULL PRO BLUEPRINT V2.0 - CATATAN LENGKAP BIAR KALAHIN POCKETPAL AI
================================================================================
Repo: github.com/v1-byte/Xena-Ai
Target: Dari App Dev (v1.5.2) -> App Pro (v2.0)
Dibuat: 22 Okt 2026 untuk device RAM 6+6GB

================================================================================
PART 1: AUDIT JUJUR KENAPA SEKARANG BELUM PRO
================================================================================
A. BRANDING & PERSEPSI:
- Nama inkonsisten: Xena-Ai / Xena-AI / Xena-Ai (di GitHub beda-beda)
- Icon belum ada identitas (masih default/debug)
- 22 Releases dalam 3 hari = keliatan panic push, bukan stable
- 0 Star, 0 Fork = README belum jualan, belum ada screenshot, benchmark, demo video

B. UI SEKARANG:
- FAB isinya "Bersihkan RAM, Hapus Sisa Download, Model AI" -> Ini menu tukang service HP, bukan AI Pro. User takut.
- Chat kosong = layar putih. Gak ada onboarding, gak ada contoh prompt WhiteRabbitNeo.
- Model download cuma pakai .part manual, kalo koneksi putus file corrupt, user harus hapus manual.
- Context 1024 itu kekecilan buat WhiteRabbitNeo 7B, jawaban kepotong.

C. SYSTEM SEKARANG:
- Groq Key gsk_ disimpan di SharedPreferences biasa (rawan di-root, bisa dicuri app lain)
- Chat history gak persisten (hilang pas app mati)
- System.gc() itu placebo, gak bersihin RAM beneran di Android 13+
- Build butuh JDK 17 + NDK 26 + CMake 3.22 = kontributor baru langsung kabur
- Groq + Lokal masih tabrakan, belum ada auto fallback.

================================================================================
PART 2: TAMPILAN - FULL REDESIGN BIAR KELIATAN MAHAL
================================================================================
GOAL: User buka 3 detik langsung mikir "Ini app pro, bukan app abal-abal".

2.1 NAVIGASI BARU (WAJIB):
- HAPUS FAB RAM DARI HOME. Pindah ke Settings > Advanced > Diagnostics.
- BOTTOM NAV 3 SAJA:
  [CHAT] - [MODEL] - [SETTINGS]
- Top App Bar di Chat: Kiri = Nama Model Aktif + Status Dot Hijau, Kanan = 2 icon (History + New Chat). Contoh: ● WhiteRabbitNeo 7B Q2_K • 2.1 tok/s
- PocketPal cuma punya Chat & Models. Kita tambah Settings yang proper.

2.2 CHAT SCREEN - INI YANG BIKIN USER BETAH:
- Bubble Chat: User kanan warna primary, AI kiri warna surfaceVariant, radius 18dp. Jangan kotak.
- Markdown Pro: Support **bold, list, table, quote**. WAJIB pakai library Markwon atau Compose Markdown.
- Code Block Super Pro (KUNCI BIAR KALAHIN POCKETPAL):
  - Header: python • COPY • SHARE • PUSH TO GITHUB
  - Syntax Highlight: Dark theme kayak VS Code
  - Line number (optional)
  - Kalo WhiteRabbitNeo ngasih exploit code, user bisa langsung push.
- Thinking Animation: Pakai orb + spark yang sudah ada! Pas AI mikir, tampilkan "Xena is thinking..." + orb muter. Jangan dipakai buat bersihin RAM.
- Empty State Pro:
  Judul: "Mau ngoprek apa hari ini?"
  4 Card Prompt (bisa di-klik):
  1. 🔴 Red Team Mode: "Buatkan Python scanner untuk port 80,443"
  2. 🔵 Blue Team Mode: "Analisis log Apache ini, cari anomali"
  3. 🟢 Code Audit: "Review code login PHP ini dari celah SQLi"
  4. ⚪ Groq Fast Mode: "Jelaskan CVE-2024-XXXX dengan bahasa simpel"
- Input Bar: Text field + tombol mic + tombol send. Ada chip "Attach File" buat upload log.

2.3 MODEL MANAGER - BIKIN LEBIH CANGGIH DARI POCKETPAL:
PocketPal cuma: List -> Download -> Load. Kita bikin:
- Card Design: Tiap model ada Card dengan:
  - Icon model (misal 🐰 untuk WhiteRabbitNeo)
  - Nama: WhiteRabbitNeo V3 7B Q2_K
  - Meta: 3.02 GB • Q2_K • Context 2048
  - Badge: RECOMMENDED FOR 8GB+ / LIGHT FOR 6GB / GROQ ONLY
  - Tombol: DOWNLOAD / LOAD / BENCHMARK / DELETE
- Smart Device Check (FITUR PEMBEDA):
  Kode: if (totalRAM < 7.5GB) -> Model 7B auto disable + tooltip "Perangkat kamu 6GB, model ini butuh 8GB. Pakai Groq Mode dengan persona yang sama?"
  Ini mencegah user download 3GB terus force close dan kasih rating bintang 1.
- Download Manager Pro:
  - Pakai WorkManager + Foreground Service + Notification progress kayak Play Store
  - UI: Progress bar + 45% + 12.5 MB/s + ETA 3m 12s + PAUSE / RESUME / CANCEL
  - Auto resume kalo internet putus, validasi checksum GGUF
  - Folder: /Download/Xena-AI/Models/ jangan di cache
- Benchmark: Tombol kecil "Test Speed". Jalanin 100 token, hasil: "2.8 tok/s • 1.2s first token • Aman untuk chat". Simpen hasilnya.

2.4 DESIGN SYSTEM PRO:
- Icon: Bikin baru. Jangan pakai robot. Ide: Huruf X yang tajam + mata. Warna ungu gelap + cyan.
- Material You 3: Wajib dynamic color. Light mode putih bersih, Dark mode #121212 AMOLED.
- Typography: Headline -> Space Grotesk Bold, Body -> Inter / Manrope. Jangan Roboto semua.
- Corner: Semua Card 24dp radius, bukan 4dp kotak jadul.
- Haptics: Getar halus pas send chat, pas download selesai.

================================================================================
PART 3: SYSTEM - BIAR GAK LEMOT & GAK FORCE CLOSE DI RAM 6+6
================================================================================
GOAL: App stabil di RAM 6GB, gak panas, gak makan baterai.

3.1 ARSITEKTUR PRO:
- MVVM + Repository + Clean Architecture
- Database: Room untuk Chat History. Entity: ChatSession, Message, ModelUsed. Jadi user bisa search history "cari chat tentang SQLi kemarin"
- Settings: DataStore (Preferences) + Jetpack Security (EncryptedSharedPreferences) KHUSUS buat Groq API Key. Jangan SharedPreferences biasa.
- Dependency Injection: Hilt / Koin biar gak pusing.

3.2 LLAMA.CPP & MEMORY MANAGEMENT (PENTING BUAT 6+6):
- Sekarang: context 1024, batch 32, threads 2 -> Kekecilan, lambat.
- Pro:
  - Auto Config: Buat fungsi getBestConfig():
    if RAM >= 8GB: context=4096, threads=6, batch=512
    if RAM == 6GB: context=2048, threads=4, batch=256, use_mmap=true, use_mlock=false
    if RAM < 6GB: paksa Groq mode, jangan kasih load lokal 7B
  - Unload Proper: Jangan System.gc(). Pakai llama.cpp close model + free context + onTrimMemory() + clear WebView cache.
  - Load di Background: Semua load model di Dispatchers.IO, UI kasih loading dialog "Loading WhiteRabbitNeo 7B... 45%". Jangan di Main Thread, nanti ANR.
  - Low Memory Killer: Daftarin ComponentCallbacks2, kalo OS bilang TRIM_MEMORY_RUNNING_CRITICAL, auto unload model dan simpan chat.

3.3 GROQ + LOKAL HYBRID ENGINE (FITUR YANG POCKETPAL GAK PUNYA):
- Buat class HybridEngine:
  - Coba load lokal dulu. Kalo OOM / gagal, otomatis fallback: show dialog "Model 7B butuh RAM lebih. Mau lanjut dengan Groq Cloud tapi tetap pakai otak WhiteRabbitNeo? [Ya, Pakai Groq] [Tidak]"
  - Kalo Ya, inject System Prompt WhiteRabbitNeo ke Groq model openai/gpt-oss-20b. Jadi user tetap dapat persona yang sama, tapi speed Groq (super cepat).
  - Simpan preferensi: User 6GB default ke Groq, tapi tetap bisa coba lokal Q2_K.

3.4 DOWNLOAD & FILE SYSTEM:
- WorkManager: Buat Worker DownloadModelWorker. Support pause/resume via Range header.
- Validasi: Setelah download cek file size, cek magic number GGUF "GGUF", bukan cuma cek .part hilang.
- Storage: Minta permission MANAGE_EXTERNAL_STORAGE? Jangan. Pakai SAF / MediaStore Download folder.

3.5 KEAMANAN & PRIVASI PRO:
- Groq Key di-encrypt + ada tombol "Hide Key" + validasi format gsk_
- Chat lokal: Ada toggle "Incognito Mode" -> chat gak disimpan ke DB
- Permission: Jangan minta READ_SMS dll. Cuma INTERNET & POST_NOTIFICATIONS.

================================================================================
PART 4: FITUR PRO BIAR LEBIH BAIK DARI POCKETPAL
================================================================================
PocketPal Fitur: Load model, chat, system prompt.
Xena Harus Punya Lebih:

1. WhiteRabbitNeo Preset Library (EXCLUSIVE):
   - Red Team, Blue Team, Malware Analyst, OSINT, Bug Bounty. 1 klik ganti system prompt. User gak perlu hafal prompt panjang.

2. GitHub Integration (EXCLUSIVE):
   - Di code block ada "Push to GitHub". User login OAuth, pilih repo, file langsung ke-commit. Ini yang diminta user dari tadi "biar bisa conec ke github gimana"

3. Groq Marketplace:
   - Selain openai/gpt-oss-20b, kasih pilihan: llama-3.3-70b, mixtral, gemma. User bisa ganti model Groq.

4. Prompt & Session Management:
   - Save chat as Template, Export chat ke Markdown/PDF, Share chat link.

5. Developer Tools (Karena WhiteRabbitNeo buat security):
   - Built-in terminal mini buat curl / ping (read only)
   - CVE Search: Ketik "CVE-2024-1234" langsung fetch dari NVD.

================================================================================
PART 5: ROADMAP EKSEKUSI
================================================================================
V1.6 STABIL (3 Hari):
- [ ] Ganti Bottom Nav, hapus FAB RAM dari home
- [ ] Room DB buat chat history
- [ ] Encrypted Groq Key
- [ ] Chat UI markdown + code copy

V1.7 BEAT POCKETPAL (1 Minggu):
- [ ] Model Manager baru (pause/resume, benchmark, RAM warning)
- [ ] Auto fallback Groq kalo 7B gagal load
- [ ] Empty State + Preset WhiteRabbitNeo

V2.0 PRO LAUNCH (2 Minggu):
- [ ] Icon baru + Material You 3 + Splash Screen Pro
- [ ] GitHub Integration
- [ ] Export Chat + Prompt Library
- [ ] Play Store listing: Screenshot pro, video demo, deskripsi "The only WhiteRabbitNeo mobile with Groq + Local Hybrid"

V2.1 MONETIZE (Optional):
- Pro Version: $2.99 unlock unlimited Groq switch + custom theme + no ads. Jangan bikin paywall buat model lokal.

================================================================================
PART 6: CHECKLIST SEBELUM RILIS PRO KE PLAY STORE
================================================================================
- [ ] Test di HP 6GB RAM beneran (bukan emulator), load Q2_K 7B, chat 50 pesan, cek panas & baterai
- [ ] Test download 3GB pakai data seluler putus-putus
- [ ] Test Groq key salah, key habis quota
- [ ] Screenshot 6 buah: Chat, Model Manager, Preset, Code Block, Dark Mode, Benchmark
- [ ] Video 30 detik: Buka app -> Pilih WhiteRabbitNeo -> Chat "buatkan scanner"
- [ ] README baru: Logo, Badge, Screenshot, Benchmark, Cara Build, License MIT

Jika semua checklist ini selesai, Xena-AI V2.0 sudah layak disebut PRO dan layak bersaing bahkan lebih baik dari PocketPal AI karena punya 2 hal yang PocketPal tidak punya: Groq Hybrid + WhiteRabbitNeo Security Preset + GitHub.

Selesai. Simpan file ini sebagai BLUEPRINT_PRO.md di repo.


================================================================================


# UI/UX SPEC - XENA PRO V2.0
## BOTTOM NAV
- Chat (icon: chat_bubble), Model (icon: memory), Settings (icon: settings)
- Badge: Show active model tok/s

## CHAT SCREEN
- TopBar: Left: Model Name + green dot, Right: History + New Chat
- Bubble: User #6750A4, AI #E8DEF8 (Material 3)
- Markdown: Markwon library
- CodeBlock: Header with language, COPY, SHARE, PUSH TO GITHUB button. Theme: VS Code Dark
- EmptyState: Title + 4 Preset Cards (Red, Blue, Code Audit, Groq Fast)
- Input: TextField + Attachment + Mic + Send

## MODEL SCREEN
- Two Tabs: LOCAL (Private) | GROQ (Cloud)
- Card: Icon, Name, Size, RAM Need, Badge, Buttons: Download/Load/Benchmark/Delete
- Download: Progress + MB/s + ETA + Pause/Resume/Cancel via WorkManager
- Smart Warning: If RAM 6GB + model 7B -> Blur + "Need 8GB+"

## SETTINGS SCREEN
- Groq: API Key (encrypted), Server Mode ON/OFF, Test Connection
- Appearance: Theme (Light/Dark/System), Dynamic Color ON/OFF
- Advanced: Memory Diagnostics (move RAM cleaner here), Clear Cache, Export DB
- About: Version, GitHub, License

## DESIGN TOKENS
- Colors: Primary #6750A4, Secondary #958DA5, Surface #FFFBFE
- Radius: Card 24dp, Button 20dp, Bubble 18dp
- Fonts: Headline SpaceGrotesk-Bold, Body Inter-Regular


================================================================================


# SYSTEM ARCHITECTURE - XENA PRO V2.0

## STACK
- Kotlin, MVVM, Repository
- Room DB: ChatSession(id, title, createdAt), Message(id, sessionId, role, content, model, timestamp)
- DataStore + EncryptedSharedPreferences for Groq Key
- Hilt DI
- WorkManager for downloads
- llama.cpp via JNI (NDK 26, CMake 3.22)

## MEMORY LOGIC
fun getBestConfig(totalRamGB: Float): LlamaConfig {
  return when {
    totalRamGB >= 8 -> LlamaConfig(context=4096, threads=6, batch=512, mmap=true)
    totalRamGB >= 6 -> LlamaConfig(context=2048, threads=4, batch=256, mmap=true, mlock=false)
    else -> LlamaConfig(groqOnly=true)
  }
}

## HYBRID ENGINE
class HybridEngine {
  suspend fun load(model: Model): Result {
    val local = tryLoadLocal(model)
    if (local.isFailure && isOOM) {
      return fallbackToGroq(model.persona)
    }
    return local
  }
}

## SECURITY
- Groq Key: EncryptedSharedPreferences, hide/show toggle
- No sensitive permission
- Incognito mode: Don't save to Room


================================================================================


# CHECKLIST RILIS PRO V2.0

V1.6 (3 Hari):
- [ ] Bottom Nav 3
- [ ] Hapus FAB RAM dari home
- [ ] Room DB chat history
- [ ] Encrypted Groq Key
- [ ] Chat UI markdown + code copy

V1.7 (1 Minggu):
- [ ] Model Manager baru (pause/resume, benchmark, RAM warning)
- [ ] Auto fallback Groq
- [ ] Empty State + Preset
- [ ] Download Manager WorkManager

V2.0 PRO (2 Minggu):
- [ ] Icon baru + Material You 3
- [ ] GitHub Integration (push code)
- [ ] Export Chat md/pdf
- [ ] Prompt Library
- [ ] Play Store: 6 Screenshot + 30s Video + Description

TESTING:
- [ ] HP 6GB real device load 7B Q2_K
- [ ] Download 3GB putus-putus
- [ ] Groq key salah / quota habis
- [ ] 50 pesan chat panas check

README:
- [ ] Logo, Badge, Screenshot, Benchmark tok/s, License MIT, Cara Build
- [ ] Hapus 22 release tag, jadi v1.5.2 stable


================================================================================


# POCKETPAL AI vs XENA-AI - BAGAIMANA XENA BISA MENANG

PocketPal Kelebihan:
- Stabil, 1jt+ download
- UI clean, model manager bagus
- Support banyak model GGUF

PocketPal Kekurangan:
- Cuma lokal, gak ada cloud fallback
- Gak ada preset security (WhiteRabbitNeo)
- Gak ada GitHub integration
- Gak ada benchmark speed

Xena Kelebihan yang harus ditekankan:
- Hybrid Groq + Lokal (PocketPal gak punya)
- WhiteRabbitNeo Preset Red/Blue Team (Exclusive)
- GitHub Push dari code block (Exclusive)
- Smart RAM Warning (PocketPal biarin user download gagal)
- Benchmark tok/s

Cara Menang: Jangan lawan PocketPal di stabilitas lokal saja, tapi tawarkan Hybrid + Security.


================================================================================
