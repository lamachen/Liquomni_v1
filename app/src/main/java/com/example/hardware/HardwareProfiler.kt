package com.example.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

/**
 * Geräteprofil.
 *
 * Grundsatz: alles, was hier steht, ist entweder direkt aus einem Android-Systemwert
 * oder — wo möglich — aus einer echten Messung (CPU-Taktfrequenzen und ISA-Erweiterungen
 * aus /sys, GPU-Renderer über Vulkan, Shader-Precisions).
 *
 * Frühere Versionen dieses Profilers enthielten eine Tabelle ausgedachter Werte
 * ("geschätzte Tokens/s", "Speicherbandbreite in GB/s", SIMD-Architektur pro SoC-Marke).
 * Diese Zahlen waren nicht gemessen, sondern aus Modellnamen abgeleitet — genau das
 * Verhalten, das die Backend-Diagnose unbrauchbar gemacht hat. Sie sind entfernt:
 * nicht messbare Felder liefern jetzt 0 bzw. einen expliziten "nicht gemessen"-Text.
 */
data class DeviceHardwareInfo(
  val deviceModel: String,
  val androidVersion: String,
  val abiList: String,
  val isArm64: Boolean,
  val totalRamGb: Float,
  val freeRamGb: Float,
  val isLowRam: Boolean,
  val cpuCores: Int,
  val gpuVendor: String,
  val gpuRenderer: String,
  val glEsVersion: String,
  val availableStorageGb: Float,
  val canRun2BModel: Boolean,
  val socFamily: String = "ARM64 SoC",
  /** 0 = nicht gemessen; gemessene Vorschlag kommt aus der CPU-Topologie. */
  val recommendedThreads: Int = 0,
  /** Gemessene CPU-Realität aus /proc/cpuinfo + /sys, kein Marketing-Text. */
  val simdArchitecture: String = "nicht gemessen",
  /** 0 bedeutet bewusst "nicht gemessen" — UI muss das als solches anzeigen. */
  val estimatedMemoryBandwidthGbps: Float = 0f,
  val estimatedTokensPerSec: Float = 0f,
  /** Echte CPU-Topologie für Diagnose-Zwecke. */
  val cpuTopology: CpuTopology? = null
) {
  /** UI-taugliche Darstellung der nicht gemessenen Felder. */
  fun performanceEstimateText(): String =
    if (estimatedTokensPerSec > 0f) "ca. %.1f tok/s".format(estimatedTokensPerSec) else "tok/s: nicht gemessen"
}

object HardwareProfiler {

  fun getHardwareInfo(context: Context): DeviceHardwareInfo {
    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    actManager.getMemoryInfo(memInfo)

    val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0).toFloat()
    val cores = Runtime.getRuntime().availableProcessors()
    val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }

    val stat = StatFs(Environment.getDataDirectory().path)
    val availableStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0).toFloat()

    val configInfo = actManager.deviceConfigurationInfo
    val glEsVersion = configInfo?.glEsVersion ?: "unbekannt"

    // --- Echte CPU-Topologie messen (sysfs), statt SoC-Namen zu raten ---
    val topology = CpuTopologyReader.read(availableProcessors = cores)
    val isa = CpuInfoReader.read()
    val clusterText = if (topology.clusters.isEmpty()) {
      "Cluster nicht messbar (/sys cpufreq nicht lesbar)"
    } else {
      "%d schnelle von %d Kernen, max %.2f GHz".format(
        topology.performanceCoreCount,
        topology.logicalCores,
        topology.maxFreqGhz
      )
    }
    val simdDescription = "${isa.summary()} — $clusterText"

    // --- GPU: misst nur, was wirklich messbar ist (Treiber + Vulkan-API-Version) ---
    // Den exakten Renderer-Namen kennt nur die Engine selbst; er steht in der
    // Geräte-Tabelle der Backend-Diagnose, nachdem libllama.so gebunden ist.
    val vulkan = VulkanDriverProbe.read()
    val gpuRenderer = if (vulkan.driverPresent) {
      "Vulkan ${vulkan.apiVersion ?: "Version unbekannt"} — Renderer-Name siehe Backend-Diagnose"
    } else {
      "kein Vulkan-Treiber${vulkan.detail?.let { " (${it.take(60)})" } ?: ""}"
    }
    val gpuVendor = if (vulkan.driverPresent) "Vulkan-Gerät (Hersteller nicht sicher bestimmbar)" else "unbekannt"

    // LFM3-Omni-2B in Q4_K_M braucht grob 1,4-2 GB Gewichte plus Kontext. Das ist eine
    // Rückfrage an den Nutzer, keine Messung: wir prüfen nur, ob der Speicherplatz
    // offenbar reicht.
    val canRun2BModel = totalRamGb >= 3.5f && isArm64

    val recommendedThreads = CpuTopologyReader.suggestedThreads(topology)

    return DeviceHardwareInfo(
      deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
      androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
      abiList = Build.SUPPORTED_ABIS.joinToString(", "),
      isArm64 = isArm64,
      totalRamGb = totalRamGb,
      freeRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0).toFloat(),
      isLowRam = memInfo.lowMemory,
      cpuCores = cores,
      gpuVendor = gpuVendor,
      gpuRenderer = gpuRenderer,
      glEsVersion = glEsVersion,
      availableStorageGb = availableStorageGb,
      canRun2BModel = canRun2BModel,
      socFamily = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim().ifBlank {
        // Fallback auf Gerätebezeichnung, wenn der SoC nicht separat ausgewiesen ist
        "${Build.MANUFACTURER} ${Build.BOARD}"
      },
      recommendedThreads = recommendedThreads,
      simdArchitecture = simdDescription,
      estimatedMemoryBandwidthGbps = 0f,
      estimatedTokensPerSec = 0f,
      cpuTopology = topology
    )
  }
}
