package com.memoflow.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAsrModelCatalogTest {
    @Test
    fun catalogContainsSupportedModelsWithoutDefaultDownload() {
        assertEquals(
            setOf(
                "qwen3-asr-0.6b-int8",
                "funasr-nano-2512-int8",
                "paraformer-zh-int8",
            ),
            LocalAsrModelCatalog.models.map { it.id }.toSet(),
        )
        LocalAsrModelCatalog.models.forEach { spec ->
            assertTrue(spec.archiveBytes > 100L * 1024L * 1024L)
            if (spec.sha256.isNotBlank()) {
                assertEquals(64, spec.sha256.length)
            }
            assertTrue(spec.downloadUrls.isNotEmpty())
            assertTrue(spec.requiredFiles.isNotEmpty())
        }
    }

    @Test
    fun paraformerIsTheSmallestInitialOption() {
        val smallest = LocalAsrModelCatalog.models.minBy { it.archiveBytes }
        assertEquals("paraformer-zh-int8", smallest.id)
    }
}
