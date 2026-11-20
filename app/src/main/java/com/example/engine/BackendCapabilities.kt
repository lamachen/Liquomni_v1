package com.example.engine

/**
 * Reines Modell für "welches Rechen-Backend hat die nativ geladene Engine wirklich?"
 *
 * Hier bewusst ohne Android-APIs und ohne JNA: diese Datei ist die einzige Stelle,
 * an der Entscheidungen über CPU/GPU-Auslagerung getroffen werden, und sie ist damit
 * auf der JVM testbar (`app/src/test/java/com/example/BackendPolicyTest.kt`).
 *
 * Hintergrund: die Engine war jahrelang "still" CPU-only, weil `n_gpu_layers = 99`
 * hart eingetragen wurde, ohne dass ein GPU-Backend existierte. llama.cpp ignoriert
 * diese Zahl dann kommentarlos. Deshalb gilt jetzt:
 *  - erst Gerätetabelle ermitteln ([EngineCapabilities]),
 *  - dann aus Modus + Fähigkeit die Zahl ableiten ([BackendPolicy.resolve]),
 *  - und die Begründung immer sichtbar machen ([GpuLayerDecision.reason]).
 */

/** Nutzerwunsch aus den Einstellungen. */
enum class ComputeBackendMode {
    /** Automatisch: GPU falls von der Engine angeboten, sonst CPU. */
    AUTO,

    /** GPU erzwingen und mit Fehler melden, wenn keine da (statt leise CPU). */
    GPU,

    /** Bewusst rein CPU — z. B. um Vulkan-Instabilität auf einem Gerät zu umgehen. */
    CPU
}

/** Ein Eintrag aus `ggml_backend_dev_get(i)`. */
data class GgmlDeviceInfo(
    val index: Int,
    val name: String,
    val description: String,
    /** enum ggml_backend_dev_type: 0=CPU, 1=GPU, 2=iGPU, 3=ACCEL, 4=META */
    val typeCode: Int
) {
    val typeLabel: String
        get() = when (typeCode) {
            0 -> "CPU"
            1 -> "GPU"
            2 -> "iGPU"
            3 -> "ACCEL"
            4 -> "META"
            else -> "TYPE_$typeCode"
        }

    /** Alles außer CPU und META kann Schichten übernehmen. */
    val isAccelerator: Boolean get() = typeCode == 1 || typeCode == 2 || typeCode == 3
}

