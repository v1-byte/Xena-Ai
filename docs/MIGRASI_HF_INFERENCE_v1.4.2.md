# Xena-AI v1.4.2 — Hugging Face Inference Providers

## Ringkasan

Server Mode memakai endpoint Chat Completions resmi Hugging Face Inference Providers:

`https://router.huggingface.co/v1/chat/completions`

Tidak diperlukan Hugging Face Space Docker untuk jalur ini. Permintaan menggunakan model `Qwen/Qwen2.5-Coder-7B-Instruct`. Ketersediaan model, provider, kuota, dan biaya bergantung pada akun serta dukungan provider yang berlaku.

## Pengaturan di Android

1. Buka tab **Atur** → **Koneksi Hugging Face Inference**.
2. Masukkan token HF yang memiliki izin Inference, lalu tekan **Simpan token**.
3. Aktifkan **Server Mode: ON**.
4. Tekan **Tes koneksi**. Jika gagal, periksa token, izin token, dukungan provider model, kuota/penagihan, dan internet.
5. Kembali ke Chat dan kirim pesan uji.

Token dimasukkan langsung pada perangkat. Aplikasi menyimpannya dalam bentuk terenkripsi menggunakan Android Keystore; source code dan APK tidak mengandung token.

## Keamanan

Token yang pernah ditempel di chat atau dimasukkan ke kode harus dianggap terekspos dan dicabut di pengaturan Hugging Face. Buat token pengganti dengan izin yang dibutuhkan, lalu masukkan di aplikasi. Jangan commit token ke repository atau membagikannya.
