package com.example

import com.example.model.GgufModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelDownloadValidationTest {

    @Test
    fun verifyCatalogDownloadUrlsAreValidAbliter8HuggingFacePaths() {
        GgufModelCatalog.defaultVariants.forEach { variant ->
            assertTrue(
                "URL for ${variant.fileName} must point to abliter8-ai/lfm3-2b-omni-abliter8-gguf repo",
                variant.downloadUrl.startsWith("https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/")
            )
            assertTrue(
                "Filename in URL must match variant fileName",
                variant.downloadUrl.endsWith(variant.fileName)
            )
        }
    }

    @Test
    fun verifyVisionAudioProjectorIsConfigured() {
        val projectors = GgufModelCatalog.defaultVariants.filter { it.isMultimodalProjector }
        assertEquals(1, projectors.size)
        val bf16Projector = projectors.first()
        assertEquals("mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf", bf16Projector.fileName)
        assertTrue(bf16Projector.estimatedSizeMb in 850..960)
    }

    @Test
    fun verifyMainModelVariantsMatchAbliter8HuggingFaceRepo() {
        val expectedFiles = setOf(
            "LFM3-Omni-2B-Abliter8-Q4_K_M.gguf",
            "LFM3-Omni-2B-Abliter8-Q6_K.gguf",
            "LFM3-Omni-2B-Abliter8-Q8_0.gguf",
            "LFM3-Omni-2B-Abliter8-Q4_0.gguf"
        )
        val actualFiles = GgufModelCatalog.mainModelVariants.map { it.fileName }.toSet()
        assertEquals(expectedFiles, actualFiles)
    }
}
