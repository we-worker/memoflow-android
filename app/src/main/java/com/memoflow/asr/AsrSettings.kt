package com.memoflow.asr

enum class AsrProvider {
    PC,
    LOCAL,
    ALIYUN,
    DOUBAO,
}

data class AsrSettings(
    val provider: AsrProvider = AsrProvider.PC,
    val localModelId: String = "",
    val aliyunModel: String = "qwen3-asr-flash",
    val doubaoModel: String = "",
    val aliyunApiKey: String = "",
    val doubaoApiKey: String = "",
)
