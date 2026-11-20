package com.example.hardware

import java.io.File
import java.util.Locale

/**
 * Liest die echte CPU-Topologie aus /sys (cpufreq-Policies).
 *
 * Warum das nötig ist: die Engine hat ihre Thread-Zahl bisher aus
 * `Runtime.availableProcessors()` hochgerechnet ("cores - 2"). Auf big.LITTLE-SoCs
 * ist das daneben — liegen die Efficiency-Kerne mit in der Auswahl, wird jeder
 * Decode-Schritt vom langsamsten Worker ausgebremst, denn llama.cpp verteilt seine
 * Threads nicht selbst auf die schnellen Kerne.
 *
 * Nur Datei-Lektüre, keine Android-APIs: damit ist der Reader in einem JVM-Unit-Test
 * gegen ein nachgebautes /sys im Temp-Verzeichnis prüfbar
 * (app/src/test/java/com/example/CpuTopologyTest.kt).
 */

data class CpuCluster(
    /** Cluster-Kennung, z. B. "policy0" oder "cpu3" (Fallback ohne Policies). */
    val policy: String,
    /** Logische Kernnummern dieses Clusters. */
    val cpus: List<Int>,
    /** scaling_max_freq in kHz, 0 wenn nicht lesbar. */
    val maxFreqKHz: Long
) {
    val coreCount: Int get() = cpus.size
    val ghz: Float get() = maxFreqKHz / 1_000_000f
}

data class CpuTopology(
    val clusters: List<CpuCluster>,
    val logicalCores: Int,
    /** true, wenn mindestens ein Cluster eine reale Taktrate liefern konnte. */
    val measured: Boolean
) {

    /** Schnellster Cluster nach gemessenem Maximaltakt. */
    /** Höchste gemessene Maximaltaktfrequenz in GHz (0, wenn nichts messbar war). */
    val maxFreqGhz: Float
        get() = (fastestCluster?.maxFreqKHz ?: 0L) / 1_000_000f

    val fastestCluster: CpuCluster?
        get() = clusters.filter { it.maxFreqKHz > 0 }.maxByOrNull { it.maxFreqKHz }

    /**
     * Kerne, auf denen Inferenz sinnvoll läuft: alle Cluster, deren Takt mindestens
     * 80 % des schnellsten Clusters erreicht — also Prime + Performance, ohne Effizienz-Kerne.
     */
    val performanceCoreCount: Int
        get() {
            val top = fastestCluster?.maxFreqKHz ?: 0L
            if (top <= 0) return 0
            val threshold = top * 8 / 10
            return clusters.filter { it.maxFreqKHz >= threshold }.sumOf { it.coreCount }
        }

    val efficiencyCoreCount: Int
        get() = (clusters.sumOf { it.coreCount } - performanceCoreCount).coerceAtLeast(0)

    fun describe(): String {
        if (clusters.isEmpty()) return "keine cpufreq-Informationen lesbar"
        val parts = clusters.sortedByDescending { it.maxFreqKHz }.map { c ->
            val freq = if (c.maxFreqKHz > 0) String.format(Locale.US, "%.2fGHz", c.ghz) else "Takt unbekannt"
            "${c.cpus.size}x $freq (cpu ${c.cpus.joinToString(",")})"
        }
        return "$logicalCores logische Kerne, ${clusters.size} Cluster: " + parts.joinToString(" | ")
    }
}

object CpuTopologyReader {

    private const val FREQ_REL_PATH = "cpufreq/scaling_max_freq"

    /**
     * @param sysCpuRoot normalerweise `/sys/devices/system/cpu`
     * @param availableProcessors Ersatzwert, wenn sysfs nichts hergibt
     */
    fun read(
        sysCpuRoot: File = File("/sys/devices/system/cpu"),
        availableProcessors: Int = Runtime.getRuntime().availableProcessors()
    ): CpuTopology {
        val clusterCpus = linkedMapOf<String, MutableList<Int>>()
        val clusterFreq = linkedMapOf<String, Long>()

        // 1) Bevorzugt: policy*/related_cpus + policy*/scaling_max_freq
        val policyDirs = sysCpuRoot.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }
            ?.filter { File(it, FREQ_REL_PATH).exists() || File(it, "related_cpus").exists() }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            .orEmpty()

