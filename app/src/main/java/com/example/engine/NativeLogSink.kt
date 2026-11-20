package com.example.engine

import android.util.Log
import com.sun.jna.Callback
import com.sun.jna.Pointer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JNA-Callback-Brücke für
 * `typedef void (*ggml_log_callback)(enum ggml_log_level level, const char * text, void * user_data)`.
 */
/** Zeitstempel-Basis für die Log-Zeilen; SimpleDateFormat ist nicht thread-safe,
 *  alle format()-Aufrufe synchronisieren deshalb darauf. */
private val logTimestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

internal interface GgmlLogCallback : Callback {
    fun callback(level: Int, text: String?, userData: Pointer?)
}

/**
 * Fängt die native Log-Ausgabe von llama.cpp/ggml ab (`llama_log_set`).
 *
 * Warum: die eigentliche Fehlerursache beim Modell-Load stand bisher nur im C++-Log
 * (adb logcat), in der App kam aber nur "Interner Fehler" an. Genau das hat die
 * Fehlersuche unmöglich gemacht. Dieses Sink hält die letzten Zeilen im RAM, damit
 * Diagnose-Panel und Exception-Meldungen den echten Text zeigen können.
 *
 * Thread-Safety: ggml ruft den Callback aus beliebigen Threads (auch aus ggml-Workern),
 * alle Zugriffe passieren daher synchronisiert auf dem Puffer.
 */
object NativeLogSink {

    private const val TAG = "NativeLogSink"

    /** Werte von `enum ggml_log_level`. */
    private const val LEVEL_DEBUG = 1
    private const val LEVEL_INFO = 2
    private const val LEVEL_WARN = 3
    private const val LEVEL_ERROR = 4

    private const val MAX_LINES = 500
    private const val MAX_LINE_CHARS = 2000

    private val callbackRef = object : GgmlLogCallback {
        override fun callback(level: Int, text: String?, userData: Pointer?) {
            if (!text.isNullOrBlank()) append(level, text.trimEnd())
        }
    }

    private val lock = Any()
    private val ring = ArrayDeque<Line>()

    @Volatile
    private var installed = false

    data class Line(val level: Int, val levelLabel: String, val timeMs: Long, val text: String) {
        fun format(): String {
            // SimpleDateFormat ist nicht thread-safe; format() kann von UI- und
            // Coroutine-Threads gleichzeitig aufrufbar sein (snapshot/tail).
            val stamp = synchronized(logTimestampFormat) { logTimestampFormat.format(Date(timeMs)) }
            return "$stamp [$levelLabel] $text"
        }
    }

    private fun levelLabel(level: Int): String = when (level) {
        LEVEL_ERROR -> "ERROR"
        LEVEL_WARN -> "WARN"
        LEVEL_INFO -> "INFO"
        LEVEL_DEBUG -> "DEBUG"
        else -> "LOG"
    }

    private fun append(level: Int, text: String) {
        val clipped = if (text.length > MAX_LINE_CHARS) text.substring(0, MAX_LINE_CHARS) + " ..." else text
        synchronized(lock) {
            ring.addLast(Line(level, levelLabel(level), System.currentTimeMillis(), clipped))
            while (ring.size > MAX_LINES) ring.removeFirst()
        }
    }

    /**
     * Installiert den Handler einmal pro Prozess. Idempotent.
     * @return true, wenn der Handler jetzt aktiv ist.
     */
    fun install(): Boolean {
        if (installed) return true
        val lib = LlamaNativeBridge.ensureLoaded(onLog = null) ?: return false
        return try {
            lib.llama_log_set(callbackRef, null)
            installed = true
            append(LEVEL_INFO, "llama_log_set() installiert: C++-Logs landen jetzt in der App.")
            true
        } catch (t: Throwable) {
            // Kein Abbruch: ohne Log-Sink läuft die Engine weiter, nur die Diagnose ist ärmer.
            Log.w(TAG, "llama_log_set fehlgeschlagen: ${t.message}")
            false
        }
    }

    fun isInstalled(): Boolean = installed

    /** Letzte [count] Zeilen, älteste zuerst. */
    fun tail(count: Int = 80): List<Line> = synchronized(lock) {
        ring.takeLast(count.coerceAtLeast(1)).toList()
    }

    /** Als zusammenhängender Text fürs Diagnose-Panel. */
    fun snapshot(count: Int = 80): String = tail(count).joinToString("\n") { it.format() }

    /**
     * Die letzte Zeile, die nach einem Problem aussieht — für Präfixe in
     * Exception-Meldungen ("Modell konnte nicht geladen werden: ggml_new_tensor: ...").
     */
    fun lastProblem(): String? = synchronized(lock) {
        ring.lastOrNull {
            it.level == LEVEL_ERROR || it.level == LEVEL_WARN ||
                it.text.contains("error", ignoreCase = true) || it.text.contains("failed", ignoreCase = true)
        }?.text ?: ring.lastOrNull()?.text
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
    }

    /** Anzahl gespeicherter Zeilen (für die UI-Ampel). */
    fun count(): Int = synchronized(lock) { ring.size }
}
