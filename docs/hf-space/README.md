---
title: Xena Qwen Server
emoji: ⚡
colorFrom: indigo
colorTo: purple
sdk: docker
app_port: 7860
pinned: false
---

# Xena Qwen Server (llama.cpp)

OpenAI-compatible endpoint for **Xena-AI** Android client (server mode).

- Chat: `POST /v1/chat/completions`
- Model weights: Qwen2.5-Coder-7B-Instruct Q3_K_M (loaded on the Space, not on the phone)

## Client setup

1. Wait until Space status is **Running**.
2. Copy base URL: `https://USERNAME-xena-qwen-server.hf.space`
3. In Xena-AI → **Atur** → set HF Server URL → Server Mode ON.
4. Optional keep-awake: cron-job.org every 12h → `GET` the Space root URL.

## Privacy

Space should stay **Private**. Chat content goes to this Space only (not to Groq).
