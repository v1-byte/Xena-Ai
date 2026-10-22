package com.xena.ai

import org.json.JSONArray
import org.json.JSONObject

/** Model GGUF yang diizinkan untuk diunduh oleh aplikasi. */
object ModelCatalog {
    data class Model(
        val id: String,
        val title: String,
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val quantization: String,
        val recommendation: String,
        val ramNote: String
    )

    // Byte sizes were verified from Hugging Face metadata on 2026-10-22.
    // UI labels are approximate; the transfer displays the server-reported size.
    private val models = listOf(
        Model(
            id = "qwen15-q4",
            title = "Qwen2.5-Coder 1.5B",
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_768L,
            quantization = "Q4_K_M",
            recommendation = "Paling ringan · rekomendasi awal untuk RAM 6 GB",
            ramNote = "Paling hemat RAM; kecepatan aktual bergantung perangkat"
        ),
        Model(
            id = "qwen3b-q3",
            title = "Qwen2.5-Coder 3B",
            fileName = "qwen2.5-coder-3b-instruct-q3_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q3_k_m.gguf",
            sizeBytes = 1_724_178_880L,
            quantization = "Q3_K_M",
            recommendation = "Seimbang · ukuran dan kemampuan menengah",
            ramNote = "RAM 6 GB: mulai dari context 1024"
        ),
        Model(
            id = "qwen7b-q2",
            title = "Qwen2.5-Coder 7B",
            fileName = "qwen2.5-coder-7b-instruct-q2_k.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q2_k.gguf",
            sizeBytes = 3_015_940_032L,
            quantization = "Q2_K",
            recommendation = "7B paling kecil · lebih lambat dan kualitas kuantisasi lebih rendah",
            ramNote = "Berat untuk RAM 6 GB; tutup aplikasi lain"
        ),
        Model(
            id = "whiterabbitneo-v3-7b-q2",
            title = "WhiteRabbitNeo V3 7B",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q2_K.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q2_K.gguf",
            sizeBytes = 3_015_941_312L,
            quantization = "Q2_K",
            recommendation = "WhiteRabbitNeo lokal · unduhan sekitar 3,02 GB",
            ramNote = "Model 7B cukup berat; perlu ruang penyimpanan dan RAM bebas yang memadai"
        ),
        Model(
            id = "qwen7b-q3",
            title = "Qwen2.5-Coder 7B",
            fileName = "qwen2.5-coder-7b-instruct-q3_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q3_k_m.gguf",
            sizeBytes = 3_808_391_104L,
            quantization = "Q3_K_M",
            recommendation = "Model 7B yang sekarang · opsi berat",
            ramNote = "Bisa gagal dimuat pada RAM 6 GB meski context sudah diturunkan"
        ),
        Model(
            id = "qwen7b-q4",
            title = "Qwen2.5-Coder 7B",
            fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            sizeBytes = 4_683_073_536L,
            quantization = "Q4_K_M",
            recommendation = "Lebih besar · dipertahankan sebagai pilihan lanjutan",
            ramNote = "Tidak direkomendasikan untuk RAM 6 GB"
        )
    )

    fun all(): List<Model> = models

    fun get(id: String): Model? = models.firstOrNull { it.id == id }

    fun byFileName(name: String): Model? = models.firstOrNull { it.fileName.equals(name, ignoreCase = true) }

    fun toJson(): String {
        val array = JSONArray()
        models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("title", model.title)
                    .put("fileName", model.fileName)
                    .put("sizeBytes", model.sizeBytes)
                    .put("quantization", model.quantization)
                    .put("recommendation", model.recommendation)
                    .put("ramNote", model.ramNote)
            )
        }
        return array.toString()
    }
}
