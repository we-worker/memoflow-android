package com.memoflow.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.memoflow.domain.AsrEngine
import com.memoflow.domain.AudioReference
import com.memoflow.domain.TranscriptSegment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalSherpaAsrEngine(
    context: Context,
    private val modelId: String,
) : AsrEngine {
    private val appContext = context.applicationContext
    private val manager = LocalAsrModelManager(appContext)

    override suspend fun transcribe(reference: AudioReference): List<TranscriptSegment> =
        withContext(Dispatchers.Default) {
            val spec = LocalAsrModelCatalog.find(modelId)
                ?: error("未知本地 ASR 模型：" + modelId)
            check(manager.isInstalled(spec)) {
                "本地模型尚未安装：" + spec.displayName
            }

            val source = File(reference.audioPath)
            check(source.exists()) { "音频文件不存在" }

            val recognizer = createRecognizer(spec, manager.installDir(spec))
            try {
                val result = mutableListOf<TranscriptSegment>()
                var startMs = reference.startMs.toLong()
                val endMs = reference.endMs.toLong().coerceAtLeast(startMs + 1L)

                while (startMs < endMs) {
                    val windowEnd = minOf(startMs + LOCAL_WINDOW_MS, endMs)
                    val stream = recognizer.createStream()
                    try {
                        AsrAudioTools.feedM4aToStream(
                            source = source,
                            startMs = startMs,
                            endMs = windowEnd,
                            stream = stream,
                        )
                        recognizer.decode(stream)
                        val decoded = recognizer.getResult(stream)
                        val text = decoded.text.trim()
                        if (text.isNotBlank()) {
                            result +=
                                TranscriptSegment(
                                    chunkId = reference.chunkId,
                                    startOffsetMs = startMs,
                                    endOffsetMs = windowEnd,
                                    text = text,
                                    language = decoded.lang.ifBlank { null },
                                    modelId = spec.id,
                                    modelVersion = spec.version,
                                )
                        }
                    } finally {
                        stream.release()
                    }
                    startMs = windowEnd
                }
                result
            } finally {
                recognizer.release()
            }
        }

    private fun createRecognizer(
        spec: LocalAsrModelSpec,
        dir: File,
    ): OfflineRecognizer {
        val modelConfig =
            when (spec.family) {
                LocalAsrFamily.QWEN3_ASR ->
                    OfflineModelConfig(
                        qwen3Asr =
                            OfflineQwen3AsrModelConfig(
                                convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                                decoder = File(dir, "decoder.int8.onnx").absolutePath,
                                tokenizer = File(dir, "tokenizer").absolutePath,
                                maxNewTokens = 512,
                            ),
                        numThreads = THREADS,
                        provider = "cpu",
                    )

                LocalAsrFamily.FUNASR_NANO ->
                    OfflineModelConfig(
                        funasrNano =
                            OfflineFunAsrNanoModelConfig(
                                encoderAdaptor =
                                    File(dir, "encoder_adaptor.int8.onnx").absolutePath,
                                llm = File(dir, "llm.int8.onnx").absolutePath,
                                embedding = File(dir, "embedding.int8.onnx").absolutePath,
                                tokenizer = File(dir, "Qwen3-0.6B").absolutePath,
                                itn = true,
                            ),
                        numThreads = THREADS,
                        provider = "cpu",
                    )

                LocalAsrFamily.PARAFORMER ->
                    OfflineModelConfig(
                        paraformer =
                            OfflineParaformerModelConfig(
                                model = File(dir, "model.int8.onnx").absolutePath,
                            ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = THREADS,
                        provider = "cpu",
                    )
            }

        return OfflineRecognizer(
            config =
                OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                ),
        )
    }

    companion object {
        private const val LOCAL_WINDOW_MS = 60_000L
        private const val THREADS = 2
    }
}
