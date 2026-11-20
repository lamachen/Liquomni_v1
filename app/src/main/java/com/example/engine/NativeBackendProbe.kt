package com.example.engine

import android.util.Log
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Fragt die native Engine ab, welche Rechenknoten WIRKLICH existieren.
 *
 * Ersetzt die bisherige, unbelegte Annahme "auf echten Android-Geräten mit
 * Vulkan-Unterstützung immer 99 Schichten auf die GPU". Richtig ist: llama.cpp
 * lagert nur auf Geräte aus, die ein registriertes ggml-Backend gemeldet hat. Ist
 * keins gebaut (unsere committete libllama.so ist CPU-only), wird n_gpu_layers
 * kommentarlos ignoriert — und die App läuft auf der CPU, ohne es zu sagen.
 *
 * Aufgebaut wie eine Checkliste, jede Stufe einzeln abgesichert:
 *  1. JNA-Bindung an libllama.so
 *  2. llama_backend_init()            (ggml-Zeitbasis, f16-Tabellen)
 *  3. ggml_backend_load_all_from_path() (optional: Backend-Module aus dem Lib-Verzeichnis)
 *  4. Geräte-Tabelle                  (ggml_backend_dev_*)
 *  5. llama_supports_gpu_offload()
 *  6. llama_print_system_info()       (welche CPU-Extensions einkompiliert sind)
 *  7. Treiber-Präsenz Vulkan/OpenCL   (reiner dlopen-Test, keine Funktionsaufrufe)
 */
object NativeBackendProbe {

    private const val TAG = "NativeBackendProbe"

    /** Einmal berechnete Ergebnisse; die Frage ändert sich pro Prozess nicht. */
    @Volatile
    private var cached: EngineCapabilities? = null

    fun probe(nativeLibDir: String?, forceReread: Boolean = false, onLog: ((String) -> Unit)? = null): EngineCapabilities {
        if (!forceReread) cached?.let { return it }

        val lib = LlamaNativeBridge.ensureLoaded(onLog = onLog)
        if (lib == null) {
            val caps = EngineCapabilities(
                devices = emptyList(),
                gpuOffloadCompiledIn = false,
                systemInfo = "",
                vulkanDriverPresent = false,
                openclDriverPresent = false,
                devicesGainedFromBackendScan = 0,
                engineBound = false
            )
            cached = caps
            onLog?.invoke("BACKEND: Engine nicht gebunden — ${caps.diagnosis}")
            return caps
        }

        runCatching { lib.llama_backend_init() }
            .onFailure { Log.w(TAG, "llama_backend_init: ${it.message}") }

        val countBefore = runCatching { lib.ggml_backend_dev_count() }.getOrDefault(0L).coerceIn(0L, 64L)

        // Backend-Module (libggml-vulkan.so, libggml-opencl.so, ...) liegen im APK neben
        // libllama.so. llama.cpp findet sie normalerweise automatisch; der explizite Aufruf
        // macht es unabhängig davon, aus welchem Verzeichnis heraus der Prozess startet.
        if (!nativeLibDir.isNullOrBlank()) {
            runCatching { lib.ggml_backend_load_all_from_path(nativeLibDir) }
                .onFailure { Log.w(TAG, "ggml_backend_load_all_from_path($nativeLibDir): ${it.message}") }
        }
        val devices = enumerateDevices(lib)
        val gained = (devices.size - countBefore.toInt()).coerceAtLeast(0)

        val gpuCompiledIn = runCatching { lib.llama_supports_gpu_offload() }.getOrDefault(false)
        val systemInfo = runCatching { lib.llama_print_system_info().orEmpty() }.getOrDefault("")

        val caps = EngineCapabilities(
            devices = devices,
            gpuOffloadCompiledIn = gpuCompiledIn,
            systemInfo = systemInfo,
            vulkanDriverPresent = canDlopen("vulkan"),
            openclDriverPresent = canDlopen("OpenCL"),
            devicesGainedFromBackendScan = gained,
            engineBound = true
        )
        cached = caps

        onLog?.invoke("BACKEND: Geräte=[${caps.deviceSummary()}] | gpu_offload_einkompiliert=${if (gpuCompiledIn) "ja" else "nein"}")
        onLog?.invoke("BACKEND: Treiber: ${caps.driverList()} | $gained Gerät(e) aus Backend-Modul-Scan")
        if (systemInfo.isNotBlank()) {
            onLog?.invoke("BACKEND: CPU-Features der Engine: ${systemInfo.trim()}")
        }
        onLog?.invoke("BACKEND: Diagnose — ${caps.diagnosis}")
        return caps
    }

    /** Erzwingt eine Neue Messung (nach dem Austausch von .so-Dateien oder in der Diagnose-Ansicht). */
    fun invalidate() {
        cached = null
    }

    private fun enumerateDevices(lib: LlamaNativeLib): List<GgmlDeviceInfo> {
        val count = runCatching { lib.ggml_backend_dev_count() }.getOrDefault(0L).coerceIn(0L, 64L)
        val out = ArrayList<GgmlDeviceInfo>(count.toInt())
        for (i in 0L until count) {
            val dev = runCatching { lib.ggml_backend_dev_get(i) }.getOrNull() ?: continue
            if (!LlamaNativeBridge.isValidPointerAddress(Pointer.nativeValue(dev))) continue
            val name = runCatching { lib.ggml_backend_dev_name(dev).orEmpty() }.getOrDefault("")
                .ifBlank { "device#$i" }
            val desc = runCatching { lib.ggml_backend_dev_description(dev).orEmpty() }.getOrDefault("")
            val type = runCatching { lib.ggml_backend_dev_type(dev) }.getOrDefault(-1)
            out.add(GgmlDeviceInfo(index = i.toInt(), name = name, description = desc, typeCode = type))
        }
        return out
    }

    /**
     * Reiner dlopen-Test. Es werden ABSOLUT KEINE Treiberfunktionen aufgerufen,
     * damit ein fehlerhafter GPU-Treiber die App nicht abschießen kann.
     *
     * Hinweis: libOpenCL.so ist nicht Teil des öffentlichen NDK-Scope — ein "false"
     * bedeutet hier also "aus der App heraus nicht ladbar", nicht "kein OpenCL am Gerät".
     */
    private fun canDlopen(name: String): Boolean = try {
        NativeLibrary.getInstance(name) != null
    } catch (_: Throwable) {
        false
    }
}
