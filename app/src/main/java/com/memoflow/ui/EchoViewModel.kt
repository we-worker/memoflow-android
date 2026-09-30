package com.memoflow.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.memoflow.asr.*
import com.memoflow.data.*
import com.memoflow.domain.AsrEngine
import com.memoflow.domain.AudioReference
import com.memoflow.processing.AudioPostProcessWorker
import com.memoflow.processing.WaveformStore
import com.memoflow.recording.RemoteAsrEngine
import com.memoflow.service.BootReceiver
import com.memoflow.service.RecordingForegroundService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class EchoSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val wifiOnly: Boolean = true,
    val autoResume: Boolean = true,
    val vadThreshold: Float = RecordingForegroundService.DEFAULT_SILERO_THRESHOLD,
    val vadSegmentGapMinutes: Int = RecordingForegroundService.DEFAULT_VAD_SEGMENT_GAP_MINUTES,
    val cleanupRetentionDays: Int = 7,
)

sealed interface AsrUiState {
    data object Idle : AsrUiState
    data class Running(val chunkId: String) : AsrUiState
    data class Done(val chunkId: String, val count: Int) : AsrUiState
    data class Error(val chunkId: String?, val message: String) : AsrUiState
}

class EchoViewModel(application: Application) : AndroidViewModel(application) {
    private val db = MemoDatabase.get(application)
    private val dao = db.chunks()
    private val syncPrefs =
        application.getSharedPreferences(ChunkSyncWorker.PREFS_SYNC, Context.MODE_PRIVATE)
    private val recordingPrefs =
        application.getSharedPreferences(
            RecordingForegroundService.PREFS_RECORDING,
            Context.MODE_PRIVATE,
        )
    private val asrPrefs =
        application.getSharedPreferences(PREFS_ASR, Context.MODE_PRIVATE)
    private val secretStore = SecretStore(application)
    private val modelManager = LocalAsrModelManager(application)

    val chunks =
        dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rangeCount =
        dao.observeRangeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val transcriptCount =
        dao.observeTranscriptCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<EchoSettings> = _settings.asStateFlow()

    private val _asrSettings = MutableStateFlow(loadAsrSettings())
    val asrSettings: StateFlow<AsrSettings> = _asrSettings.asStateFlow()

    private val _localModels = MutableStateFlow<List<LocalAsrModelState>>(emptyList())
    val localModels: StateFlow<List<LocalAsrModelState>> = _localModels.asStateFlow()

    private val _serverStatus = MutableStateFlow("未检测")
    val serverStatus: StateFlow<String> = _serverStatus.asStateFlow()

