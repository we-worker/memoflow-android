package com.memoflow.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.memoflow.data.*
import com.memoflow.domain.AudioReference
import com.memoflow.recording.RemoteAsrEngine
import com.memoflow.service.BootReceiver
import com.memoflow.service.RecordingForegroundService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class EchoSettings(
    val baseUrl: String = "",
    val wifiOnly: Boolean = true,
    val autoResume: Boolean = true,
    val vadThreshold: Float = 0.012f,
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
    private val syncPrefs = application.getSharedPreferences(ChunkSyncWorker.PREFS_SYNC, Context.MODE_PRIVATE)
    private val recordingPrefs = application.getSharedPreferences(RecordingForegroundService.PREFS_RECORDING, Context.MODE_PRIVATE)

    val chunks =
        dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rangeCount =
        dao.observeRangeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val transcriptCount =
        dao.observeTranscriptCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<EchoSettings> = _settings.asStateFlow()

    private val _serverStatus = MutableStateFlow("未检测")
    val serverStatus: StateFlow<String> = _serverStatus.asStateFlow()

    private val _asrState = MutableStateFlow<AsrUiState>(AsrUiState.Idle)
    val asrState: StateFlow<AsrUiState> = _asrState.asStateFlow()

    fun ranges(chunkId: String) = dao.observeRanges(chunkId)
    fun transcripts(chunkId: String) = dao.observeTranscripts(chunkId)

    fun saveBaseUrl(value: String) {
        val cleaned = value.trim().trimEnd('/')
        syncPrefs.edit().putString(ChunkSyncWorker.KEY_BASE_URL, cleaned).apply()
        _settings.value = _settings.value.copy(baseUrl = cleaned)
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
        recordingPrefs.edit().putFloat(RecordingForegroundService.KEY_VAD_THRESHOLD, value).apply()
        _settings.value = _settings.value.copy(vadThreshold = value)
    }

    fun syncNow() {
        ChunkSyncWorker.runNow(getApplication())
    }

    fun testServer() {
        val url = settings.value.baseUrl
        if (url.isBlank()) {
            _serverStatus.value = "请先填写电脑地址"
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
                            .newCall(Request.Builder().url("$url/health").get().build())
                            .execute()
                            .use { response ->
                                if (response.isSuccessful) {
                                    "已连接 · " + response.body?.string().orEmpty()
                                } else {
                                    "连接失败 · HTTP " + response.code
                                }
                            }
                    }.getOrElse { "连接失败 · " + (it.message ?: it.javaClass.simpleName) }
                }
            _serverStatus.value = message
        }
    }

    fun transcribe(chunk: AudioChunkEntity) {
        val url = settings.value.baseUrl
        if (url.isBlank()) {
            _asrState.value = AsrUiState.Error(chunk.id, "请先在“电脑与 MCP”中配置 PC 服务地址")
            return
        }
        _asrState.value = AsrUiState.Running(chunk.id)
        viewModelScope.launch {
            runCatching {
                RemoteAsrEngine(url).transcribe(
                    AudioReference(
                        chunkId = chunk.id,
                        startMs = 0,
                        endMs = chunk.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        audioPath = chunk.audioPath,
                    ),
                )
            }.onSuccess { segments ->
                dao.replaceTranscripts(chunk.id, segments.map { it.toEntity() })
                _asrState.value = AsrUiState.Done(chunk.id, segments.size)
            }.onFailure {
                _asrState.value = AsrUiState.Error(chunk.id, it.message ?: "转写失败")
            }
        }
    }

    fun deleteChunk(chunk: AudioChunkEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(chunk.audioPath).delete() }
            dao.deleteBundle(chunk.id)
            onDone()
        }
    }

    private fun loadSettings() =
        EchoSettings(
            baseUrl = syncPrefs.getString(ChunkSyncWorker.KEY_BASE_URL, "") ?: "",
            wifiOnly = syncPrefs.getBoolean("wifi_only", true),
            autoResume = recordingPrefs.getBoolean(BootReceiver.KEY_AUTO_START, true),
            vadThreshold = recordingPrefs.getFloat(RecordingForegroundService.KEY_VAD_THRESHOLD, 0.012f),
        )
}
