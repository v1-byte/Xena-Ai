# Xena-AI v1.4.0 — Migrasi Server Mode (HuggingFace)

Tanggal: 21 Okt 2026  
Target: HP 6GB (RMX3760) — offline 7B GGUF sering OOM / download gagal.

## Ringkas

| Mode | Default | Beban HP |
|------|---------|----------|
| **Server Mode** (HF Space) | **ON** | Hanya HTTPS JSON |
| Local GGUF | Opsional | Butuh RAM + penyimpanan besar |

Offline **tidak dihapus total** (masih bisa untuk model kecil 1.5B/3B). Default untuk 6GB = server.

## Kode yang ditambah

- `KonektorHuggingFace.kt` — OkHttp → `/v1/chat/completions`
- `AppPrefs` — `server_mode` (default true), `hf_server_url`
- Bridge JS: `setServerMode`, `setHfServerUrl`, `getHfServerUrl`, `pingHfServer`, `isServerMode`
- Chat path memakai HF jika server mode ON

## Buat Space (sekali)

1. https://huggingface.co/join  
2. New Space → name `xena-qwen-server` → SDK **Docker** → CPU basic → **Private**  
3. Upload isi folder `docs/hf-space/` (`Dockerfile` + `README.md`)  
4. Tunggu **Running** (download model di build bisa 5–15 menit)  
5. URL: `https://USERNAME-xena-qwen-server.hf.space`

## Anti-sleep 48 jam

- https://cron-job.org → setiap 12 jam → GET URL Space

## Di HP

1. Install APK **v1.4.0**  
2. Atur → tempel URL Space → Server Mode ON  
3. Chat tanpa unduh 3.55 GB

## Privasi & kecepatan

- Chat lewat server HF (Private Space).  
- CPU free ≈ 5–10 tok/s, bukan secepat GPU cloud.  
- Untuk offline 100%: matikan Server Mode + unduh model 1.5B/3B.
