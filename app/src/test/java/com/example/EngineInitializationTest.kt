package com.example

import com.example.engine.LlamaNativeBridge
import com.example.model.GgufModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EngineInitializationTest {

    @Test
    fun verifyMainModelCatalogMetadata() {
        val mainModel = GgufModelCatalog.mainModelVariant
        assertEquals("LFM3-Omni-2B-Abliter8-Q4_K_M.gguf", mainModel.fileName)
        assertEquals(
            "https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/LFM3-Omni-2B-Abliter8-Q4_K_M.gguf",
            mainModel.downloadUrl
        )
    }

    @Test
    fun verifyMtmdInputTextStructLayout() {
        val (textStruct, _) = LlamaNativeBridge.buildMtmdInputText(
            text = "<|im_start|>user\nHallo<|im_end|>\n",
            addSpecial = true,
            parseSpecial = true
        )
        assertEquals(24L, textStruct.size())
        assertEquals(1.toByte(), textStruct.getByte(16L))
        assertEquals(1.toByte(), textStruct.getByte(17L))
    }

    @Test
    fun verifyPcmConversionRoundTrip() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // +16384, -16384
        val floats = LlamaNativeBridge.pcm16LeToFloatArray(pcm)
        assertEquals(2, floats.size)
        assertEquals(0.5f, floats[0], 0.0001f)
        assertEquals(-0.5f, floats[1], 0.0001f)
    }

    /**
     * Die Engine-Bibliothek muss aus native/build_engine.sh stammen: LFM3-Audio-Gen-Exporte
     * UND die omni_*-Shim-Funktionen enthalten und darf keine JNI-Bridge mitbringen.
     * Schlägt mit dem alten, committeten CPU-only-Blob absichtlich fehl.
     */
    @Test
    fun verifyNativeJniLibrariesHaveCompleteDependenciesAndLfm3AudioGenExports() {
        val jniDir = File("src/main/jniLibs/arm64-v8a").let {
            if (it.exists()) it else File("app/src/main/jniLibs/arm64-v8a")
        }
        assertTrue("jniLibs/arm64-v8a directory must exist", jniDir.exists())

        val libLlama = File(jniDir, "libllama.so")
        assertTrue("Self-contained libllama.so must exist in jniLibs/arm64-v8a", libLlama.exists())
        assertTrue("libllama.so must be > 10MB", libLlama.length() > 10 * 1024 * 1024)

        val staleBridges = jniDir.listFiles()?.filter { it.name.startsWith("librnllama") }?.map { it.name }.orEmpty()
        assertTrue(
            "Es darf keine JNI-Bridge mehr in jniLibs liegen (CPU-only-Altlast): $staleBridges. " +
                "native/build_engine.sh entfernt sie.",
            staleBridges.isEmpty()
        )

        val libLlamaBytes = libLlama.readBytes()
        val requiredSymbols = listOf(
            "mtmd_helper_gen_audio_init",
            "mtmd_helper_gen_audio_reset",
            "mtmd_helper_gen_audio_set_input",
            "mtmd_helper_gen_audio_step_prompt",
            "mtmd_helper_gen_audio_next_is_text",
            "mtmd_helper_gen_audio_step_gen",
            "mtmd_helper_gen_audio_get_output",
            "mtmd_helper_gen_audio_flush",
            "mtmd_bitmap_init_from_audio",
            "llama_set_embeddings",
            "llama_get_embeddings_ith",
            // omni_shim.cpp
            "omni_abi_version",
            "omni_load",
            "omni_free",
            "omni_lctx",
            "omni_model",
            "omni_mtmd",
            "omni_gen_set_input",
            "omni_sampler_penalties"
        )
        val asciiView = String(libLlamaBytes, Charsets.ISO_8859_1)
        for (sym in requiredSymbols) {
            assertTrue("libllama.so must export '$sym'", asciiView.contains(sym))
        }

        val systemLibs = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libvulkan.so")
        val bundledLibs = jniDir.listFiles()?.filter { it.name.endsWith(".so") }?.map { it.name }?.toSet() ?: emptySet()

        for (soFile in jniDir.listFiles()?.filter { it.name.endsWith(".so") } ?: emptyList()) {
            val needed = parseElfDtNeeded(soFile)
            for (dep in needed) {
                assertFalse(
                    "${soFile.name} must not depend on missing libllama-common.so",
                    dep == "libllama-common.so"
                )
                assertFalse(
                    "${soFile.name} must not depend on missing libc++_shared.so",
                    dep == "libc++_shared.so"
                )
                assertTrue(
                    "Dependency '$dep' required by '${soFile.name}' must be either a system library or bundled in jniLibs",
                    dep in systemLibs || dep in bundledLibs
                )
            }
        }
    }

    private fun parseElfDtNeeded(file: File): List<String> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eShoff = buf.getLong(40).toInt()
        val eShentsize = buf.getShort(58).toInt() and 0xFFFF
        val eShnum = buf.getShort(60).toInt() and 0xFFFF
        val eShstrndx = buf.getShort(62).toInt() and 0xFFFF

        val shstrOff = buf.getLong(eShoff + eShstrndx * eShentsize + 24).toInt()
        var dynstrOff = 0
        var dynamicOff = 0
        var dynamicSize = 0

        for (i in 0 until eShnum) {
            val shBase = eShoff + i * eShentsize
            val nameIdx = buf.getInt(shBase)
            val name = readCString(bytes, shstrOff + nameIdx)
            val shOffset = buf.getLong(shBase + 24).toInt()
            val shSize = buf.getLong(shBase + 32).toInt()
            if (name == ".dynstr") dynstrOff = shOffset
            if (name == ".dynamic") {
                dynamicOff = shOffset
                dynamicSize = shSize
            }
        }

        val needed = mutableListOf<String>()
        var off = dynamicOff
        while (off < dynamicOff + dynamicSize) {
            val dTag = buf.getLong(off)
            val dVal = buf.getLong(off + 8).toInt()
            if (dTag == 0L) break
            if (dTag == 1L) {
                needed.add(readCString(bytes, dynstrOff + dVal))
            }
            off += 16
        }
        return needed
    }

    private fun readCString(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < bytes.size && bytes[end].toInt() != 0) {
            end++
        }
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }
}
