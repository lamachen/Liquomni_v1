package com.example

import com.example.hardware.CpuInfoReader
import com.example.hardware.CpuTopologyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Prüft die sysfs-/procfs-Lesung gegen nachgebaute Verzeichnisbäume — ohne Android,
 * ohne Gerät. Genau diese Messung ersetzt die bisherige Annahme
 * "availableProcessors - 2 Threads".
 */
class CpuTopologyTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /** Baut einen sysfs-artigen Baum: policy-Name -> (cpus, scaling_max_freq in kHz). */
    private fun writePolicies(root: File, policies: Map<String, Pair<List<Int>, Long>>) {
        for ((policy, data) in policies) {
            val dir = File(root, policy).apply { mkdirs() }
            File(dir, "related_cpus").writeText(data.first.joinToString(" ") + "\n")
            File(dir, "cpufreq/scaling_max_freq").apply {
                parentFile?.mkdirs()
                writeText(data.second.toString())
            }
        }
    }

    @Test
    fun bigLITTLEDreiClusterWerdenErkannt() {
        // nachgebauter Tensor-artiger Fall: 1 Prime + 5 Performance + 2 Efficiency
        val root = temp.newFolder("sys")
        writePolicies(
            root,
            mapOf(
                "policy0" to (listOf(0, 1) to 1_260_000L),
                "policy2" to (listOf(2, 3, 4, 5, 6) to 2_500_000L),
                "policy6" to (listOf(7) to 3_000_000L)
            )
        )

        val topology = CpuTopologyReader.read(root, availableProcessors = 8)

        assertEquals(3, topology.clusters.size)
        assertEquals(8, topology.logicalCores)
        assertTrue("Taktraten sind messbar", topology.measured)
        assertEquals(3.0f, topology.maxFreqGhz, 0.001f)

        // 80%-Schwelle vom schnellsten Cluster (3.0 GHz): nur Performance + Prime
        assertEquals("Efficiency-Kerne dürfen nicht als Performance zählen", 6, topology.performanceCoreCount)
        assertEquals(2, topology.efficiencyCoreCount)

        assertEquals(6, CpuTopologyReader.suggestedThreads(topology))
    }

    @Test
    fun clusterOhneTaktableitungZaehlenNichtAlsPerformance() {
        val root = temp.newFolder("sys")
        val policy = File(root, "policy0").apply { mkdirs() }
        File(policy, "related_cpus").writeText("0 1 2 3")
        // bewusst keine cpufreq-Datei

        val topology = CpuTopologyReader.read(root, availableProcessors = 4)

        assertEquals(1, topology.clusters.size)
        assertFalse("ohne scaling_max_freq ist nichts gemessen", topology.measured)
        assertEquals(0, topology.performanceCoreCount)
        assertEquals("nichts gemessen => keine Empfehlung", 4, CpuTopologyReader.suggestedThreads(topology))
        assertTrue(topology.describe().contains("Takt unbekannt"))
    }

    @Test
    fun fallbackAufEinzelneCpusWennKeinePolicies() {
        val root = temp.newFolder("sys")
        for (i in 0..5) {
            val cpu = File(root, "cpu$i").apply { mkdirs() }
            File(cpu, "cpufreq/scaling_max_freq").apply {
                parentFile?.mkdirs()
                writeText((if (i < 4) 2_000_000 else 1_200_000).toString())
            }
        }

        val topology = CpuTopologyReader.read(root, availableProcessors = 6)
        assertEquals(6, topology.clusters.size)
        assertEquals(4, topology.performanceCoreCount)
        assertEquals(4, CpuTopologyReader.suggestedThreads(topology))
    }

    @Test
    fun leererSysfsBaumFaehtAufProzessorzahlZurueck() {
        val root = temp.newFolder("leer")
        val topology = CpuTopologyReader.read(root, availableProcessors = 8)
        assertTrue(topology.clusters.isEmpty())
        assertEquals(8, topology.logicalCores)
        assertEquals(0, topology.performanceCoreCount)
        assertEquals(8, CpuTopologyReader.suggestedThreads(topology))
    }

    @Test
    fun vorschlagWirdHartGedeckelt() {
        val root = temp.newFolder("sys")
        writePolicies(root, mapOf("policy0" to ((0..15).toList()) to 2_400_000L))
        val topology = CpuTopologyReader.read(root, availableProcessors = 16)
        assertEquals(8, CpuTopologyReader.suggestedThreads(topology, hardCap = 8))
        assertEquals(12, CpuTopologyReader.suggestedThreads(topology, hardCap = 12))
    }

    @Test
    fun cpuinfoFeaturesWerdenGelesen() {
        val file = temp.newFile("cpuinfo")
        file.writeText(
            """
            processor	: 0
            BogoMIPS	: 100.00
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            CPU implementer	: 0x41

            processor	: 1
            Features	: fp asimd asimddp i8mm sve2 sme
            """.trimIndent()
        )

        val isa = CpuInfoReader.read(file)
        assertTrue(isa.measured)
        assertTrue(isa.hasNeon)
        assertTrue(isa.hasDotProd)
        assertTrue(isa.hasI8mm)
        assertTrue(isa.hasSve2)
        assertTrue(isa.hasSme)
        assertTrue(isa.hasFp16)
        assertFalse(isa.hasSve) // sve2 ja, aber "sve" als eigenes Token nicht gemeldet
        assertTrue(isa.summary().contains("dotprod"))
        assertTrue(isa.summary().contains("i8mm"))
    }

    @Test
    fun unlesbaresCpuinfoBleibtEhrlich() {
        val missing = File(temp.root, "gibt-es-nicht")
        val isa = CpuInfoReader.read(missing)
        assertFalse(isa.measured)
        assertTrue(isa.features.isEmpty())
        assertTrue(isa.summary().contains("nicht lesbar"))
    }
}
