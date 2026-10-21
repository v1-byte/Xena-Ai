# Xena-AI HuggingFace Space — llama.cpp OpenAI-compatible server
# Hardware: CPU Basic free (≈2 vCPU / 16 GB RAM)
FROM ghcr.io/ggerganov/llama.cpp:server

USER root
RUN apt-get update && apt-get install -y --no-install-recommends wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /models \
    && wget -q -O /models/model.gguf \
    "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q3_k_m.gguf"

EXPOSE 7860
# mlock off; modest ctx for free CPU
CMD ["--model", "/models/model.gguf", "--host", "0.0.0.0", "--port", "7860", "--ctx-size", "2048", "--threads", "2"]