/** Was die geladene Native-Engine an Rechenknoten tatsächlich zur Verfügung hat. */
data class EngineCapabilities(
    val devices: List<GgmlDeviceInfo>,
    /** Ergebnis von `llama_supports_gpu_offload()` — nur true, wenn GPU-Code einkompiliert ist. */
    val gpuOffloadCompiledIn: Boolean,
    /** `llama_print_system_info()`: echte, einkompilierte CPU-Features (NEON/DOTPROD/I8MM/SVE…). */
    val systemInfo: String,
    /** libvulkan.so im Prozess verfügbar (nur dlopen-Test, keine Inferenz-Funktion). */
    val vulkanDriverPresent: Boolean,
    /** libOpenCL.so im Prozess verfügbar. */
    val openclDriverPresent: Boolean,
    /** Wie viele Geräte zusätzlich erschienen, nachdem `ggml_backend_load_all_from_path`
     *  im Lib-Verzeichnis der App nach libggml-*.so-Backends gesucht hat. */
    val devicesGainedFromBackendScan: Int,
    /** true, wenn der JNA-Zugang zur Engine überhaupt steht. */
    val engineBound: Boolean
) {
    val acceleratorDevices: List<GgmlDeviceInfo>
        get() = devices.filter { it.isAccelerator }

    val cpuDevices: List<GgmlDeviceInfo>
        get() = devices.filter { it.typeCode == 0 }

    val hasGpuDevice: Boolean get() = acceleratorDevices.isNotEmpty()

    /**
     * Warum ein GPU-Gerät trotz vorhandenen Treibers fehlen kann — genau die Antwort,
     * die in der App bisher gefehlt hat.
     */
    val diagnosis: String
        get() = when {
            !engineBound ->
                "Engine nicht gebunden: libllama.so/JNA nicht verfügbar."
            hasGpuDevice ->
                "Beschleuniger erkannt: ${acceleratorDevices.joinToString { "${it.name} (${it.typeLabel})" }} — GPU-Auslagerung ist möglich."
            gpuOffloadCompiledIn ->
                "llama.cpp meldet GPU-Support, aber es wurde kein Gerät registriert. " +
                    "Typische Ursachen: Treiber blockiert die Backend-Bibliothek, " +
                    "oder das Backend-Modul (libggml-vulkan.so) liegt nicht im App-Lib-Verzeichnis."
            !vulkanDriverPresent && !openclDriverPresent ->
                "Die Engine ist ohne GPU-Backend gebaut UND das Gerät meldet weder Vulkan- noch OpenCL-Treiber. " +
                    "Diese libllama.so ist CPU-only (see native/native_audit.py)."
            else ->
                "Gerät hat Treiber (${driverList()}), aber die geladene libllama.so enthält kein GPU-Backend. " +
                    "Abhilfe: native/build_engine.sh --backend all"
        }

    fun driverList(): String = listOfNotNull(
        "Vulkan".takeIf { vulkanDriverPresent },
        "OpenCL".takeIf { openclDriverPresent }
    ).joinToString().ifBlank { "keine GPU-Treiber geladen" }

    fun deviceSummary(): String =
        if (devices.isEmpty()) "keine GGML-Geräte registriert"
        else devices.joinToString { "${it.name} [${it.typeLabel}]" }
}

/** Ergebnis der Backend-Entscheidung inkl. Begründung für UI und Log. */
data class GpuLayerDecision(
    val nGpuLayers: Int,
    val label: String,
    val reason: String,
    val warned: Boolean
) {
    val usesGpu: Boolean get() = nGpuLayers > 0
}

object BackendPolicy {

    /** llama.cpp-Konvention: >= 99 heißt "alle Schichten". */
    const val OFFLOAD_ALL = 99

    /** Obergrenze für die Nutzer-Einstellung (Slider). */
    const val MAX_USER_LAYERS = 99

    /**
     * Leitet `n_gpu_layers` aus Modus + Fähigkeit ab.
     *
     * @param userLayers vom Nutzer gesetzter Wunsch (nur relevant bei [ComputeBackendMode.GPU])
     */
    fun resolve(
        mode: ComputeBackendMode,
        caps: EngineCapabilities?,
        userLayers: Int = OFFLOAD_ALL
    ): GpuLayerDecision {
        val requested = userLayers.coerceIn(0, MAX_USER_LAYERS)
        val gpuAvailable = caps?.hasGpuDevice == true

        return when (mode) {
            ComputeBackendMode.CPU -> GpuLayerDecision(
                nGpuLayers = 0,
                label = "CPU",
                reason = "Nutzer hat CPU-Modus gewählt (GPU-Offload bewusst deaktiviert).",
                warned = false
            )

            ComputeBackendMode.AUTO -> when {
                caps == null -> GpuLayerDecision(
                    nGpuLayers = 0,
                    label = "CPU (Auto, ohne Kenntnis)",
                    reason = "Backend-Erkennung nicht verfügbar — es wird sicherheitshalber auf der CPU gerechnet.",
                    warned = true
                )
                gpuAvailable -> GpuLayerDecision(
                    nGpuLayers = requested,
                    label = "AUTO → ${caps.acceleratorDevices.first().name}",
                    reason = "Auto: Beschleuniger ${caps.acceleratorDevices.first().name} " +
                        "(${caps.acceleratorDevices.first().typeLabel}) verfügbar — $requested Schichten werden ausgelagert" +
                        (if (requested >= OFFLOAD_ALL) " (alle)" else "") + ".",
                    warned = false
                )
                else -> GpuLayerDecision(
                    nGpuLayers = 0,
                    label = "AUTO → CPU",
                    reason = "Auto: Engine meldet ${caps.deviceSummary()}. ${caps.diagnosis}",
                    warned = false
                )
            }

            ComputeBackendMode.GPU -> if (gpuAvailable) {
                GpuLayerDecision(
                    // Der Nutzerwert gilt exakt — auch 0. "GPU erzwingen" mit 0 Schichten
                    // ist eine bewusste Entscheidung (z. B. Vulkan nur für den Audio-Zweig).
                    nGpuLayers = requested,
                    label = "GPU (${caps?.acceleratorDevices?.firstOrNull()?.name ?: "GPU"})",
                    reason = "GPU gewünscht: $requested Schichten auf " +
                        (caps?.acceleratorDevices?.firstOrNull()?.name ?: "unbekanntes Gerät"),
                    warned = false
                )
            } else {
                // Bewusst KEIN stiller Rückfall mehr: der Nutzer soll den Grund sehen.
                GpuLayerDecision(
                    nGpuLayers = 0,
                    label = "GPU erzwungen → NICHT MÖGLICH",
                    reason = "GPU-Modus gewählt, aber es ist kein Beschleuniger registriert. " + caps?.diagnosis.orEmpty(),
                    warned = true
                )
            }
        }
    }

