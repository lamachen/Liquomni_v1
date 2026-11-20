package com.example.engine

import android.content.Context
import java.io.File

/**
 * Schutz gegen Treiber-Abstürze beim ersten GPU-Ladeversuch.
 *
 * Ein Segfault im Vulkan-Treiber lässt sich in der JVM nicht abfangen. Deshalb wird vor
 * dem Laden eine Marker-Datei geschrieben und nach erfolgreichem Laden gelöscht. Steht die
 * Datei beim nächsten Start noch da, ist der letzte GPU-Versuch abgestürzt -> CPU erzwingen,
 * bis der Nutzer die Sperre zurücksetzt ([reset], Button "GPU erneut versuchen").
 */
object GpuCrashGuard {
    private fun marker(c: Context) = File(c.filesDir, "gpu_load.pending")

    fun crashedLastTime(c: Context): Boolean = marker(c).exists()

    fun arm(c: Context) {
        runCatching { marker(c).writeText(System.currentTimeMillis().toString()) }
    }

    fun disarm(c: Context) {
        runCatching { marker(c).delete() }
    }

    fun reset(c: Context) = disarm(c)

    /** Ersetzt eine GPU-Entscheidung durch CPU, falls der letzte GPU-Ladeversuch abstürzte. */
    fun applyTo(c: Context, decision: GpuLayerDecision): GpuLayerDecision =
        if (decision.usesGpu && crashedLastTime(c)) {
            GpuLayerDecision(
                nGpuLayers = 0,
                label = "CPU (GPU-Absturz erkannt)",
                reason = "Der letzte GPU-Ladeversuch hat die App beendet. In den Einstellungen zurücksetzen.",
                warned = true
            )
        } else {
            decision
        }
}
