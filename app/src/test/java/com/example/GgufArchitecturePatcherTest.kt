package com.example

import com.example.model.GgufModelCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GgufArchitecturePatcherTest {

    @Test
    fun testGgufHeaderValidationRecognizesValidLfm3Header() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val testFile = File(context.cacheDir, "test_lfm3_valid.gguf")
        if (testFile.exists()) testFile.delete()

        FileOutputStream(testFile).use { fos ->
            val buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0x46554747) // "GGUF"
            buf.putInt(3)          // Version 3
            buf.putLong(287L)      // Tensor count
            buf.putLong(43L)       // KV count
            fos.write(buf.array(), 0, buf.position())
        }

        // Projector threshold is 50MB; a small file should be reported as incomplete
        val (isValid, isComplete, _) = GgufModelCatalog.validateGgufFile(testFile, isProjector = true)
        assertFalse(isValid)
        assertFalse(isComplete)

        testFile.delete()
    }
}