    /**
     * Thread-Zahl-Policy. Trennt "wie viele Kerne hat das Gerät" (Hardware) von
     * "wie viele soll llama.cpp benutzen" (Entscheidung) und hält beides fest.
     */
    fun resolveThreads(
        userThreads: Int,
        availableProcessors: Int,
        bigCoreCount: Int?
    ): ThreadDecision {
        val physicalCeiling = (bigCoreCount ?: availableProcessors).coerceAtLeast(1)
        val threads = if (userThreads > 0) {
            userThreads.coerceIn(1, physicalCeiling.coerceAtMost(16))
        } else {
            physicalCeiling
        }
        return ThreadDecision(
            threads = threads,
            reason = if (userThreads > 0) {
                val extra = if (threads < userThreads) {
                    " — begrenzt auf $threads, denn mehr Threads als schnelle Kerne machen die Inferenz nicht schneller"
                } else ""
                "Nutzerwert $userThreads (Gerät meldet ${availableProcessors} logische, " +
                    "${bigCoreCount?.let { "$it schnelle" } ?: "keine separate"} Kerne)$extra."
            } else {
                "Auto: $physicalCeiling Kerne laut Messung/Kernel."
            },
            clampedToPhysical = userThreads > 0 && threads < userThreads
        )
    }

    /** Kurzer, ehrlicher Report für das Diagnose-Panel. */
    fun describe(caps: EngineCapabilities?, decision: GpuLayerDecision, threadDecision: ThreadDecision?): String =
        buildString {
            appendLine("Backend-Entscheidung: ${decision.label} (n_gpu_layers=${decision.nGpuLayers})")
            appendLine("Begründung: ${decision.reason}")
            if (threadDecision != null) {
                appendLine("Threads: ${threadDecision.threads} — ${threadDecision.reason}")
            }
            appendLine("GGML-Geräte: ${caps?.deviceSummary() ?: "Engine nicht gebunden"}")
            appendLine("GPU-Offload einkompiliert: ${caps?.gpuOffloadCompiledIn?.let { if (it) "ja" else "nein" } ?: "unbekannt"}")
            appendLine("GPU-Treiber am Gerät: ${caps?.driverList() ?: "unbekannt"}")
            if (!caps?.systemInfo.isNullOrBlank()) {
                appendLine("Engine-CPU-Features: ${caps!!.systemInfo.trim()}")
            }
        }.trimEnd()
}

data class ThreadDecision(
    val threads: Int,
    val reason: String,
    val clampedToPhysical: Boolean
)
