# Xena-AI v1.5.0 — Migrasi Server Mode ke Groq

## Perubahan

Server Mode kini mengirim permintaan ke endpoint Chat Completions Groq:

`https://api.groq.com/openai/v1/chat/completions`

Model default adalah `openai/gpt-oss-20b`, yang saat penulisan tercantum di daftar production Groq. Model `qwen-2.5-coder-32b` dari lampiran sudah tidak tersedia: Groq mencatat model itu dihentikan pada 14 April 2025. `llama-3.3-70b-versatile` saat ini tercantum dengan label Enterprise, bukan model default yang tercantum untuk Free plan.

## Menyiapkan koneksi

1. Buat API key di [Groq Console](https://console.groq.com/keys). Jangan kirim atau commit key ke chat/repository.
2. Pasang Xena-AI v1.5.0.
3. Buka **Atur → Koneksi Groq**, tempel key berawalan `gsk_`, lalu pilih **Simpan API key**.
4. Aktifkan **Server Mode: ON**.
5. Tekan **Tes koneksi**, lalu coba kirim pesan di tab Chat.

Key disimpan terenkripsi dengan AES-GCM dan kunci Android Keystore pada perangkat. Aplikasi mengosongkan input setelah disimpan. Pemasangan baru tidak menerima API key default.

## Batas dan biaya

Groq mencantumkan untuk `openai/gpt-oss-20b` batas Free sebesar 30 request/menit, 1.000 request/hari, 8.000 token/menit, dan 200.000 token/hari. Batas sebenarnya dapat berbeda menurut organisasi dan berubah dari waktu ke waktu; periksa halaman **Limits** di akun.

Developer tier adalah pay-as-you-go, memerlukan metode pembayaran, dan mengenakan biaya per token. Tes koneksi melakukan satu chat completion kecil sehingga menggunakan request/kuota API. Periksa plan, usage, spend limits, dan billing Groq sebelum menggunakan akun berbayar. Aplikasi tidak menetapkan atau mengubah billing akun.

## Troubleshooting

- **401/403**: key tidak valid atau akses akun tidak tersedia; buat/cek key di Groq Console.
- **402**: akun/kuota tidak mengizinkan request; periksa plan dan billing.
- **404**: model ID tidak tersedia untuk akun saat ini; cek daftar model aktif Groq.
- **429**: rate limit tercapai; tunggu reset yang ditunjukkan Groq.
- **5xx/koneksi**: coba lagi setelah layanan pulih dan periksa jaringan.

## Hugging Face

Konektor chat HF, token HF tersimpan, URL Space, dan template Space Docker dihapus. Saat mulai aplikasi, konfigurasi token/URL HF dari versi lama juga dihapus. Katalog unduhan GGUF lokal masih memakai URL file Hugging Face sebagai sumber model; ini tidak memakai token HF Inference dan tidak terkait jalur chat Groq.

## Rujukan resmi

- [Groq OpenAI compatibility](https://console.groq.com/docs/openai)
- [Groq supported models and pricing](https://console.groq.com/docs/models)
- [Groq rate limits](https://console.groq.com/docs/rate-limits)
- [Groq model deprecations](https://console.groq.com/docs/deprecations)
- [Groq billing FAQ](https://console.groq.com/docs/billing-faqs)
