package com.memoflow.asr

enum class LocalAsrFamily {
    QWEN3_ASR,
    FUNASR_NANO,
    PARAFORMER,
}

data class LocalAsrModelSpec(
    val id: String,
    val displayName: String,
    val version: String,
    val family: LocalAsrFamily,
    val description: String,
    val languages: String,
    val archiveName: String,
    val archiveRootDir: String,
    val archiveBytes: Long,
    val sha256: String,
    val downloadUrls: List<String>,
    val requiredFiles: List<String>,
)

object LocalAsrModelCatalog {
    val models =
        listOf(
            LocalAsrModelSpec(
                id = "qwen3-asr-0.6b-int8",
                displayName = "Qwen3-ASR 0.6B INT8",
                version = "2026-03-25",
                family = LocalAsrFamily.QWEN3_ASR,
                description = "多语言 + 中文方言/口音；本地离线识别，模型体积最大。",
                languages = "中文、粤语、英语及多语种/中文方言",
                archiveName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
                archiveRootDir = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
                archiveBytes = 878_702_423L,
                sha256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96",
                downloadUrls =
                    listOf(
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
                        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/390698077",
                    ),
                requiredFiles =
                    listOf(
                        "conv_frontend.onnx",
                        "encoder.int8.onnx",
                        "decoder.int8.onnx",
                        "tokenizer",
                    ),
            ),
            LocalAsrModelSpec(
                id = "funasr-nano-2512-int8",
                displayName = "Fun-ASR-Nano-2512 INT8",
                version = "2025-12-30",
                family = LocalAsrFamily.FUNASR_NANO,
                description = "Fun-ASR-Nano-2512 的 sherpa-onnx INT8 版本，适合中文/英文/日文。",
                languages = "中文（含多种方言/口音）、英语、日语",
                archiveName = "sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2",
                archiveRootDir = "sherpa-onnx-funasr-nano-int8-2025-12-30",
                archiveBytes = 841_730_611L,
                sha256 = "eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b",
                downloadUrls =
                    listOf(
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2",
                        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/394517157",
                    ),
                requiredFiles =
                    listOf(
                        "encoder_adaptor.int8.onnx",
                        "llm.int8.onnx",
                        "embedding.int8.onnx",
                        "Qwen3-0.6B",
                    ),
            ),
            LocalAsrModelSpec(
                id = "paraformer-zh-int8",
                displayName = "Paraformer 中文 INT8",
                version = "2025-10-07",
                family = LocalAsrFamily.PARAFORMER,
                description = "体积和运行压力更小，适合普通话及部分中文方言场景。",
                languages = "中文为主",
                archiveName = "sherpa-onnx-paraformer-zh-int8-2025-10-07.tar.bz2",
                archiveRootDir = "sherpa-onnx-paraformer-zh-int8-2025-10-07",
                archiveBytes = 228_262_632L,
                sha256 = "a071ee5419e14adb34d7f970ab98105a45e6608018b168f023ca2e4810744abe",
                downloadUrls =
                    listOf(
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-int8-2025-10-07.tar.bz2",
                        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/301503409",
                    ),
                requiredFiles =
                    listOf(
                        "model.int8.onnx",
                        "tokens.txt",
                    ),
            ),
        )

    fun find(id: String): LocalAsrModelSpec? = models.firstOrNull { it.id == id }
}

enum class LocalModelInstallStatus {
    NOT_INSTALLED,
    WAITING_FOR_WIFI,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    INSTALLED,
    FAILED,
    CANCELLED,
}

data class LocalAsrModelState(
    val spec: LocalAsrModelSpec,
    val status: LocalModelInstallStatus,
    val progressPercent: Int = 0,
    val detail: String = "",
)
