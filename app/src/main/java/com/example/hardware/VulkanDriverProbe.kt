package com.example.hardware

import android.util.Log
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference

/**
 * Prüft, ob am Gerät ein Vulkan-Treiber aus der App heraus erreichbar ist — und wenn ja,
 * welche Vulkan-API-Version er meldet.
 *
 * Bewusst nur `vkEnumerateInstanceVersion`: das ist der einzige Vulkan-Aufruf, der ohne
 * Instanz-Strukturen und ohne Geräte-Enumeration auskommt. Eine echte Geräte-Enumeration
 * (Renderer-Name, VRAM) über JNA wäre Handmarshalling von C-Strukturen und damit
 * wartungsgefährlich; den maßgeblichen GPU-Namen liefert stattdessen die Engine selbst
 * über `ggml_backend_dev_*` (siehe com.example.engine.NativeBackendProbe).
 */
data class VulkanDriverInfo(
    val driverPresent: Boolean,
    /** z. B. "1.3.276"; null wenn nicht ermittelbar. */
    val apiVersion: String?,
    /** Was genau fehlgeschlagen ist — für Diagnose, damit "unbekannt" nicht raten heißt. */
    val detail: String?
) {
    /** Kurzlabel für Einstellungs- und Diagnosezeilen. */
    fun summary(): String = when {
        driverPresent && apiVersion != null -> "Vulkan-Treiber $apiVersion"
        driverPresent -> "Vulkan-Treiber vorhanden (Version nicht lesbar)"
        else -> "kein Vulkan-Treiber erreichbar${detail?.let { " ($it)" } ?: ""}"
    }

    companion object {
        private const val TAG = "VulkanDriverProbe"

        @Volatile
        private var cached: VulkanDriverInfo? = null

        fun read(forceReread: Boolean = false): VulkanDriverInfo {
            cached?.let { if (!forceReread) return it }
            val info = measure()
            cached = info
            return info
        }

        private fun measure(): VulkanDriverInfo {
            val library = try {
                NativeLibrary.getInstance("vulkan")
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "libvulkan.so nicht ladbar: $msg")
                return VulkanDriverInfo(driverPresent = false, apiVersion = null, detail = msg)
            }

            return try {
                val function: Function = library.getFunction("vkEnumerateInstanceVersion")
                val out = IntByReference(0)
                val result = function.invokeInt(arrayOf(out))
                if (result != 0) {
                    VulkanDriverInfo(
                        driverPresent = true,
                        apiVersion = null,
                        detail = "vkEnumerateInstanceVersion lieferte VkResult=$result"
                    )
                } else {
                    val raw = out.value
                    val major = (raw shr 22) and 0x3FF
                    val minor = (raw shr 12) and 0x3FF
                    val patch = raw and 0xFFF
                    VulkanDriverInfo(
                        driverPresent = true,
                        apiVersion = "$major.$minor.$patch",
                        detail = null
                    )
                }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "vkEnumerateInstanceVersion nicht aufrufbar: $msg")
                VulkanDriverInfo(driverPresent = true, apiVersion = null, detail = msg)
            }
        }
    }
}
