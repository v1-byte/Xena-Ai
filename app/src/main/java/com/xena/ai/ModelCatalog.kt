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
        val ramNote: String,
        val sha256: String? = null
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
            ramNote = "Paling hemat RAM; kecepatan aktual bergantung perangkat",
            sha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046"
        ),
        Model(
            id = "qwen3b-q3",
            title = "Qwen2.5-Coder 3B",
            fileName = "qwen2.5-coder-3b-instruct-q3_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q3_k_m.gguf",
            sizeBytes = 1_724_178_880L,
            quantization = "Q3_K_M",
            recommendation = "Seimbang · ukuran dan kemampuan menengah",
            ramNote = "RAM 6 GB: mulai dari context 1024",
            sha256 = "fc3937db7dda9d9ef68ce1f63b5a84ac850ec3c07578461d645e5a88509348e3"
        ),
        // BugHunter 3B — bug bounty / security research; ~1.9 GB file, cocok RAM 2 GB+
        Model(
            id = "qwen3b-bughunter",
            title = "Qwen2.5 3B BugHunter",
            fileName = "Qwen2.5-VL-3B-uncensored-bughunter-v9.Q4_K_M.gguf",
            url = "https://huggingface.co/mmp2055/Qwen2.5-VL-3B-uncensored-bughunter-v9/resolve/main/Qwen2.5-VL-3B-uncensored-bughunter-v9.Q4_K_M.gguf",
            sizeBytes = 1_929_902_432L,
            quantization = "Q4_K_M",
            recommendation = "★ Ethical hacking / bug bounty ringan · loss rendah (Q4) · RAM 2 GB+",
            ramNote = "Aktif ~2,5 GB RAM; context 1024. Text-only. Cocok server/web testing di HP.",
            sha256 = "46ef6ae943c964742516e23ae9cd062f5128d8bd896b592319303c3f27fb8cc3"
        ),
        Model(
            id = "qwen7b-q2",
            title = "Qwen2.5-Coder 7B",
            fileName = "qwen2.5-coder-7b-instruct-q2_k.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q2_k.gguf",
            sizeBytes = 3_015_940_032L,
            quantization = "Q2_K",
            recommendation = "7B paling kecil · lebih lambat dan kualitas kuantisasi lebih rendah",
            ramNote = "Berat untuk RAM 6 GB; tutup aplikasi lain",
            sha256 = "6ce2630974b0ef631e2b29064cd10a9cb16b278d001961d72bfd86987392c8d9"
        ),
        Model(
            id = "whiterabbitneo-v3-7b-q2",
            title = "WhiteRabbitNeo V3 7B",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q2_K.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q2_K.gguf",
            sizeBytes = 3_015_941_312L,
            quantization = "Q2_K",
            recommendation = "WhiteRabbitNeo lokal · unduhan sekitar 3,02 GB",
            ramNote = "Model 7B cukup berat; perlu ruang penyimpanan dan RAM bebas yang memadai",
            sha256 = "0307e36b117049d30d89138966d4d676fc65e57086ee4ec90f3191514b769ddc"
        ),
        Model(
            id = "qwen7b-q3",
            title = "Qwen2.5-Coder 7B",
            fileName = "qwen2.5-coder-7b-instruct-q3_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q3_k_m.gguf",
            sizeBytes = 3_808_391_104L,
            quantization = "Q3_K_M",
            recommendation = "Model 7B yang sekarang · opsi berat",
            ramNote = "Bisa gagal dimuat pada RAM 6 GB meski context sudah diturunkan",
            sha256 = "ff5c64615cf8a44651d208e9d8da1f753feefc21605e6c1ee67957aa77257c3c"
        ),
        Model(
            id = "qwen7b-q4",
            title = "Qwen2.5-Coder 7B",
            fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            sizeBytes = 4_683_073_536L,
            quantization = "Q4_K_M",
            recommendation = "Lebih besar · dipertahankan sebagai pilihan lanjutan",
            ramNote = "Tidak direkomendasikan untuk RAM 6 GB",
            sha256 = "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c"
        ),
        // WhiteRabbitNeo — cybersecurity.
        // Tidak ada basis resmi mini (1B/3B). "Mini" = quant paling kecil dari V3-7B.
        Model(
            id = "wrn-mini",
            title = "WhiteRabbitNeo Mini",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-IQ2_M.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-IQ2_M.gguf",
            sizeBytes = 2_780_343_488L,
            quantization = "IQ2_M",
            recommendation = "WRN Mini · ethical hacking hemat · ~2,8 GB · loss lebih tinggi (IQ2)",
            ramNote = "Paling hemat di keluarga WRN; context 512–1024; HP 4–6 GB",
            sha256 = "41267ea0f58e7ec96a5bce475163bd06a0877fbc5c2689260dc6282946b6e54a"
        ),
        Model(
            id = "wrn-v3-iq3xxs",
            title = "WhiteRabbitNeo Mini+",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-IQ3_XXS.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-IQ3_XXS.gguf",
            sizeBytes = 3_114_515_648L,
            quantization = "IQ3_XXS",
            recommendation = "WRN Mini+ · ethical hacking ringan · loss lebih rendah dari Mini",
            ramNote = "Masih kelas ringan; context 1024 disarankan",
            sha256 = "87841eb11cd3190e8c2be991db6d167e08b7c5f17c87f99d88dc67e26e7efc88"
        ),
        Model(
            id = "wrn-v15a-q3m",
            title = "WhiteRabbitNeo 7B v1.5a",
            fileName = "WhiteRabbitNeo-7B-v1.5a-Q3_K_M.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo-7B-v1.5a-GGUF/resolve/main/WhiteRabbitNeo-7B-v1.5a-Q3_K_M.gguf",
            sizeBytes = 3_299_351_776L,
            quantization = "Q3_K_M",
            recommendation = "WRN v1.5a ringan · ~3.3 GB · seimbang ukuran/kualitas",
            ramNote = "RAM 6 GB: context 1024, tutup app lain",
            sha256 = "b24ad0fcfac834051cb3bf75fd7bd328837683c7c63d6a8bf99c924258f4d390"
        ),
        Model(
            id = "wrn-v3-q3m",
            title = "WhiteRabbitNeo V3 7B",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q3_K_M.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q3_K_M.gguf",
            sizeBytes = 3_808_392_384L,
            quantization = "Q3_K_M",
            recommendation = "WRN V3 menengah · ~3.8 GB",
            ramNote = "RAM 6–8 GB; turunkan context jika OOM",
            sha256 = "5e41c03157638f4d72f129b2cb4fe2475fad79cebe46fea29ceb2d73d8f85e0e"
        ),
        Model(
            id = "wrn-v3-q4m",
            title = "WhiteRabbitNeo V3 7B",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q4_K_M.gguf",
            sizeBytes = 4_683_074_752L,
            quantization = "Q4_K_M",
            recommendation = "★ Ethical hacking / server security · loss rendah (Q4) · rekomendasi utama",
            ramNote = "Ideal RAM 8 GB+; di 6 GB sering ketat — turunkan context ke 512–1024",
            sha256 = "584bfc1f4c160928842866c566129f9789c4671af8e51a9e36ba0ebf10f24f41"
        ),
        Model(
            id = "wrn-v3-q5m",
            title = "WhiteRabbitNeo V3 7B",
            fileName = "WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q5_K_M.gguf",
            url = "https://huggingface.co/bartowski/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-GGUF/resolve/main/WhiteRabbitNeo_WhiteRabbitNeo-V3-7B-Q5_K_M.gguf",
            sizeBytes = 5_444_832_448L,
            quantization = "Q5_K_M",
            recommendation = "★ Ethical hacking max quality · loss paling rendah (Q5) · ~5,4 GB",
            ramNote = "Butuh RAM 8–12 GB; tidak untuk HP 6 GB",
            sha256 = "6d7c235f2e79bc65c9cb4478b4460a8efdd1da13dd5185b49788693e1824bd58"
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
                    .put("sha256", model.sha256 ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }
}
