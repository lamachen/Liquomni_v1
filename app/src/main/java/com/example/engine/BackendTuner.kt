package com.example.engine

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Entscheidet im Modus AUTO nach Messung statt nach "GPU vorhanden".
 *
 * Hintergrund: Auf PowerVR (Pixel 10) kann Vulkan beim Decode langsamer sein als die CPU mit
 * i8mm. Deshalb misst die App pro Gerät und Engine-Build, wie lange ein Turn tatsächlich
 * dauert, getrennt für GPU und CPU und getrennt nach Modus (Text-Streaming vs. 24-kHz-Audio):
 *
 *  - Audio: Kosten = RTF (Rechenzeit / erzeugte Audiodauer), kleiner ist besser.
 *  - Text:  Kosten = ms pro Token, kleiner ist besser.
 *
 * Regel (wie in Phase 0 der Planung): GPU wird Default, wenn ihre Kosten höchstens
 * ~11 % über denen der CPU liegen (entspricht tg(GPU) >= 0.9 * tg(CPU)); die GPU entlastet
 * dabei die CPU/den Akku. Sonst bleibt die CPU Default.
 *
 * Ablauf ohne Daten: erster Start GPU, nächster Start CPU, damit beide Seiten gemessen
 * sind. Der Schlüssel enthält SoC und App-Update-Zeit, ein neuer Engine-Build startet also
 * automatisch neu mit der Messung.
 */
object BackendTuner {
    private const val TAG = "BackendTuner"
    private const val PREFS = "backend_tuner"

    /** GPU darf bis zu diesem Faktor teurer sein als CPU und trotzdem gewinnen (1 / 0.9). */
    private const val GPU_COST_TOLERANCE = 1.11f

    /** Gewicht des neuen Messwerts im gleitenden Mittel. */
    private const val EMA_ALPHA = 0.5f

    fun modeKey(omniOutputMode: String): String =
        if (omniOutputMode.equals("SYSTEM_TTS", ignoreCase = true) ||
            omniOutputMode.equals("TEXT", ignoreCase = true)
        ) "text" else "audio"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildKey(c: Context): String {
        val updated = try {
            c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        return "${Build.SOC_MODEL.ifBlank { Build.HARDWARE }}|$updated"
    }

    private fun k(c: Context, mode: String, gpu: Boolean, field: String) =
        "${buildKey(c)}|$mode|${if (gpu) "gpu" else "cpu"}|$field"

    /** Trägt einen gemessenen Turn ein (kleiner = besser). */
    fun record(c: Context, gpu: Boolean, mode: String, cost: Float, onLog: ((String) -> Unit)? = null) {
        if (!cost.isFinite() || cost <= 0f) return
        try {
            val sp = prefs(c)
            val old = sp.getFloat(k(c, mode, gpu, "cost"), -1f)
            val n = sp.getInt(k(c, mode, gpu, "n"), 0)
            val merged = if (old < 0f) cost else old * (1f - EMA_ALPHA) + cost * EMA_ALPHA
            sp.edit()
                .putFloat(k(c, mode, gpu, "cost"), merged)
                .putInt(k(c, mode, gpu, "n"), n + 1)
                .apply()
            onLog?.invoke("TUNER: ${if (gpu) "GPU" else "CPU"}/$mode Messung ${"%.3f".format(cost)} -> Mittel ${"%.3f".format(merged)} (n=${n + 1})")
        } catch (e: Throwable) {
            Log.w(TAG, "record fehlgeschlagen: ${e.message}")
        }
    }

    private fun cost(c: Context, mode: String, gpu: Boolean): Float? =
        prefs(c).getFloat(k(c, mode, gpu, "cost"), -1f).takeIf { it > 0f }

    /**
     * true = GPU, false = CPU. Fehlt eine Seite, wird sie als Nächstes gemessen:
     * nichts gemessen -> GPU; nur GPU gemessen -> CPU; nur CPU gemessen -> GPU.
     */
    fun preferGpu(c: Context, mode: String): Boolean {
        val g = cost(c, mode, true)
        val cpu = cost(c, mode, false)
        return when {
            g == null && cpu == null -> true
            g != null && cpu == null -> false
            g == null && cpu != null -> true
            else -> g!! <= cpu!! * GPU_COST_TOLERANCE
        }
    }

    /** Lesbare Zusammenfassung für Diagnose-Log. */
    fun summary(c: Context, mode: String): String {
        val g = cost(c, mode, true)
        val cpu = cost(c, mode, false)
        fun f(v: Float?) = v?.let { "%.3f".format(it) } ?: "—"
        return "TUNER[$mode]: GPU=${f(g)} | CPU=${f(cpu)} (kleiner = besser) -> ${if (preferGpu(c, mode)) "GPU" else "CPU"}"
    }

    /**
     * Wendet die Messung auf eine AUTO-Entscheidung an. Nutzerwahl (CPU / GPU) bleibt
     * unangetastet, und ohne verfügbare GPU gibt es nichts zu entscheiden.
     */
    fun applyTo(
        c: Context,
        mode: ComputeBackendMode,
        tuningMode: String,
        decision: GpuLayerDecision,
        onLog: ((String) -> Unit)? = null
    ): GpuLayerDecision {
        if (mode != ComputeBackendMode.AUTO || !decision.usesGpu) return decision
        onLog?.invoke(summary(c, tuningMode))
        if (preferGpu(c, tuningMode)) return decision
        val g = cost(c, tuningMode, true)
        val cpu = cost(c, tuningMode, false)
        return GpuLayerDecision(
            nGpuLayers = 0,
            label = "AUTO → CPU (gemessen)",
            reason = if (g != null && cpu != null) {
                "Auto: Messung zeigt CPU schneller/gleichwertig (GPU ${"%.3f".format(g)} vs CPU ${"%.3f".format(cpu)}, kleiner = besser)."
            } else {
                "Auto: CPU-Referenzmessung fehlt noch — dieser Start läuft auf der CPU, damit beide Seiten Daten haben."
            },
            warned = false
        )
    }

    fun reset(c: Context) {
        prefs(c).edit().clear().apply()
    }
}