        for (policy in policyDirs) {
            val related = readTrimmed(File(policy, "related_cpus"))
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }
                .orEmpty()
            if (related.isEmpty()) continue
            clusterCpus.getOrPut(policy.name) { mutableListOf() }.addAll(related)
            clusterFreq[policy.name] = readTrimmed(File(policy, FREQ_REL_PATH))?.toLongOrNull() ?: 0L
        }

        // 2) Fallback: einzelne cpus, wenn es keine Policies gibt (gepatchte Kernel, emulierte Geräte)
        if (clusterCpus.isEmpty()) {
            val cpuDirs = sysCpuRoot.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu[0-9]+")) }
                ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
                .orEmpty()
            for (cpu in cpuDirs) {
                val idx = cpu.name.removePrefix("cpu").toIntOrNull() ?: continue
                clusterCpus.getOrPut("cpu$idx") { mutableListOf() }.add(idx)
                clusterFreq["cpu$idx"] = readTrimmed(File(cpu, FREQ_REL_PATH))?.toLongOrNull() ?: 0L
            }
        }

        val clusters = clusterCpus.map { (name, cpus) ->
            CpuCluster(
                policy = name,
                cpus = cpus.distinct().sorted(),
                maxFreqKHz = clusterFreq[name] ?: 0L
            )
        }
        val totalCpus = clusters.sumOf { it.cpus.size }
        return CpuTopology(
            clusters = clusters,
            logicalCores = if (totalCpus > 0) totalCpus else availableProcessors,
            measured = clusters.any { it.maxFreqKHz > 0 }
        )
    }

    private fun readTrimmed(file: File): String? = try {
        if (!file.canRead()) null else file.readText().trim().ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    /**
     * Thread-Empfehlung für Inferenz: Zahl der Performance-Kerne, hart gedeckelt und
     * niemals über den logischen Kernen. 0 heißt "nichts ermittelbar" — der Aufrufer
     * soll dann auf seinen eigenen Default zurückfallen.
     */
    fun suggestedThreads(topology: CpuTopology, hardCap: Int = 8): Int {
        val perf = topology.performanceCoreCount
        val pool = if (perf > 0) perf else topology.logicalCores
        if (pool <= 0) return 0
        return pool.coerceIn(1, hardCap)
    }
}

/**
 * ISA-Erweiterungen, die der Kernel/CPU tatsächlich meldet — gelesen aus
 * `/proc/cpuinfo` (Zeile `Features:`).
 *
 * Warum nicht aus `Build.HARDWARE`/SoC-Namen abgeleitet? Weil genau dieses Raten der
 * alte Fehler war: die App hat "Armv9.2-A (SVE2, SME2)" angezeigt, obwohl die geladene
 * libllama.so weder SVE noch SME enthielt. Was hier steht, ist die Meldung des Systems;
 * ob die Engine davon Gebrauch machen *kann*, steht separat in der Backend-Diagnose
 * (`llama_print_system_info()`).
 */
data class CpuIsaCapabilities(
    /** Nüchterne Token-Liste aus der Features-Zeile, z. B. ["fp", "asimd", "asimddp", "i8mm"]. */
    val features: List<String>,
    /** Woher die Daten kommen, damit "leer" interpretierbar bleibt. */
    val source: String
) {
    val measured: Boolean get() = features.isNotEmpty()

    /** fp-asimd/NEON: Baseline für Vektorisierung in llama.cpp. */
    val hasNeon: Boolean get() = has("asimd") || has("neon")

    /** SDOT/UDOT — llama.cpp-Kernel "armv8.2 dotprod". */
    val hasDotProd: Boolean get() = has("asimddp") || has("dotprod")

    /** Matrix-Multiplikation auf 8-Bit — armv8.7/i8mm-Kernel. */
    val hasI8mm: Boolean get() = has("i8mm")

    val hasSve: Boolean get() = has("sve")
    val hasSve2: Boolean get() = has("sve2")
    val hasSme: Boolean get() = has("sme") || has("sme2")
    val hasFp16: Boolean get() = has("fphp") || has("asimdhp")

    private fun has(token: String) = features.any { it.equals(token, ignoreCase = true) }

    fun summary(): String {
        if (features.isEmpty()) return "ISA-Erweiterungen nicht lesbar ($source)"
        val marks = listOfNotNull(
            "neon".takeIf { hasNeon },
            "dotprod".takeIf { hasDotProd },
            "i8mm".takeIf { hasI8mm },
            "sve".takeIf { hasSve },
            "sve2".takeIf { hasSve2 },
            "sme".takeIf { hasSme },
            "fp16".takeIf { hasFp16 }
        )
        val head = if (marks.isEmpty()) "keine bekannten SIMD-Features gemeldet" else marks.joinToString("+")
        return "$head (${features.size} Features aus $source)"
    }
}

object CpuInfoReader {

    /**
     * Liest `/proc/cpuinfo`. Auf Android-64-Bit-Geräten stehen die SIMD-Flags in der
     * `Features:`-Zeile des(der) Logical-CPU-Blöcke; alle Vorkommen werden zusammengeführt.
     */
    fun read(cpuInfoFile: File = File("/proc/cpuinfo")): CpuIsaCapabilities {
        val features = LinkedHashSet<String>()
        val text = try {
            if (cpuInfoFile.canRead()) cpuInfoFile.readText() else null
        } catch (_: Throwable) {
            null
        } ?: return CpuIsaCapabilities(emptyList(), "${cpuInfoFile.path} nicht lesbar")

        for (raw in text.lineSequence()) {
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val key = raw.substring(0, colon).trim()
            if (!key.equals("Features", ignoreCase = true) && !key.equals("feature", ignoreCase = true)) continue
            raw.substring(colon + 1).trim().split(Regex("[\\s,]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { features.add(it) }
        }
        return CpuIsaCapabilities(features.toList(), cpuInfoFile.path)
    }
}
