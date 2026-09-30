package com.memoflow

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.memoflow.asr.AliyunQwenAsrEngine
import com.memoflow.asr.DoubaoArkAsrEngine
import com.memoflow.data.AudioChunkEntity
import com.memoflow.data.MemoDatabase
import com.memoflow.data.toEntity
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioReference
import com.memoflow.recording.AacMediaCodecEncoder
import com.memoflow.recording.M4aChunkWriter
import com.memoflow.service.BootReceiver
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreInstrumentedTest {
    @Test
    fun roomPersistsAndUpdatesChunkState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room.inMemoryDatabaseBuilder(context, MemoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val entity =
                AudioChunkEntity(
                    id = "chunk-1",
                    deviceId = "test",
                    startTimeUtcMs = 100,
                    endTimeUtcMs = 200,
                    durationMs = 100,
                    audioPath = "/tmp/chunk.m4a",
                    codec = "aac-lc",
                    container = "m4a",
                    sampleRate = 16000,
                    channels = 1,
                    bitrate = 24000,
                    fileSize = 1234,
                    checksumSha256 = "abc",
                    state = "COMPLETE",
                    schemaVersion = 1,
                    postProcessState = "READY",
                )
            db.chunks().upsert(entity)
            assertEquals("chunk-1", db.chunks().observe().first().single().id)
            assertEquals(1, db.chunks().pending().size)

            db.chunks().updateState("chunk-1", "UPLOADED")
            assertTrue(db.chunks().pending().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun syntheticPcmProducesReadableM4a() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(context.cacheDir, "m4a-test").apply {
            deleteRecursively()
            mkdirs()
        }

        val encoder = AacMediaCodecEncoder()
        val writer = M4aChunkWriter(outputDir)

        writer.start()
        encoder.open(sampleRate = 16000, channels = 1, bitrate = 24000)

        try {
            repeat(20) { index ->
                val frame =
                    AudioFrame(
                        pcm = sineLikePcm(index),
                        timestampNs = index * 100_000_000L,
                        sampleRate = 16000,
                        channels = 1,
                    )
                val encoded = encoder.encode(frame)
                encoder.outputFormat?.let(writer::onFormat)
                encoded.forEach(writer::write)
            }

            val tail = encoder.flush()
            encoder.outputFormat?.let(writer::onFormat)
            tail.forEach(writer::write)
        } finally {
            encoder.close()
        }

        val chunk = writer.finish()
        assertNotNull(chunk)
        val file = File(chunk!!.audioPath)
        assertTrue(file.exists())
        assertTrue(file.length() > 0L)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            assertTrue("M4A duration should be positive", duration > 0L)
        } finally {
            retriever.release()
        }
    }

    @Test
    fun completedChunkRunsPostVadAndCreatesWaveform() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(context.cacheDir, "post-vad-test").apply {
            deleteRecursively()
            mkdirs()
        }

        val encoder = AacMediaCodecEncoder()
        val writer = M4aChunkWriter(outputDir)
        writer.start()
        encoder.open(sampleRate = 16000, channels = 1, bitrate = 24000)

        try {
            repeat(40) { index ->
                val frame =
                    AudioFrame(
                        pcm = sineLikePcm(index),
                        timestampNs = index * 100_000_000L,
                        sampleRate = 16000,
                        channels = 1,
                    )
                val encoded = encoder.encode(frame)
                encoder.outputFormat?.let(writer::onFormat)
                encoded.forEach(writer::write)
            }
            val tail = encoder.flush()
            encoder.outputFormat?.let(writer::onFormat)
            tail.forEach(writer::write)
        } finally {
            encoder.close()
        }

        val chunk = writer.finish()
        assertNotNull(chunk)

        val db = MemoDatabase.get(context)
        val entity = chunk!!.toEntity()
        db.chunks().upsert(entity)

        val workerClass =
            Class.forName("com.memoflow.processing.AudioPostProcessWorker")
        val companion = workerClass.getDeclaredField("Companion").get(null)
        companion.javaClass
            .getMethod("enqueue", Context::class.java, String::class.java)
            .invoke(companion, context, chunk.id)

        var processed = db.chunks().getChunk(chunk.id)
        for (attempt in 0 until 120) {
            if (processed?.postProcessState == "DONE" || processed?.postProcessState == "FAILED") {
                break
            }
            delay(250)
            processed = db.chunks().getChunk(chunk.id)
        }

        assertNotNull(processed)
        assertEquals("DONE", processed!!.postProcessState)
        assertTrue(!processed!!.waveformPath.isNullOrBlank())
        assertTrue(File(processed!!.waveformPath!!).exists())
        assertTrue(File(processed!!.waveformPath!!).length() > 12L)

        db.chunks().deleteBundle(chunk.id)
        File(chunk.audioPath).delete()
        processed!!.speechAudioPath?.let { File(it).delete() }
        processed!!.waveformPath?.let { File(it).delete() }
        Unit
    }

    @Test
    fun aliyunCloudAsrSendsBase64AudioAndParsesText() = runBlocking {
        val source = createTestM4a("aliyun-cloud-asr-test")
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"阿里云模拟转写\"}}]}"),
        )
        server.start()

        try {
            val engine =
                AliyunQwenAsrEngine(
                    apiKey = "test-key",
                    endpoint = server.url("/compatible-mode/v1/chat/completions").toString(),
                    client = OkHttpClient(),
                )
            val result =
                engine.transcribe(
                    AudioReference(
                        chunkId = "aliyun-test",
                        startMs = 0,
                        endMs = 2_000,
                        audioPath = source.absolutePath,
                    ),
                )

            assertEquals(1, result.size)
            assertEquals("阿里云模拟转写", result.single().text)

            val request = server.takeRequest()
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"model\":\"qwen3-asr-flash\""))
            assertTrue(body.contains("data:audio/mp4;base64,"))
            assertTrue(body.contains("\"enable_itn\":true"))
        } finally {
            server.shutdown()
            source.delete()
        }
        Unit
    }

    @Test
    fun doubaoCloudAsrSendsInputAudioAndParsesText() = runBlocking {
        val source = createTestM4a("doubao-cloud-asr-test")
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"豆包模拟转写\"}}]}"),
        )
        server.start()

        try {
            val engine =
                DoubaoArkAsrEngine(
                    apiKey = "ark-test-key",
                    model = "doubao-audio-test",
                    endpoint = server.url("/api/v3/chat/completions").toString(),
                    client = OkHttpClient(),
                )
            val result =
                engine.transcribe(
                    AudioReference(
                        chunkId = "doubao-test",
                        startMs = 0,
                        endMs = 2_000,
                        audioPath = source.absolutePath,
                    ),
                )

            assertEquals(1, result.size)
            assertEquals("豆包模拟转写", result.single().text)

            val request = server.takeRequest()
            assertEquals("Bearer ark-test-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"type\":\"input_audio\""))
            assertTrue(body.contains("\"format\":\"audio/mp4\""))
            assertTrue(body.contains("\"model\":\"doubao-audio-test\""))
            assertFalse(body.contains("ark-test-key"))
        } finally {
            server.shutdown()
            source.delete()
        }
        Unit
    }

    @Test
    fun sherpaSileroVadLoadsModelAndAcceptsPcm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clazz =
            Class.forName("com.memoflow.recording.SherpaOnnxSileroVadEngine")
        val constructor =
            clazz.getConstructor(
                Context::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        val vad =
            constructor.newInstance(
                context,
                0.5f,
                0.5f,
                0.25f,
                30.0f,
                16000,
            )
        try {
            val accept = clazz.getMethod("smokeTestPcm", ByteArray::class.java)
            repeat(12) { index ->
                accept.invoke(vad, sineLikePcm(index))
            }
            clazz.getMethod("smokeFlush").invoke(vad)
        } finally {
            clazz.getMethod("smokeRelease").invoke(vad)
        }
    }

    @Test
    fun bootReceiverDefersMicrophoneResumeUntilUiIsVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean(BootReceiver.KEY_AUTO_START, true).commit()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(prefs.getBoolean(BootReceiver.KEY_RESUME_REQUESTED, false))
    }

    private suspend fun createTestM4a(name: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(context.cacheDir, name).apply {
            deleteRecursively()
            mkdirs()
        }
        val encoder = AacMediaCodecEncoder()
        val writer = M4aChunkWriter(outputDir)
        writer.start()
        encoder.open(sampleRate = 16000, channels = 1, bitrate = 24000)
        try {
            repeat(20) { index ->
                val frame =
                    AudioFrame(
                        pcm = sineLikePcm(index),
                        timestampNs = index * 100_000_000L,
                        sampleRate = 16000,
                        channels = 1,
                    )
                val encoded = encoder.encode(frame)
                encoder.outputFormat?.let(writer::onFormat)
                encoded.forEach(writer::write)
            }
            val tail = encoder.flush()
            encoder.outputFormat?.let(writer::onFormat)
            tail.forEach(writer::write)
        } finally {
            encoder.close()
        }
        return File(requireNotNull(writer.finish()).audioPath)
    }

    private fun sineLikePcm(frameIndex: Int): ByteArray {
        val pcm = ByteArray(3200)
        var sampleIndex = 0
        while (sampleIndex < 1600) {
            val value = (((sampleIndex + frameIndex * 13) % 100) - 50) * 300
            val sample = value.toShort().toInt()
            val byteIndex = sampleIndex * 2
            pcm[byteIndex] = (sample and 0xff).toByte()
            pcm[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
            sampleIndex++
        }
        return pcm
    }
}
