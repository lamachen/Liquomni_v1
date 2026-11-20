package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.Lfm3OmniNativeEngine
import com.example.model.GgufModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineHardwareTest {

    private lateinit var context: Context
    private lateinit var engine: Lfm3OmniNativeEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        engine = Lfm3OmniNativeEngine(context)
    }

    @Test
    fun engineIsNotReadyInitially() {
        assertFalse(engine.isReady())
    }

    @Test
    fun engineRejectsMissingOrEmptyModel() {
        runBlocking {
            var exceptionThrown = false
            try {
                engine.loadModel()
            } catch (e: Exception) {
                exceptionThrown = true
            }
            assertTrue("Engine must reject missing model files", exceptionThrown)
            assertFalse(engine.isReady())
        }
    }

    @Test
    fun engineRejectsIncompleteOrCorruptedModel() {
        runBlocking {
            val modelsDir = GgufModelCatalog.getModelsDir(context)
            val testModel = File(modelsDir, "LFM3-Omni-2B-Abliter8-Q4_K_M.gguf")

            RandomAccessFile(testModel, "rw").use { raf ->
                val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(0x46554747)
                buf.putInt(3)
                buf.putLong(248L)
                buf.putLong(36L)
                raf.write(buf.array())
                raf.setLength(2 * 1024 * 1024)
            }

            var rejected = false
            try {
                engine.loadModel()
            } catch (e: Exception) {
                rejected = true
            }

            assertTrue("Engine must reject incomplete or corrupted files", rejected)
            assertFalse(engine.isReady())

            testModel.delete()
        }
    }

    @Test
    fun engineUnloadModelFreesResources() {
        runBlocking {
            engine.unloadModel()
            assertFalse(engine.isReady())
        }
    }
}
