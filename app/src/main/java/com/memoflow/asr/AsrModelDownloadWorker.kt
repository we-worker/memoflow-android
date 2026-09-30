package com.memoflow.asr

import android.content.Context
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

class AsrModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val client =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val spec = LocalAsrModelCatalog.find(modelId) ?: return@withContext Result.failure()
        val manager = LocalAsrModelManager(applicationContext)
        val archiveDir = File(applicationContext.cacheDir, "asr_model_downloads").apply { mkdirs() }
        val archive = File(archiveDir, spec.archiveName + ".part")
        val staging = File(applicationContext.cacheDir, "asr_model_staging/" + spec.id)
        val target = manager.installDir(spec)

        try {
            if (manager.isInstalled(spec)) return@withContext Result.success()

            ensureFreeSpace(spec)
            staging.deleteRecursively()
            target.deleteRecursively()

            setProgress(statusData(LocalModelInstallStatus.DOWNLOADING, 0, "准备下载"))
            download(spec, archive)

            setProgress(statusData(LocalModelInstallStatus.VERIFYING, 100, "校验 SHA-256"))
            val digest = sha256(archive)
            check(digest.equals(spec.sha256, ignoreCase = true)) {
                "模型校验失败：expected=" + spec.sha256 + ", actual=" + digest
            }

            setProgress(statusData(LocalModelInstallStatus.INSTALLING, 100, "解压安装"))
            extract(spec, archive, staging, target)

            check(manager.isInstalled(spec)) {
                "模型安装不完整，缺少必要文件"
            }

            archive.delete()
            staging.deleteRecursively()
            Result.success(statusData(LocalModelInstallStatus.INSTALLED, 100, "已安装"))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            archive.delete()
            staging.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            archive.delete()
            staging.deleteRecursively()
            target.deleteRecursively()
            Result.failure(
                statusData(
                    LocalModelInstallStatus.FAILED,
                    0,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun ensureFreeSpace(spec: LocalAsrModelSpec) {
        val available = StatFs(applicationContext.filesDir.absolutePath).availableBytes
        val required = (spec.archiveBytes * 23L) / 10L
        check(available >= required) {
            "存储空间不足，需要约 " + formatBytes(required) +
                " 可用空间，当前约 " + formatBytes(available)
        }
    }

    private suspend fun download(spec: LocalAsrModelSpec, archive: File) {
        var lastError: Throwable? = null

        for (url in spec.downloadUrls) {
            try {
                archive.delete()
                val requestBuilder =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "MemoFlow-Android")
                if (url.contains("api.github.com")) {
                    requestBuilder
                        .header("Accept", "application/octet-stream")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("下载失败：HTTP " + response.code)
                    }

                    val body = response.body ?: error("下载响应为空")
                    val total = body.contentLength().takeIf { it > 0 } ?: spec.archiveBytes
                    FileOutputStream(archive).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var downloaded = 0L
                            var lastPercent = -1
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                downloaded += count
                                val percent =
                                    ((downloaded * 100L) / total.coerceAtLeast(1L))
                                        .toInt()
                                        .coerceIn(0, 99)
                                if (percent != lastPercent) {
                                    setProgress(
                                        statusData(
                                            LocalModelInstallStatus.DOWNLOADING,
                                            percent,
                                            formatBytes(downloaded) + " / " + formatBytes(total),
                                        ),
                                    )
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }

                check(archive.length() > spec.archiveBytes / 2L) {
                    "下载文件异常，大小仅 " + archive.length()
                }
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw lastError ?: IllegalStateException("所有下载地址均失败")
    }

    private fun extract(
        spec: LocalAsrModelSpec,
        archive: File,
        staging: File,
        target: File,
    ) {
        staging.mkdirs()
        val stagingCanonical = staging.canonicalFile

        TarArchiveInputStream(
            BZip2CompressorInputStream(
                BufferedInputStream(FileInputStream(archive)),
            ),
        ).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) continue

                val output = File(staging, entry.name).canonicalFile
                check(
                    output.path == stagingCanonical.path ||
                        output.path.startsWith(stagingCanonical.path + File.separator),
                ) {
                    "模型压缩包包含非法路径"
                }

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { tar.copyTo(it) }
                }
            }
        }

        val root = File(staging, spec.archiveRootDir)
        check(root.isDirectory) { "压缩包缺少模型目录 " + spec.archiveRootDir }

        target.parentFile?.mkdirs()
        target.deleteRecursively()
        if (!root.renameTo(target)) {
            root.copyRecursively(target, overwrite = true)
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_STATUS = "status"
        const val KEY_PROGRESS = "progress"
        const val KEY_DETAIL = "detail"

        fun statusData(
            status: LocalModelInstallStatus,
            progress: Int,
            detail: String,
        ): Data =
            workDataOf(
                KEY_STATUS to status.name,
                KEY_PROGRESS to progress,
                KEY_DETAIL to detail,
            )

        private fun formatBytes(bytes: Long): String =
            when {
                bytes >= 1024L * 1024L * 1024L ->
                    "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
                bytes >= 1024L * 1024L ->
                    "%.1f MB".format(bytes / 1024.0 / 1024.0)
                else -> "%.0f KB".format(bytes / 1024.0)
            }
    }
}
