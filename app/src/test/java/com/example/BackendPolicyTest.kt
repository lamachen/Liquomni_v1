package com.example

import com.example.engine.BackendPolicy
import com.example.engine.ComputeBackendMode
import com.example.engine.EngineCapabilities
import com.example.engine.GgmlDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reine JVM-Tests: [BackendPolicy] ist bewusst frei von Android- und JNA-Abhängigkeiten,
 * damit die Backend-Entscheidung — genau der Punkt, an dem die App still CPU-only war —
 * ohne Gerät prüfbar ist.
 */
class BackendPolicyTest {

    private fun caps(
        devices: List<GgmlDeviceInfo>,
        gpuCompiledIn: Boolean = false,
        vulkan: Boolean = false,
        opencl: Boolean = false,
        bound: Boolean = true,
        systemInfo: String = "CPU: NEON"
    ) = EngineCapabilities(
        devices = devices,
        gpuOffloadCompiledIn = gpuCompiledIn,
        systemInfo = systemInfo,
        vulkanDriverPresent = vulkan,
        openclDriverPresent = opencl,
        devicesGainedFromBackendScan = 0,
        engineBound = bound
    )

    private val cpuDevice = GgmlDeviceInfo(0, "CPU", "ATLAS", 0)
    private val vulkanDevice = GgmlDeviceInfo(1, "Vulkan0", "PowerVR Rogue GX-1-16", 2)

    @Test
    fun autoOhneBeschleunigerRechnetAufDerCpu() {
        val decision = BackendPolicy.resolve(
            ComputeBackendMode.AUTO,
            caps(listOf(cpuDevice))
        )
        assertEquals(0, decision.nGpuLayers)
        assertFalse("AUTO darf nicht warnen, wenn gar kein GPU gewünscht ist", decision.warned)
        assertTrue(decision.reason.contains("Engine meldet", ignoreCase = true))
    }

    @Test
    fun autoMitBeschleunigerLagertAlleSchichtenAus() {
        val decision = BackendPolicy.resolve(
            ComputeBackendMode.AUTO,
            caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true)
        )
        assertEquals(BackendPolicy.OFFLOAD_ALL, decision.nGpuLayers)
        assertEquals("AUTO → Vulkan0", decision.label)
        assertFalse(decision.warned)
    }

    /**
     * Der Kern des ursprünglichen Fehlers: `n_gpu_layers = 99` wurde gesetzt, obwohl
     * die Engine gar kein GPU-Backend hatte. Jetzt wird aus der Fähigkeit abgeleitet.
     */
    @Test
    fun gpuModusOhneGeraetWarntStattdessenSchweigtEsNicht() {
        val decision = BackendPolicy.resolve(
            ComputeBackendMode.GPU,
            caps(listOf(cpuDevice), gpuCompiledIn = false, vulkan = true)
        )
        assertEquals(0, decision.nGpuLayers)
        assertTrue("GPU-Wunsch ohne Gerät muss als Warnung sichtbar werden", decision.warned)
        assertTrue(decision.reason.contains("kein Beschleuniger", ignoreCase = true))
        assertTrue(decision.reason.contains("libllama.so", ignoreCase = true))
    }

    @Test
    fun cpuModusIstImmerCpu() {
        val decision = BackendPolicy.resolve(
            ComputeBackendMode.CPU,
            caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true),
            userLayers = 99
        )
        assertEquals(0, decision.nGpuLayers)
        assertEquals("CPU", decision.label)
        assertFalse(decision.warned)
    }

    @Test
    fun gpuModusRespektiertBenutzerwertUndBegrenztIhn() {
        val decision = BackendPolicy.resolve(
            ComputeBackendMode.GPU,
            caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true),
            userLayers = 500
        )
        assertEquals(BackendPolicy.MAX_USER_LAYERS, decision.nGpuLayers)

        val partial = BackendPolicy.resolve(
            ComputeBackendMode.GPU,
            caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true),
            userLayers = 12
        )
        assertEquals(12, partial.nGpuLayers)
    }

    @Test
    fun autoRespektiertBenutzerwertBeiVorhandenemGeraet() {
        val decision = BackendPolicy.resolve(
            ComputeBackendMode.AUTO,
            caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true),
            userLayers = 20
        )
        assertEquals(20, decision.nGpuLayers)
        assertTrue(decision.reason.contains("20 Schichten"))
    }

    @Test
    fun fehlendeErkennungFuehrtZuCpuBetriebMitWarnung() {
        val decision = BackendPolicy.resolve(ComputeBackendMode.AUTO, null)
        assertEquals(0, decision.nGpuLayers)
        assertTrue(decision.warned)
    }

    @Test
    fun diagnoseTextErklaertCpuOnlyEngine() {
        val onlyCpu = caps(listOf(cpuDevice), gpuCompiledIn = false, vulkan = false, opencl = false)
        assertTrue(onlyCpu.diagnosis.contains("CPU-only", ignoreCase = true))

        val driverButNoBackend = caps(listOf(cpuDevice), gpuCompiledIn = false, vulkan = true)
        assertTrue(driverButNoBackend.diagnosis.contains("build_engine.sh"))

        val withGpu = caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true)
        assertTrue(withGpu.hasGpuDevice)
        assertTrue(withGpu.diagnosis.contains("GPU-Auslagerung ist möglich"))
    }

    @Test
    fun threadsWerdenAufSchnelleKerneBegrenzt() {
        val decision = BackendPolicy.resolveThreads(userThreads = 8, availableProcessors = 8, bigCoreCount = 6)
        assertEquals(6, decision.threads)
        assertTrue("Begrenzung muss sichtbar gemeldet werden", decision.clampedToPhysical)
        assertTrue(decision.reason.contains("begrenzt"))
    }

    @Test
    fun threadsAutoNimmtGemesseneKerneOderProzessoren() {
        assertEquals(4, BackendPolicy.resolveThreads(0, 8, 4).threads)
        assertEquals(8, BackendPolicy.resolveThreads(0, 8, null).threads)
        assertFalse(BackendPolicy.resolveThreads(0, 8, null).clampedToPhysical)
    }

    @Test
    fun threadsOhneMessungFallenNichtAufNull() {
        val decision = BackendPolicy.resolveThreads(userThreads = 0, availableProcessors = 0, bigCoreCount = 0)
        assertEquals(1, decision.threads)
    }

    @Test
    fun beschreibungEnthaeltGeraeteUndTreiber() {
        val caps = caps(listOf(cpuDevice, vulkanDevice), gpuCompiledIn = true, vulkan = true)
        val decision = BackendPolicy.resolve(ComputeBackendMode.AUTO, caps)
        val threads = BackendPolicy.resolveThreads(0, 8, 6)
        val text = BackendPolicy.describe(caps, decision, threads)

        assertTrue(text.contains("Backend-Entscheidung"))
        assertTrue(text.contains("Vulkan0 [iGPU]"))
        assertTrue(text.contains("GPU-Treiber am Gerät: Vulkan"))
        assertTrue(text.contains("Threads: 6"))
    }
}
