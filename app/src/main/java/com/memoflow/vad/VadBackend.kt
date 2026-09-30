package com.memoflow.vad

enum class VadBackend(
    val displayName: String,
    val defaultThreshold: Float,
    val description: String,
) {
    SILERO(
        displayName = "Silero VAD",
        defaultThreshold = 0.50f,
        description = "sherpa-onnx Silero；轻量、成熟，当前默认。",
    ),
    FIRERED_NON_STREAM(
        displayName = "FireRedVAD",
        defaultThreshold = 0.40f,
        description = "FireRed 官方非流式 VAD；整段 chunk 统一分析。",
    ),
    FIRERED_STREAM(
        displayName = "FireRed Stream-VAD",
        defaultThreshold = 0.40f,
        description = "FireRed 官方流式专用模型；按 10 ms 帧维护上下文。",
    );

    companion object {
        fun fromStored(value: String?): VadBackend =
            entries.firstOrNull { it.name == value } ?: SILERO
    }
}
