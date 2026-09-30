package com.memoflow.asr

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalAsrModelManager(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun installDir(spec: LocalAsrModelSpec): File =
        File(appContext.filesDir, "asr_models/" + spec.id)

    fun isInstalled(spec: LocalAsrModelSpec): Boolean {
        val dir = installDir(spec)
        if (!dir.isDirectory) return false
        return spec.requiredFiles.all { File(dir, it).exists() }
    }

    fun enqueueDownload(modelId: String) {
        val spec = LocalAsrModelCatalog.find(modelId) ?: return
        if (isInstalled(spec)) return

        val request =
            OneTimeWorkRequestBuilder<AsrModelDownloadWorker>()
                .setInputData(workDataOf(AsrModelDownloadWorker.KEY_MODEL_ID to modelId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

        workManager.enqueueUniqueWork(
            workName(modelId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork(workName(modelId))
    }

    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        LocalAsrModelCatalog.find(modelId)?.let { installDir(it).deleteRecursively() }
    }

    suspend fun snapshot(): List<LocalAsrModelState> =
        withContext(Dispatchers.IO) {
            LocalAsrModelCatalog.models.map { spec ->
                if (isInstalled(spec)) {
                    LocalAsrModelState(
                        spec = spec,
                        status = LocalModelInstallStatus.INSTALLED,
                        progressPercent = 100,
                        detail = "已安装",
                    )
                } else {
                    val info =
                        runCatching {
                            workManager
                                .getWorkInfosForUniqueWork(workName(spec.id))
                                .get()
                                .lastOrNull()
                        }.getOrNull()

                    stateFromWork(spec, info)
                }
            }
        }

    private fun stateFromWork(
        spec: LocalAsrModelSpec,
        info: WorkInfo?,
    ): LocalAsrModelState {
        if (info == null) {
            return LocalAsrModelState(spec, LocalModelInstallStatus.NOT_INSTALLED)
        }

        val progress = info.progress
        val output = info.outputData
        val source =
            if (progress.keyValueMap.isNotEmpty()) progress else output
        val explicit =
            source.getString(AsrModelDownloadWorker.KEY_STATUS)
                ?.let { runCatching { LocalModelInstallStatus.valueOf(it) }.getOrNull() }
        val percent =
            source.getInt(AsrModelDownloadWorker.KEY_PROGRESS, 0)
                .coerceIn(0, 100)
        val detail = source.getString(AsrModelDownloadWorker.KEY_DETAIL).orEmpty()

        val status =
            explicit ?: when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    LocalModelInstallStatus.WAITING_FOR_WIFI
                WorkInfo.State.RUNNING -> LocalModelInstallStatus.DOWNLOADING
                WorkInfo.State.CANCELLED -> LocalModelInstallStatus.CANCELLED
                WorkInfo.State.FAILED -> LocalModelInstallStatus.FAILED
                WorkInfo.State.SUCCEEDED ->
                    if (isInstalled(spec)) {
                        LocalModelInstallStatus.INSTALLED
                    } else {
                        LocalModelInstallStatus.FAILED
                    }
            }

        return LocalAsrModelState(spec, status, percent, detail)
    }

    companion object {
        fun workName(modelId: String) = "asr-model-download-" + modelId
    }
}