    private val _asrState = MutableStateFlow<AsrUiState>(AsrUiState.Idle)
    val asrState: StateFlow<AsrUiState> = _asrState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _localModels.value = modelManager.snapshot()
                delay(1_500)
            }
        }
    }

    fun ranges(chunkId: String) = dao.observeRanges(chunkId)
    fun transcripts(chunkId: String) = dao.observeTranscripts(chunkId)

    fun ensurePostProcessed(chunk: AudioChunkEntity) {
        if (!chunk.originalAvailable) return
        if (!File(chunk.audioPath).exists()) return
        if (chunk.postProcessState == "PROCESSING") return

        val current = settings.value
        val desiredGapMs = current.vadSegmentGapMinutes * 60_000L
        val parametersMatch =
            kotlin.math.abs(chunk.appliedVadThreshold - current.vadThreshold) < 0.0001f &&
                chunk.appliedVadMergeSilenceMs == desiredGapMs

        if (chunk.postProcessState == "DONE" && parametersMatch) return
        AudioPostProcessWorker.enqueue(getApplication(), chunk.id)
    }

    suspend fun loadWaveform(path: String?): List<Float> =
        withContext(Dispatchers.IO) {
            if (path.isNullOrBlank()) emptyList() else WaveformStore.read(File(path))
        }

    fun saveBaseUrl(value: String) {
        val cleaned = value.trim().trimEnd('/')
        syncPrefs.edit().putString(ChunkSyncWorker.KEY_BASE_URL, cleaned).apply()
        _settings.value = _settings.value.copy(baseUrl = cleaned)
    }

    fun saveApiKey(value: String) {
        val cleaned = value.trim()
        syncPrefs.edit().putString(ChunkSyncWorker.KEY_API_KEY, cleaned).apply()
        _settings.value = _settings.value.copy(apiKey = cleaned)
    }

    fun setWifiOnly(enabled: Boolean) {
        syncPrefs.edit().putBoolean("wifi_only", enabled).apply()
        _settings.value = _settings.value.copy(wifiOnly = enabled)
        ChunkSyncWorker.schedule(getApplication())
    }

    fun setAutoResume(enabled: Boolean) {
        recordingPrefs.edit().putBoolean(BootReceiver.KEY_AUTO_START, enabled).apply()
        _settings.value = _settings.value.copy(autoResume = enabled)
    }

    fun setVadThreshold(value: Float) {
        val safe = value.coerceIn(0.05f, 0.95f)
        recordingPrefs.edit()
            .putFloat(RecordingForegroundService.KEY_SILERO_THRESHOLD, safe)
            .apply()
        _settings.value = _settings.value.copy(vadThreshold = safe)
        viewModelScope.launch { dao.markPostProcessStale() }
    }

    fun setVadSegmentGapMinutes(minutes: Int) {
        val safe = minutes.coerceIn(1, 10)
        recordingPrefs.edit()
            .putInt(RecordingForegroundService.KEY_VAD_SEGMENT_GAP_MINUTES, safe)
            .apply()
        _settings.value = _settings.value.copy(vadSegmentGapMinutes = safe)
        viewModelScope.launch { dao.markPostProcessStale() }
    }

    fun setCleanupRetentionDays(days: Int) {
        val safe = days.coerceIn(1, 30)
        recordingPrefs.edit().putInt(KEY_CLEANUP_RETENTION_DAYS, safe).apply()
        _settings.value = _settings.value.copy(cleanupRetentionDays = safe)
    }

    fun setAsrProvider(provider: AsrProvider) {
        asrPrefs.edit().putString(KEY_ASR_PROVIDER, provider.name).apply()
        _asrSettings.value = _asrSettings.value.copy(provider = provider)
    }

    fun selectLocalModel(modelId: String) {
        asrPrefs.edit().putString(KEY_LOCAL_MODEL_ID, modelId).apply()
        _asrSettings.value = _asrSettings.value.copy(localModelId = modelId)
    }

    fun downloadLocalModel(modelId: String) {
        modelManager.enqueueDownload(modelId)
        viewModelScope.launch {
            delay(200)
            _localModels.value = modelManager.snapshot()
        }
    }

    fun cancelLocalModelDownload(modelId: String) {
        modelManager.cancelDownload(modelId)
    }

    fun deleteLocalModel(modelId: String) {
        modelManager.deleteModel(modelId)
        viewModelScope.launch { _localModels.value = modelManager.snapshot() }
    }

    fun saveAliyunApiKey(value: String) {
        secretStore.put(SECRET_ALIYUN_KEY, value.trim())
        _asrSettings.value = _asrSettings.value.copy(aliyunApiKey = value.trim())
    }

    fun saveAliyunModel(value: String) {
        val cleaned = value.trim().ifBlank { "qwen3-asr-flash" }
        asrPrefs.edit().putString(KEY_ALIYUN_MODEL, cleaned).apply()
        _asrSettings.value = _asrSettings.value.copy(aliyunModel = cleaned)
    }

    fun saveDoubaoApiKey(value: String) {
        secretStore.put(SECRET_DOUBAO_KEY, value.trim())
        _asrSettings.value = _asrSettings.value.copy(doubaoApiKey = value.trim())
    }

    fun saveDoubaoModel(value: String) {
        val cleaned = value.trim()
        asrPrefs.edit().putString(KEY_DOUBAO_MODEL, cleaned).apply()
        _asrSettings.value = _asrSettings.value.copy(doubaoModel = cleaned)
    }

    fun syncNow() {
        ChunkSyncWorker.runNow(getApplication())
    }

    fun testServer() {
        val current = settings.value
        if (current.baseUrl.isBlank()) {
            _serverStatus.value = "请先填写电脑地址"
            return
        }
        if (current.apiKey.isBlank()) {
            _serverStatus.value = "请先填写访问密钥"
            return
        }

        _serverStatus.value = "连接中…"
        viewModelScope.launch {
            val message =
                withContext(Dispatchers.IO) {
                    runCatching {
                        OkHttpClient.Builder()
                            .connectTimeout(4, TimeUnit.SECONDS)
                            .readTimeout(6, TimeUnit.SECONDS)
                            .build()
                            .newCall(
                                Request.Builder()
                                    .url(current.baseUrl.trimEnd('/') + "/health")
                                    .header(ChunkSyncWorker.API_KEY_HEADER, current.apiKey)
                                    .get()
                                    .build(),
                            )
                            .execute()
                            .use { response ->
                                if (response.isSuccessful) {
                                    "已认证连接 · " + response.body?.string().orEmpty()
                                } else {
                                    "连接失败 · HTTP " + response.code
                                }
                            }
                    }.getOrElse {
                        "连接失败 · " + (it.message ?: it.javaClass.simpleName)
                    }
                }
            _serverStatus.value = message
        }
    }

    fun transcribe(chunk: AudioChunkEntity) {
        if (!chunk.originalAvailable || !File(chunk.audioPath).exists()) {
            _asrState.value =
                AsrUiState.Error(
                    chunk.id,
                    "原始素材已删除，不能重新生成带原始时间坐标的 ASR；已有转写结果仍会保留",
                )
            return
        }

        val reference =
            AudioReference(
                chunkId = chunk.id,
                startMs = 0,
                endMs = chunk.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                audioPath = chunk.audioPath,
            )

        val engine =
            runCatching { createAsrEngine() }
                .getOrElse {
                    _asrState.value =
                        AsrUiState.Error(chunk.id, it.message ?: "ASR 配置不完整")
                    return
                }

        _asrState.value = AsrUiState.Running(chunk.id)
        viewModelScope.launch {
            runCatching {
                engine.transcribe(reference)
            }.onSuccess { segments ->
                dao.replaceTranscripts(chunk.id, segments.map { it.toEntity() })
                _asrState.value = AsrUiState.Done(chunk.id, segments.size)
            }.onFailure {
                _asrState.value = AsrUiState.Error(chunk.id, it.message ?: "转写失败")
            }
        }
    }

    private fun createAsrEngine(): AsrEngine {
        val asr = asrSettings.value
        return when (asr.provider) {
            AsrProvider.PC -> {
                val current = settings.value
                require(current.baseUrl.isNotBlank() && current.apiKey.isNotBlank()) {
                    "请先在“电脑与 MCP”中配置 PC 地址和访问密钥"
                }
                RemoteAsrEngine(current.baseUrl, current.apiKey)
            }

            AsrProvider.LOCAL -> {
                require(asr.localModelId.isNotBlank()) { "请先选择一个本地 ASR 模型" }
                val spec =
                    LocalAsrModelCatalog.find(asr.localModelId)
                        ?: error("本地模型不存在")
                require(modelManager.isInstalled(spec)) {
                    "请先下载并安装 " + spec.displayName
                }
                LocalSherpaAsrEngine(getApplication(), asr.localModelId)
            }

            AsrProvider.ALIYUN -> {
                require(asr.aliyunApiKey.isNotBlank()) { "请填写阿里云 DashScope API Key" }
                AliyunQwenAsrEngine(
                    apiKey = asr.aliyunApiKey,
                    model = asr.aliyunModel.ifBlank { "qwen3-asr-flash" },
                )
            }

            AsrProvider.DOUBAO -> {
                require(asr.doubaoApiKey.isNotBlank()) { "请填写豆包 Ark API Key" }
                require(asr.doubaoModel.isNotBlank()) { "请填写支持音频输入的豆包模型 ID" }
                DoubaoArkAsrEngine(
                    apiKey = asr.doubaoApiKey,
                    model = asr.doubaoModel,
                )
            }
        }
    }

    fun deleteOriginal(chunk: AudioChunkEntity) {
        if (!chunk.originalAvailable) return
        val speechPath = chunk.speechAudioPath ?: return
        if (!File(speechPath).exists()) return

        viewModelScope.launch {
            val deleted =
                withContext(Dispatchers.IO) {
                    val original = File(chunk.audioPath)
                    !original.exists() || original.delete()
                }
            if (deleted) dao.markOriginalDeleted(chunk.id)
        }
    }

    fun deleteOriginals(chunks: List<AudioChunkEntity>) {
        chunks.forEach(::deleteOriginal)
    }

    fun deleteChunk(chunk: AudioChunkEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(chunk.audioPath).delete()
                chunk.speechAudioPath?.let { File(it).delete() }
                chunk.waveformPath?.let { File(it).delete() }
            }
            dao.deleteBundle(chunk.id)
            onDone()
        }
    }

    private fun loadSettings() =
        EchoSettings(
            baseUrl = syncPrefs.getString(ChunkSyncWorker.KEY_BASE_URL, "") ?: "",
            apiKey = syncPrefs.getString(ChunkSyncWorker.KEY_API_KEY, "") ?: "",
            wifiOnly = syncPrefs.getBoolean("wifi_only", true),
            autoResume = recordingPrefs.getBoolean(BootReceiver.KEY_AUTO_START, true),
            vadThreshold =
                recordingPrefs.getFloat(
                    RecordingForegroundService.KEY_SILERO_THRESHOLD,
                    RecordingForegroundService.DEFAULT_SILERO_THRESHOLD,
                ).coerceIn(0.05f, 0.95f),
            vadSegmentGapMinutes =
                recordingPrefs.getInt(
                    RecordingForegroundService.KEY_VAD_SEGMENT_GAP_MINUTES,
                    RecordingForegroundService.DEFAULT_VAD_SEGMENT_GAP_MINUTES,
                ).coerceIn(1, 10),
            cleanupRetentionDays =
                recordingPrefs.getInt(KEY_CLEANUP_RETENTION_DAYS, 7).coerceIn(1, 30),
        )

    private fun loadAsrSettings(): AsrSettings {
        val provider =
            asrPrefs.getString(KEY_ASR_PROVIDER, AsrProvider.PC.name)
                ?.let { runCatching { AsrProvider.valueOf(it) }.getOrNull() }
                ?: AsrProvider.PC

        return AsrSettings(
            provider = provider,
            localModelId = asrPrefs.getString(KEY_LOCAL_MODEL_ID, "") ?: "",
            aliyunModel =
                asrPrefs.getString(KEY_ALIYUN_MODEL, "qwen3-asr-flash")
                    ?: "qwen3-asr-flash",
            doubaoModel = asrPrefs.getString(KEY_DOUBAO_MODEL, "") ?: "",
            aliyunApiKey = secretStore.get(SECRET_ALIYUN_KEY),
            doubaoApiKey = secretStore.get(SECRET_DOUBAO_KEY),
        )
    }

    companion object {
        private const val KEY_CLEANUP_RETENTION_DAYS = "cleanup_retention_days"

        private const val PREFS_ASR = "asr_settings"
        private const val KEY_ASR_PROVIDER = "provider"
        private const val KEY_LOCAL_MODEL_ID = "local_model_id"
        private const val KEY_ALIYUN_MODEL = "aliyun_model"
        private const val KEY_DOUBAO_MODEL = "doubao_model"

        private const val SECRET_ALIYUN_KEY = "aliyun_api_key"
        private const val SECRET_DOUBAO_KEY = "doubao_api_key"
    }
}
