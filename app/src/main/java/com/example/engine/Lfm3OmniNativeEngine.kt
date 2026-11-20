package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.example.model.GgufModelCatalog
import com.example.model.LfmExecutionPhase
import com.example.model.LfmInferenceConfig
import com.example.model.ModelStorageStatus
import com.example.model.OmniMessage
import com.example.vision.TemporalVideoFrame
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

sealed class OmniStreamOutput {
    data class PhaseChange(val phase: LfmExecutionPhase) : OmniStreamOutput()
    data class TextToken(val text: String) : OmniStreamOutput()
    data class AudioChunk(val pcm24k: ByteArray, val sampleRate: Int = 24000) : OmniStreamOutput()
}

/**
 * Native Speech-to-Speech / Any-to-Any Inferenz-Engine für LFM3-Omni-2B-Abliter8
 * auf Basis von `libllama.so` (llama.cpp + ggml-Backends + libmtmd + `omni_shim.cpp`).
 *
 * Laden und Freigeben laufen über die flache Shim-API (`omni_load` / `omni_free`), alle
 * weiteren Aufrufe per JNA direkt gegen die llama.cpp/mtmd-C-API. Keine JNI-Bridge,
 * keine Struct-Offsets.
 *
 * Unterstützt vollumfänglich OHNE lautlose Fallbacks:
 * - Multi-Frame Live-Vision-Input (`mtmd_bitmap_init` -> `clip_graph_lfm3vl`)
 * - Natives 16 kHz Audio-Input (`mtmd_bitmap_init_from_audio` -> `clip_graph_lfm3a`)
 * - Text-Input & ChatML Konversationshistorie
 * - Echtzeit-Streaming von Text-Tokens UND nativen 24 kHz PCM Sprach-Audio-Chunks
 *   direkt aus dem LFM3-Omni Depthformer + LiquidCodec Decoder (`mtmd_helper_gen_audio_*`).
 */
class Lfm3OmniNativeEngine(
    private val context: Context
) {
    private val tag = "Lfm3OmniNativeEngine"
    private val engineMutex = Mutex()
    private val inferenceMutex = Mutex()

    /** `omni_ctx*` aus `omni_load` — Besitzer von Modell, Kontext und mtmd_context. */
    @Volatile
    private var omniHandle: Pointer? = null

    @Volatile
    private var resolvedPointers: ResolvedNativePointers? = null

    @Volatile
    private var genAudioCtxPtr: Pointer? = null

    @Volatile
    private var isModelLoaded = false
    private var activeModelPath: String? = null
    private var activeProjectorPath: String? = null
    private var detectedArchitecture: String = "lfm3"
    private var activeContextTokens: Int = 4096

    /** n_gpu_layers der tatsächlich erfolgreich geladenen Strategie (0 = CPU) — Basis für BackendTuner. */
    @Volatile
    private var activeGpuLayers: Int = 0

    private val isGenerating = AtomicBoolean(false)
    private val abortRequested = AtomicBoolean(false)

    @Volatile
    var projectorHasVision: Boolean = true
        private set

    @Volatile
    var projectorHasAudio: Boolean = false
        private set

    @Volatile
    var projectorHasAudioGen: Boolean = false
        private set

    /** Zuletzt gemessene Backend-Fähigkeiten der nativen Engine (für Diagnose-UI). */
    @Volatile
    var lastCapabilities: EngineCapabilities? = null
        private set

    @Volatile
    var lastGpuDecision: GpuLayerDecision? = null
        private set

    @Volatile
    var lastThreadDecision: ThreadDecision? = null
        private set

    fun isReady(): Boolean = isModelLoaded && omniHandle != null && genAudioCtxPtr != null && projectorHasAudioGen

    /**
     * Menschlesbarer Bericht für das Diagnose-Panel: Geräte-Tabelle, Entscheidung,
     * Begründung und die letzten nativen Log-Zeilen.
     */
    fun backendReport(): String {
        val text = BackendPolicy.describe(lastCapabilities, lastGpuDecision ?: GpuLayerDecision(0, "noch nicht geladen", "Modell noch nicht geladen.", false), lastThreadDecision)
        val logs = NativeLogSink.snapshot(60)
        return if (logs.isBlank()) text else "$text\n\n--- Letztes natives Log (${NativeLogSink.count()} Zeilen im Puffer) ---\n$logs"
    }

    /**
     * Es gibt keinen nativen "stopCompletion" mehr: die Generierungsschleifen prüfen
     * [abortRequested] bei jedem Schritt. Ein laufender Prefill (`mtmd_helper_eval_chunks`)
     * ist nicht unterbrechbar und endet erst danach.
     */
    fun stopCurrentGeneration() {
        abortRequested.set(true)
    }

    /**
     * Löscht den nativen KV-Cache sowie den Zustand der Audio-Generation-Pipeline
     * zwischen zwei Anrufen sauber und synchronisiert über `inferenceMutex`,
     * ohne die geladenen 1.9-GB-Modellgewichte aus dem RAM zu werfen.
     */
    suspend fun resetSession(): Unit = withContext(Dispatchers.IO) {
        stopCurrentGeneration()
        inferenceMutex.withLock {
            abortRequested.set(false)
            val lib = LlamaNativeBridge.llamaLib ?: return@withLock
            val lctx = resolvedPointers?.lctxPtr
            if (lctx != null && Pointer.nativeValue(lctx) != 0L) {
                try {
                    val mem = lib.llama_get_memory(lctx)
                    if (mem != null && Pointer.nativeValue(mem) != 0L) {
                        lib.llama_memory_clear(mem, true)
                        lib.llama_memory_seq_rm(mem, 0, -1, -1)
                    }
                } catch (e: Throwable) {
                    Log.w(tag, "Fehler beim Zurücksetzen des KV-/Recurrent-Caches: ${e.message}")
                }
            }
            val genCtx = genAudioCtxPtr
            if (genCtx != null && Pointer.nativeValue(genCtx) != 0L) {
                try {
                    lib.mtmd_helper_gen_audio_reset(genCtx)
                } catch (e: Throwable) {
                    Log.w(tag, "Fehler beim Zurücksetzen der Audio-Pipeline: ${e.message}")
                }
            }
            Log.i(tag, "NATIVE-OMNI: Konversations-Session (KV-Cache + Conv1D Recurrent + Audio-Pipeline) vollständig zurückgesetzt.")
        }
    }

    suspend fun checkLocalModelStatus(): ModelStorageStatus = withContext(Dispatchers.IO) {
        val models = GgufModelCatalog.scanLocalModelsFast(context)
        val modelFile = models.filter { !it.isProjector }
            .sortedWith(
                compareByDescending<com.example.model.LocalModelFile> {
                    it.name.contains("abliter8", ignoreCase = true)
                }.thenByDescending { it.sizeMb }
            )
            .firstOrNull()?.file
        val projFile = models.filter { it.isProjector }
            .sortedWith(
                compareByDescending<com.example.model.LocalModelFile> {
                    it.name.contains("abliter8", ignoreCase = true)
                }.thenByDescending { it.sizeMb }
            )
            .firstOrNull()?.file

        val hasModel = modelFile != null && modelFile.exists() && modelFile.length() > 100 * 1024 * 1024
        val hasProj = projFile != null && projFile.exists() && projFile.length() > 50 * 1024 * 1024

        val modelSize = if (modelFile != null) modelFile.length() / (1024f * 1024f) else 0f
        val projSize = if (projFile != null) projFile.length() / (1024f * 1024f) else 0f

        val desc = when {
            isModelLoaded && activeProjectorPath != null && projectorHasAudioGen ->
                "LFM3-Omni Abliter8: Aktiv (Vision + 16kHz Audio In + 24kHz Speech Out)"
            hasModel && hasProj ->
                "LFM3-Omni Abliter8: Bereit (Vision + Speech-to-Speech)"
            hasModel ->
                "mmproj-Projektor fehlt! Für Vision & 24kHz Speech bitte Omni-Projektor laden."
            else ->
                "Kein LFM3-Omni-2B-Abliter8 Modell gefunden"
        }

        ModelStorageStatus(
            isModelLoaded = isModelLoaded,
            isModelFilePresent = hasModel,
            isProjectorPresent = hasProj,
            modelFileName = modelFile?.name,
            modelFileSizeMb = modelSize,
            isProjectorLoaded = activeProjectorPath != null,
            projectorFileSizeMb = projSize,
            projectorHasVision = projectorHasVision,
            projectorHasAudio = projectorHasAudio,
            projectorHasAudioGen = projectorHasAudioGen,
            activeContextTokens = activeContextTokens,
            tensorCount = 0,
            ggufArchitecture = detectedArchitecture,
            statusDescription = desc
        )
    }

    suspend fun loadModel(
        config: LfmInferenceConfig = LfmInferenceConfig(),
        onLog: ((String) -> Unit)? = null,
        onProgress: ((stepText: String, fraction: Float) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        onLog?.invoke("INIT: Starte LFM3-Omni-2B-Abliter8 Speech-to-Speech Engine...")

        val models = GgufModelCatalog.scanLocalModels(context)
        val mainModel = models.filter { !it.isProjector && it.isComplete && it.isValidGguf }
            .sortedWith(
                compareByDescending<com.example.model.LocalModelFile> {
                    it.name.contains("abliter8", ignoreCase = true)
                }.thenByDescending { it.sizeMb }
            )
            .firstOrNull()
        val projector = models.filter { it.isProjector && it.isComplete && it.isValidGguf }
            .sortedWith(
                compareByDescending<com.example.model.LocalModelFile> {
                    it.name.contains("abliter8", ignoreCase = true)
                }.thenByDescending { it.sizeMb }
            )
            .firstOrNull()

        if (mainModel == null) {
            val msg = "Kein vollständiges LFM3-Omni-2B-Abliter8 Hauptmodell gefunden! Bitte im Modell-Manager herunterladen."
            onLog?.invoke("CRITICAL: $msg")
            throw IllegalStateException(msg)
        }

        if (projector == null) {
            val msg = "Multimodaler Omni-Projektor (mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf) fehlt! " +
                "Ohne diesen Projektor sind weder Vision noch native 24kHz Sprachausgabe möglich. Bitte im Modell-Manager herunterladen."
            onLog?.invoke("CRITICAL: $msg")
            throw IllegalStateException(msg)
        }

        engineMutex.withLock {
            if (isModelLoaded && omniHandle != null && genAudioCtxPtr != null && projectorHasAudioGen) {
                onLog?.invoke("INIT: Engine & native Speech-to-Speech Pipeline bereits im RAM aktiv.")
                return@withLock
            }

            // 1. HARDWARE- & SPEICHER-DIAGNOSE
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val freeRamGb = memInfo.availMem / (1024f * 1024f * 1024f)
            val totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
            val isLowMem = memInfo.lowMemory
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unbekannt"
            onLog?.invoke("SYS: RAM frei: ${String.format(Locale.US, "%.2f", freeRamGb)}GB / Gesamt: ${String.format(Locale.US, "%.2f", totalRamGb)}GB (LowMem=$isLowMem) | ABI=$abi")

            // 2. GGUF-DATEI & METADATEN-INSPEKTION (HAUPTMODELL)
            val modelFile = mainModel.file
            onLog?.invoke("PATH: Hauptmodell: '${modelFile.name}' (${String.format(Locale.US, "%.1f", modelFile.length() / (1024f * 1024f))} MB)")
            onLog?.invoke("PATH: Pfad: ${modelFile.absolutePath}")
            val mainMeta = inspectGgufMetadata(modelFile, onLog, prefix = "MODELL")
            if (mainMeta.architecture.isNotBlank()) {
                detectedArchitecture = mainMeta.architecture
            }

            // 3. GGUF-DATEI & METADATEN-INSPEKTION (PROJEKTOR)
            val projFile = projector.file
            onLog?.invoke("PATH: Omni-Projektor: '${projFile.name}' (${String.format(Locale.US, "%.1f", projFile.length() / (1024f * 1024f))} MB)")
            val projMeta = inspectGgufMetadata(projFile, onLog, prefix = "PROJEKTOR")
            projectorHasVision = projMeta.hasVisionEncoder
            projectorHasAudio = projMeta.hasAudioEncoder
            projectorHasAudioGen = projMeta.hasAudioDecoder
            onLog?.invoke("[PROJEKTOR] Fähigkeiten: Vision=$projectorHasVision | AudioIn(16kHz)=$projectorHasAudio | AudioOut(24kHz Depthformer+LiquidCodec)=$projectorHasAudioGen")

            onProgress?.invoke("Initialisiere LFM3-Omni + Speech-Pipeline...", 0.3f)

            // Echte Backend-Erkennung statt Annahmen: welche ggml-Geräte meldet DIESE
            // libllama.so? NativeLogSink vorher installieren, damit die C++-Meldungen
            // des Ladevorgangs in der App landen und nicht nur in logcat.
            preloadNativeLibraries(onLog)
            NativeLogSink.install()
            val nativeLibDir = try {
                context.applicationInfo.nativeLibraryDir
            } catch (e: Throwable) {
                null
            }
            val capabilities = NativeBackendProbe.probe(
                nativeLibDir = nativeLibDir,
                forceReread = true,
                onLog = onLog
            )
            lastCapabilities = capabilities

            // Crash-Guard: ist der letzte GPU-Ladeversuch abgestürzt, wird CPU erzwungen.
            // AUTO entscheidet nach Messung (BackendTuner), danach greift der Crash-Guard.
            val tuningMode = BackendTuner.modeKey(config.omniOutputMode)
            val gpuDecision = GpuCrashGuard.applyTo(
                context,
                BackendTuner.applyTo(
                    context,
                    config.backendMode,
                    tuningMode,
                    BackendPolicy.resolve(config.backendMode, capabilities, config.gpuLayers),
                    onLog
                )
            )
            lastGpuDecision = gpuDecision

            val topology = com.example.hardware.CpuTopologyReader.read()
            val autoThreads = com.example.hardware.CpuTopologyReader.suggestedThreads(topology)
            val threadDecision = BackendPolicy.resolveThreads(
                userThreads = if (config.cpuThreads > 0) config.cpuThreads else autoThreads,
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                bigCoreCount = autoThreads.takeIf { it > 0 }
            )
            lastThreadDecision = threadDecision

            val targetGpuLayers = gpuDecision.nGpuLayers
            val optimalThreads = threadDecision.threads
            onLog?.invoke("BACKEND: Entscheidung = ${gpuDecision.label} | n_gpu_layers=$targetGpuLayers | threads=$optimalThreads")
            onLog?.invoke("BACKEND: Begründung = ${gpuDecision.reason}")
            if (gpuDecision.warned) {
                onLog?.invoke("WARNUNG: GPU-Auslagerung nicht aktiv, obwohl gewünscht — ${capabilities.diagnosis}")
            }
            if (threadDecision.clampedToPhysical) {
                onLog?.invoke("HINWEIS: ${config.cpuThreads} Threads eingestellt, Gerät hat aber nur weniger schnelle Kerne — begrenzt auf $optimalThreads.")
            }

            data class LoadStrategy(
                val name: String,
                val contextSize: Int,
                val useMmap: Boolean,
                val gpuLayers: Int
            )

            // Kein Fallback auf Text-Only! Alle Ladeversuche laden IMMER Hauptmodell + mmproj.
            // use_mmap=true vermeidet 2+ GB anonyme zRAM-Kompression auf 12-GB-Geräten mit ~3,6 GB freiem RAM.
            val strategies = buildList {
                add(LoadStrategy("LFM3-Omni Vollmodus (2K Kontext + Vision + 16kHz/24kHz Audio, mmap)", 2048, true, targetGpuLayers))
                if (totalRamGb >= 5.5f && freeRamGb >= 1.2f) {
                    add(LoadStrategy("LFM3-Omni Erweiterter Modus (4K Kontext + Vision + 16kHz/24kHz Audio, mmap)", 4096, true, targetGpuLayers))
                }
                add(LoadStrategy("LFM3-Omni Kompakt-Kontext (1K Kontext + Vision + 16kHz/24kHz Audio, mmap)", 1024, true, targetGpuLayers))
                add(LoadStrategy("LFM3-Omni Kompakt-Kontext (1K Kontext, ohne mmap Fallback)", 1024, false, targetGpuLayers))
                // Sichtbarer (geloggter) CPU-Rückfall, wenn die GPU-Versuche nur fehlschlagen (kein Absturz).
                // Bei erzwungenem GPU-Modus bewusst nicht: dort soll der Fehler sichtbar bleiben.
                if (targetGpuLayers > 0 && config.backendMode != ComputeBackendMode.GPU) {
                    add(LoadStrategy("CPU-Rückfall (GPU-Laden fehlgeschlagen, 2K Kontext, mmap)", 2048, true, 0))
                }
            }

            var initSuccess = false
            var lastErrorMsg = "Unbekannter Fehler"

            val lib = LlamaNativeBridge.ensureLoaded(onLog)
                ?: throw IllegalStateException(
                    "libllama.so enthält keine passende omni_*-Shim-API. Engine neu bauen: native/build_engine.sh --backend vulkan"
                )

            // Marker vor dem ersten GPU-Versuch; wird nach dem Laden (Erfolg oder normaler Fehler) gelöscht.
            // Bleibt er stehen, ist der Treiber im Ladevorgang abgestürzt.
            if (targetGpuLayers > 0) GpuCrashGuard.arm(context)

            try {
                for ((index, strategy) in strategies.withIndex()) {
                    onLog?.invoke("VERSUCH #${index + 1}: ${strategy.name} | ctx=${strategy.contextSize} | threads=$optimalThreads | gpuLayers=${strategy.gpuLayers} | mmap=${strategy.useMmap}")

                    var createdHandle: Pointer? = null
                    try {
                        val handle = withTimeout(120_000L) {
                            withContext(Dispatchers.IO) {
                                lib.omni_load(
                                    modelFile.absolutePath,
                                    projFile.absolutePath,
                                    strategy.contextSize,
                                    512,
                                    optimalThreads,
                                    strategy.gpuLayers,
                                    strategy.useMmap,
                                    false
                                )
                            }
                        } ?: throw IllegalStateException("omni_load lieferte null (Modell/Projektor/Kontext konnte nicht initialisiert werden)")

                        createdHandle = handle

                        // Zwingende Initialisierung der nativen 24 kHz Speech-to-Speech Pipeline
                        setupNativeAudioGenPipeline(handle, onLog)

                        onLog?.invoke("SUCCESS: LFM3-Omni Speech-to-Speech Engine bereit! Handle: ${Pointer.nativeValue(handle)} (ctx=${strategy.contextSize}, gpuLayers=${strategy.gpuLayers})")
                        omniHandle = handle
                        activeModelPath = modelFile.absolutePath
                        activeProjectorPath = projFile.absolutePath
                        activeContextTokens = strategy.contextSize
                        activeGpuLayers = strategy.gpuLayers
                        isModelLoaded = true
                        initSuccess = true
                        onProgress?.invoke("Bereit", 1.0f)
                        break
                    } catch (e: CancellationException) {
                        freeNativeAudioGenPipeline()
                        createdHandle?.let { runCatching { lib.omni_free(it) } }
                        throw e
                    } catch (e: Throwable) {
                        lastErrorMsg = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                        onLog?.invoke("  -> FEHLER bei Versuch #${index + 1}: $lastErrorMsg")
                        captureLogcatNativeLogs(onLog)
                        freeNativeAudioGenPipeline()
                        createdHandle?.let { runCatching { lib.omni_free(it) } }
                    }
                }
            } finally {
                // Prozess lebt noch -> kein Treiberabsturz im Ladevorgang.
                GpuCrashGuard.disarm(context)
            }

            if (!initSuccess) {
                // Was llama.cpp/ggml wirklich gesagt hat, nicht nur "interner Fehler":
                // die letzte native Log-Zeile (llama_log_set -> NativeLogSink) mit ausgeben.
                val nativeProblem = NativeLogSink.lastProblem()
                val nativeSuffix = if (nativeProblem.isNullOrBlank()) "" else " | native Meldung: $nativeProblem"
                val failureDetail = "LFM3-Omni Speech-to-Speech Pipeline konnte nicht geladen werden: $lastErrorMsg$nativeSuffix"
                onLog?.invoke("CRITICAL: $failureDetail")
                isModelLoaded = false
                omniHandle = null
                throw IllegalStateException(failureDetail)
            }
        }
    }

    private fun setupNativeAudioGenPipeline(
        handle: Pointer,
        onLog: ((String) -> Unit)?
    ) {
        freeNativeAudioGenPipeline()
        val lib = LlamaNativeBridge.ensureLoaded(onLog)
            ?: throw IllegalStateException("JNA Bindung zu libllama.so konnte nicht hergestellt werden.")
        val ptrs = LlamaNativeBridge.resolvePointers(handle, onLog)
            ?: throw IllegalStateException("Konnte native Pointer (lctx/model/mtmd) nicht über die omni_*-Accessoren auflösen.")

        val mctx = ptrs.mctxPtr
            ?: throw IllegalStateException("mtmd_context im geladenen Kontext ist null (mmproj wurde nicht geladen).")

        resolvedPointers = ptrs

        // Aktiviert das Speichern der Hidden-States (n_embd = 2048) nach jedem llama_decode Schritt,
        // damit der LFM3-Omni Depthformer & LiquidCodec Decoder 24kHz Audio-Frames generieren kann.
        lib.llama_set_embeddings(ptrs.lctxPtr, true)
        onLog?.invoke("NATIVE-OMNI: llama_set_embeddings(lctx, true) gesetzt | n_embd=${ptrs.nEmbd} | n_vocab=${ptrs.nVocab}")

        val supVision = lib.mtmd_support_vision(mctx)
        val supAudio = lib.mtmd_support_audio(mctx)
        val inSampleRate = lib.mtmd_get_audio_sample_rate(mctx)
        projectorHasVision = supVision
        projectorHasAudio = supAudio
        onLog?.invoke("NATIVE-OMNI: mtmd_context aktiv (Vision=$supVision, AudioIn=$supAudio @ ${inSampleRate}Hz)")

        val genCtx = lib.mtmd_helper_gen_audio_init(ptrs.lctxPtr, mctx)
            ?: throw IllegalStateException("mtmd_helper_gen_audio_init lieferte null.")

        // Layout-Annahme (gegen den neuen llama.cpp-Stand per Gerätelauf zu prüfen):
        // der Pipeline-Zeiger liegt bei +0 des gen_audio-Kontexts.
        val pipelinePtr = genCtx.getPointer(0L)
        if (pipelinePtr == null || Pointer.nativeValue(pipelinePtr) == 0L) {
            lib.mtmd_helper_gen_audio_free(genCtx)
            projectorHasAudioGen = false
            throw IllegalStateException(
                "Geladener Projektor enthält keine LFM3-Omni Audio-Decoder Pipeline (Depthformer + LiquidCodec fehlt). " +
                    "Bitte mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf aus abliter8-ai/lfm3-2b-omni-abliter8-gguf verwenden."
            )
        }

        genAudioCtxPtr = genCtx
        projectorHasAudioGen = true
        onLog?.invoke("NATIVE-OMNI: lfm3a_gen_audio_pipeline initialisiert! Native 24kHz Speech-Ausgabe AKTIV.")
    }

    private fun freeNativeAudioGenPipeline() {
        val genCtx = genAudioCtxPtr
        genAudioCtxPtr = null
        resolvedPointers = null
        if (genCtx != null) {
            try {
                LlamaNativeBridge.llamaLib?.mtmd_helper_gen_audio_free(genCtx)
            } catch (e: Throwable) {
                Log.w(tag, "Fehler beim Freigeben von genAudioCtx: ${e.message}")
            }
        }
    }

    private fun preloadNativeLibraries(onLog: ((String) -> Unit)? = null) {
        try {
            System.loadLibrary("llama")
            onLog?.invoke("NATIVE: 'libllama.so' (LFM3 Core + Multimodal Engine + omni-Shim) erfolgreich geladen")
        } catch (e: Throwable) {
            onLog?.invoke("NATIVE: Fehler beim Laden von 'libllama.so': ${e.message}")
        }
        // Zur Laufzeit-Kontrolle, welche Engine-Bibliotheken wirklich gemappt sind.
        try {
            val mapped = File("/proc/self/maps").useLines { lines ->
                lines.filter { "librnllama" in it || "libllama" in it || "libggml" in it }
                    .map { it.substringAfterLast('/') }
                    .distinct()
                    .toList()
            }
            onLog?.invoke("BACKEND: gemappte Engine-Bibliotheken: ${mapped.joinToString().ifBlank { "keine" }}")
            if (mapped.any { it.startsWith("librnllama") }) {
                onLog?.invoke("WARNUNG: librnllama*.so ist im Prozess gemappt (Altlast der JNI-Dependency) — wird nicht benutzt, sollte aber aus dem APK verschwinden.")
            }
        } catch (_: Throwable) {
        }
    }

    fun executeMultimodalStream(
        userTextPrompt: String,
        rawAudioPcm: ByteArray?,
        sampledCameraFrames: List<ByteArray>?,
        temporalCameraFrames: List<TemporalVideoFrame>? = null,
        femaleVoiceDirective: String,
        conversationHistory: List<OmniMessage> = emptyList(),
        inferenceConfig: LfmInferenceConfig = LfmInferenceConfig(),
        isGreeting: Boolean = false,
        onLog: ((String) -> Unit)? = null
    ): Flow<OmniStreamOutput> = callbackFlow {
        if (!isReady()) {
            loadModel(config = inferenceConfig, onLog = onLog)
        }

        val ptrs = resolvedPointers ?: run {
            close(IllegalStateException("Native LFM3-Omni Pointer sind nicht initialisiert"))
            return@callbackFlow
        }
        val genCtx = genAudioCtxPtr ?: run {
            close(IllegalStateException("Native LFM3-Omni Audio-Generation Pipeline ist nicht initialisiert"))
            return@callbackFlow
        }
        val lib = LlamaNativeBridge.llamaLib ?: run {
            close(IllegalStateException("libllama.so JNA-Bridge nicht geladen"))
            return@callbackFlow
        }
        if (ptrs.mctxPtr == null || !projectorHasAudioGen) {
            close(IllegalStateException("Multimodaler Projektor oder 24kHz Audio-Generator fehlt."))
            return@callbackFlow
        }

        if (isGenerating.get()) {
            stopCurrentGeneration()
        }
        abortRequested.set(false)

        trySend(OmniStreamOutput.PhaseChange(LfmExecutionPhase.CONV_PREFILL))

        val maxFramesCap = if (activeContextTokens >= 2048) 4 else 2
        val desiredFrames = inferenceConfig.visionKeyframesCount.coerceIn(1, maxFramesCap)
        // Kamera-Frames gehören ausschließlich zur aktuellen Nutzer-Nachricht (niemals zur Begrüßung oder zu alten Turns)
        val effectiveTemporalFrames: List<TemporalVideoFrame> = when {
            isGreeting || !projectorHasVision -> emptyList()
            !temporalCameraFrames.isNullOrEmpty() -> temporalCameraFrames.takeLast(desiredFrames)
            !sampledCameraFrames.isNullOrEmpty() -> {
                val subset = sampledCameraFrames.takeLast(desiredFrames)
                subset.mapIndexed { idx, bytes ->
                    val relSec = ((subset.size - 1 - idx) * (1.0f / inferenceConfig.visionFps.coerceAtLeast(0.5f)))
                    TemporalVideoFrame(
                        jpegBytes = bytes,
                        capturedAtMs = System.currentTimeMillis() - (relSec * 1000).toLong(),
                        relativeSecondsAgo = relSec,
                        width = inferenceConfig.visionResolution,
                        height = inferenceConfig.visionResolution
                    )
                }
            }
            else -> emptyList()
        }

        val customGreeting = inferenceConfig.greetingPrompt.trim()
            .ifBlank { com.example.model.DEFAULT_GREETING_PROMPT }

        val effectiveUserPrompt = when {
            isGreeting -> customGreeting
            userTextPrompt.isNotBlank() -> userTextPrompt.trim()
            else -> ""
        }

        val job = launch(Dispatchers.IO) {
            inferenceMutex.withLock {
                try {
                    isGenerating.set(true)
                    executeNativeOmniAudioTextStream(
                        lib = lib,
                        ptrs = ptrs,
                        genCtx = genCtx,
                        userText = effectiveUserPrompt,
                        rawAudioPcm16k = rawAudioPcm,
                        visionFrames = effectiveTemporalFrames,
                        history = conversationHistory,
                        inferenceConfig = inferenceConfig,
                        isGreeting = isGreeting,
                        onLog = onLog,
                        emitOutput = { trySend(it) }
                    )
                    trySend(OmniStreamOutput.PhaseChange(LfmExecutionPhase.READY_IDLE))
                    close()
                } catch (e: CancellationException) {
                    abortRequested.set(true)
                    close()
                } catch (e: Throwable) {
                    Log.e(tag, "Fehler in nativer LFM3-Omni Speech-to-Speech Pipeline", e)
                    close(e)
                } finally {
                    isGenerating.set(false)
                }
            }
        }

        awaitClose {
            abortRequested.set(true)
            stopCurrentGeneration()
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Führt die vollständige native C++ LFM3-Omni Pipeline (`mtmd_tokenize` + `mtmd_helper_eval_chunks`
     * + `mtmd_helper_gen_audio_*`) aus und streamt während der Generierung sowohl die Text-Tokens
     * als auch die nativen 24 kHz PCM Sprach-Audio-Chunks des Modells in Echtzeit.
     * Keine lautlosen Fallbacks: schlägt explizit mit einer Exception fehl, falls Generierung oder Audioausgabe fehlschlägt.
     */
    private fun executeNativeOmniAudioTextStream(
        lib: LlamaNativeLib,
        ptrs: ResolvedNativePointers,
        genCtx: Pointer,
        userText: String,
        rawAudioPcm16k: ByteArray?,
        visionFrames: List<TemporalVideoFrame>,
        history: List<OmniMessage>,
        inferenceConfig: LfmInferenceConfig,
        isGreeting: Boolean,
        onLog: ((String) -> Unit)? = null,
        emitOutput: (OmniStreamOutput) -> Unit
    ) {
        val mctx = ptrs.mctxPtr
            ?: throw IllegalStateException("mtmd_context ist null")
        val lctx = ptrs.lctxPtr
        val vocab = ptrs.vocabPtr
        val nEmbd = ptrs.nEmbd.coerceAtLeast(2048)
        val nVocab = ptrs.nVocab

        val nativeBitmaps = mutableListOf<Pointer>()
        var chunksPtr: Pointer? = null
        var samplerChain: Pointer? = null

        try {
            // 1. Kamera-Frames als RGB24 mtmd_bitmap* aufbereiten
            var validVisionCount = 0
            if (projectorHasVision && visionFrames.isNotEmpty()) {
                for (frame in visionFrames) {
                    val rgbTriple = decodeJpegToRgb24Bytes(frame.jpegBytes)
                    if (rgbTriple != null) {
                        val (w, h, rgbBytes) = rgbTriple
                        val bmpPtr = lib.mtmd_bitmap_init(w, h, rgbBytes)
                        if (bmpPtr != null && Pointer.nativeValue(bmpPtr) != 0L) {
                            nativeBitmaps.add(bmpPtr)
                            validVisionCount++
                        }
                    }
                }
            }

            // 2. 16 kHz Nutzer-Sprachaufnahme als Float32 mtmd_bitmap* für den LFM3-Omni Audio-Encoder aufbereiten
            var hasAudioBitmap = false
            if (projectorHasAudio && rawAudioPcm16k != null && rawAudioPcm16k.size >= 3200) {
                val floatSamples = LlamaNativeBridge.pcm16LeToFloatArray(rawAudioPcm16k)
                if (floatSamples.isNotEmpty()) {
                    val audioBmpPtr = lib.mtmd_bitmap_init_from_audio(floatSamples.size.toLong(), floatSamples)
                    if (audioBmpPtr != null && Pointer.nativeValue(audioBmpPtr) != 0L) {
                        nativeBitmaps.add(audioBmpPtr)
                        hasAudioBitmap = true
                    } else {
                        throw IllegalStateException("mtmd_bitmap_init_from_audio konnte 16kHz Audio-Bitmap nicht anlegen")
                    }
                }
            }

            // 3. KV-Cache + LFM3 Conv1D Recurrent State vollständig für sauberen Turn-Start leeren
            val isPureTextMode = inferenceConfig.omniOutputMode.equals("SYSTEM_TTS", ignoreCase = true) ||
                inferenceConfig.omniOutputMode.equals("TEXT", ignoreCase = true)

            val turnStartMs = System.currentTimeMillis()
            val mem = lib.llama_get_memory(lctx)
            if (mem != null && Pointer.nativeValue(mem) != 0L) {
                lib.llama_memory_clear(mem, true)
                lib.llama_memory_seq_rm(mem, 0, -1, -1)
            }
            // Die Thread-Entscheidung (BackendPolicy.resolveThreads) gilt auch pro Turn. Früher wurde
            // sie hier mit cpuThreads.coerceIn(2, 8) überschrieben -> bei "Auto" (0) immer 2 Threads.
            val decodeThreads = lastThreadDecision?.threads ?: inferenceConfig.cpuThreads.coerceIn(2, 8)
            val prefillThreads = decodeThreads
            lib.llama_set_n_threads(lctx, decodeThreads, prefillThreads)
            LlamaNativeBridge.syncMultimodalThreads(mctx, decodeThreads, onLog)
            // Im reinen Text-Modus (SYSTEM_TTS) werden keine 2048-Float-Embeddings pro Schritt benötigt -> maximale Geschwindigkeit
            lib.llama_set_embeddings(lctx, !isPureTextMode)

            // 4. ChatML-Präfix aufbauen:
            // - Bei SYSTEM_TTS / TEXT inklusive <|im_start|>assistant\n am Ende für direktes Text-Token-Streaming
            // - Bei MIXED / AUDIO / AUTO bis <|im_end|>\n, da mtmd_helper_gen_audio_step_prompt den Assistant-Turn öffnet
            val prefixPrompt = buildNativeOmniPrefixPrompt(
                userText = userText,
                history = history,
                visionCount = validVisionCount,
                hasNativeAudio = hasAudioBitmap,
                inferenceConfig = inferenceConfig
            )

            // 5. Multimodales Präfix (Text + 16kHz Audio + Live-Vision) über mtmd_tokenize + mtmd_helper_eval_chunks evaluieren
            chunksPtr = lib.mtmd_input_chunks_init()
                ?: throw IllegalStateException("mtmd_input_chunks_init lieferte null")

            val (inputTextStruct, inputTextCStr) = LlamaNativeBridge.buildMtmdInputText(
                text = prefixPrompt,
                addSpecial = false,
                parseSpecial = true
            )

            val bitmapsArrayMem: Memory? = if (nativeBitmaps.isNotEmpty()) {
                Memory((nativeBitmaps.size * 8).toLong()).apply {
                    nativeBitmaps.forEachIndexed { idx, ptr ->
                        setPointer((idx * 8).toLong(), ptr)
                    }
                }
            } else {
                null
            }

            val tokRc = lib.mtmd_tokenize(
                mctx,
                chunksPtr,
                inputTextStruct,
                bitmapsArrayMem,
                nativeBitmaps.size.toLong()
            )
            // Keep C string buffer alive during mtmd_tokenize
            inputTextCStr.size()

            if (tokRc != 0) {
                throw IllegalStateException("mtmd_tokenize fehlgeschlagen (Code $tokRc)")
            }

            val newNPastRef = IntByReference(0)
            val evalRc = lib.mtmd_helper_eval_chunks(
                mctx,
                lctx,
                chunksPtr,
                0,              // n_past = 0
                0,              // seq_id = 0
                512,            // n_batch = 512
                isPureTextMode, // logits_last = true bei reinem Text-Streaming (SYSTEM_TTS), false bei gen_audio_step_prompt
                newNPastRef
            )
            if (evalRc != 0) {
                throw IllegalStateException("mtmd_helper_eval_chunks fehlgeschlagen (Code $evalRc)")
            }

            val nPastAfterPrefix = newNPastRef.value.coerceAtLeast(1)
            val prefillDoneMs = System.currentTimeMillis()
            val prefillDurationMs = prefillDoneMs - turnStartMs
            Log.i(tag, "NATIVE-OMNI Prefill evaluiert in ${prefillDurationMs}ms: n_past=$nPastAfterPrefix (VisionFrames=$validVisionCount, AudioIn=$hasAudioBitmap, Mode=${inferenceConfig.omniOutputMode}, Threads=$decodeThreads)")
            onLog?.invoke("TURN PREFILL: ${prefillDurationMs}ms | n_past=$nPastAfterPrefix | Vision=$validVisionCount | AudioIn=$hasAudioBitmap | Mode=${inferenceConfig.omniOutputMode} | Threads=$decodeThreads")

            // Bitmaps und Chunks nach Prefill freigeben
            lib.mtmd_input_chunks_free(chunksPtr)
            chunksPtr = null
            for (bmp in nativeBitmaps) {
                lib.mtmd_bitmap_free(bmp)
            }
            nativeBitmaps.clear()

            if (abortRequested.get()) return

            emitOutput(OmniStreamOutput.PhaseChange(LfmExecutionPhase.OMNI_DECODE))

            // =========================================================================
            // PFAD A: Reines Hochgeschwindigkeits-Text-Streaming für Android System-TTS ("SYSTEM_TTS")
            //         Alle multimodalen Eingaben (16kHz Audio + Live-Vision) wurden nativ verarbeitet,
            //         das Modell generiert ohne Depthformer/LiquidCodec-Verzögerung reine Text-Tokens.
            // =========================================================================
            if (isPureTextMode) {
                samplerChain = lib.llama_sampler_chain_init(1.toByte())
                    ?: throw IllegalStateException("llama_sampler_chain_init lieferte null")

                val repPen = inferenceConfig.repetitionPenalty.coerceIn(1.00f, 1.35f)
                if (repPen > 1.005f) {
                    val penSampler = lib.omni_sampler_penalties(nVocab, 64, repPen, 0.0f, 0.0f)
                    if (penSampler != null) lib.llama_sampler_chain_add(samplerChain, penSampler)
                }

                if (inferenceConfig.temperature <= 0.02f) {
                    val greedySampler = lib.llama_sampler_init_greedy()
                    if (greedySampler != null) lib.llama_sampler_chain_add(samplerChain, greedySampler)
                } else {
                    val topKSampler = lib.llama_sampler_init_top_k(40)
                    if (topKSampler != null) lib.llama_sampler_chain_add(samplerChain, topKSampler)

                    val topPSampler = lib.llama_sampler_init_top_p(inferenceConfig.topP.coerceIn(0.1f, 1.0f), 1L)
                    if (topPSampler != null) lib.llama_sampler_chain_add(samplerChain, topPSampler)

                    val tempSampler = lib.llama_sampler_init_temp(inferenceConfig.temperature.coerceIn(0.02f, 1.4f))
                    if (tempSampler != null) lib.llama_sampler_chain_add(samplerChain, tempSampler)

                    val distSampler = lib.llama_sampler_init_dist(Random.nextInt())
                    if (distSampler != null) lib.llama_sampler_chain_add(samplerChain, distSampler)
                }

                val maxTextTokens = if (isGreeting) {
                    64
                } else {
                    inferenceConfig.maxNewTokens.coerceIn(32, 512)
                }

                val tokenMem = Memory(4L)
                // 56-Byte llama_batch Struct für llama_decode (n_tokens=1 an +0, token-Pointer an +8, Rest NULL -> automatisches KV-Pos-Tracking)
                val singleTokenBatchMem = Memory(56L).apply {
                    clear()
                    setInt(0L, 1)
                    setPointer(8L, tokenMem)
                }
                val pieceBuf = ByteArray(256)
                val streamStartMs = System.currentTimeMillis()
                val rawTextSoFar = StringBuilder()
                val visibleEmittedSoFar = StringBuilder()
                var firstTextMs = -1L
                var textSteps = 0
                var stopReason = "max_tokens"

                for (step in 0 until maxTextTokens) {
                    if (abortRequested.get()) {
                        stopReason = "aborted"
                        break
                    }

                    val sampledToken = lib.llama_sampler_sample(samplerChain, lctx, -1)
                    lib.llama_sampler_accept(samplerChain, sampledToken)
                    textSteps++

                    if (sampledToken < 0 || lib.llama_vocab_is_eog(vocab, sampledToken)) {
                        stopReason = "eog_token"
                        break
                    }

                    if (!lib.llama_vocab_is_control(vocab, sampledToken)) {
                        val nBytes = lib.llama_token_to_piece(vocab, sampledToken, pieceBuf, pieceBuf.size, 0, false)
                        if (nBytes > 0) {
                            val rawPiece = String(pieceBuf, 0, nBytes.coerceAtMost(pieceBuf.size), Charsets.UTF_8)
                            if (findStopMarkerIndex(rawPiece) != -1) {
                                stopReason = "stop_marker"
                                break
                            }
                            val cleanPiece = sanitizeToken(rawPiece)
                            if (cleanPiece.isNotEmpty()) {
                                rawTextSoFar.append(cleanPiece)
                                val fullRaw = rawTextSoFar.toString()
                                if (findStopMarkerIndex(fullRaw) != -1) {
                                    stopReason = "stop_marker"
                                    break
                                }
                                val cleanVisible = stripThinkingAndTags(fullRaw)
                                if (hasRepetitionLoop(cleanVisible)) {
                                    stopReason = "repetition_loop"
                                    break
                                }
                                if (cleanVisible.length > visibleEmittedSoFar.length) {
                                    if (firstTextMs < 0L) {
                                        firstTextMs = System.currentTimeMillis() - streamStartMs
                                    }
                                    val delta = cleanVisible.substring(visibleEmittedSoFar.length)
                                    visibleEmittedSoFar.append(delta)
                                    emitOutput(OmniStreamOutput.TextToken(delta))
                                }
                            }
                        }
                    }

                    // Dekodiere das gezogene Text-Token für den nächsten Schritt
                    tokenMem.setInt(0L, sampledToken)
                    val decRc = lib.llama_decode(lctx, singleTokenBatchMem)
                    if (decRc != 0) {
                        throw IllegalStateException("llama_decode im Text-Modus fehlgeschlagen (Code $decRc)")
                    }
                }

                val elapsedMs = (System.currentTimeMillis() - streamStartMs).coerceAtLeast(1L)
                val tokPerSec = (textSteps * 1000.0f) / elapsedMs
                val statsSummary = "TURN DONE [SYSTEM_TTS] ($stopReason): gen=${elapsedMs}ms (1stTxt=${firstTextMs}ms) | " +
                    "tokens=$textSteps (${String.format(Locale.US, "%.1f", tokPerSec)} tok/s)"
                Log.i(tag, "$statsSummary | Text='${visibleEmittedSoFar}'")
                onLog?.invoke(statsSummary)
                if (!isGreeting && textSteps >= 8 && stopReason != "aborted") {
                    BackendTuner.record(context, activeGpuLayers > 0, "text", 1000.0f / tokPerSec.coerceAtLeast(0.1f), onLog)
                }
                return
            }

            // =========================================================================
            // PFAD B: Native LFM3-Omni 24 kHz Audio+Text Pipeline (lfm3a_gen_audio_pipeline)
            // =========================================================================
            lib.mtmd_helper_gen_audio_reset(genCtx)

            val outModeInt = when (inferenceConfig.omniOutputMode.uppercase(Locale.ROOT)) {
                "AUDIO" -> LlamaNativeBridge.OUT_MODE_AUDIO
                "AUTO" -> LlamaNativeBridge.OUT_MODE_AUTO
                else -> LlamaNativeBridge.OUT_MODE_MIXED
            }

            // Das Input-Struct wird jetzt in C++ gegen die eingebauten Header befüllt (omni_gen_set_input).
            // LFM3-Omni unterstützt keine separate speaker/voice-Konditionierung.
            // n_past MUSS > 0 sein, damit step_prompt den KV-Cache von Seq 0 nicht leert.
            val setInpRc = lib.omni_gen_set_input(
                genCtx,
                "<|im_start|>assistant\n",
                nPastAfterPrefix.coerceAtLeast(1),
                outModeInt,
                true,
                0,
                null,
                50,
                1.0f,
                -1,
                LlamaNativeBridge.OUT_TYPE_PCM
            )
            if (setInpRc != 0) {
                throw IllegalStateException("mtmd_helper_gen_audio_set_input (omni_gen_set_input) fehlgeschlagen (Code $setInpRc)")
            }

            while (!abortRequested.get()) {
                val stepRem = lib.mtmd_helper_gen_audio_step_prompt(genCtx, 512L)
                if (stepRem < 0) {
                    throw IllegalStateException("mtmd_helper_gen_audio_step_prompt fehlgeschlagen (Code $stepRem)")
                }
                if (stepRem == 0) break
            }
            val stepPromptMs = System.currentTimeMillis() - prefillDoneMs
            onLog?.invoke("TURN PROMPT-STEP: ${stepPromptMs}ms | mode=${inferenceConfig.omniOutputMode}")

            // 7. Initialen Hidden-State (h_state) aus llama_get_embeddings_ith(lctx, -1) kopieren
            val hStateBytes = (nEmbd * 4).toLong()
            val hStateMem = Memory(hStateBytes)
            hStateMem.clear()
            val initialEmbdPtr = lib.llama_get_embeddings_ith(lctx, -1)
                ?: throw IllegalStateException("llama_get_embeddings_ith(lctx, -1) ist null nach Prefill — Depthformer benötigt Hidden States!")
            if (Pointer.nativeValue(initialEmbdPtr) == 0L) {
                throw IllegalStateException("Ungültiger Hidden-State Pointer nach Prefill")
            }
            hStateMem.write(0L, initialEmbdPtr.getFloatArray(0L, nEmbd), 0, nEmbd)

            // 8. Nativen llama_sampler mit Logit-Bias aus mtmd_helper_gen_audio_get_logit_bias initialisieren
            samplerChain = lib.llama_sampler_chain_init(1.toByte())
                ?: throw IllegalStateException("llama_sampler_chain_init lieferte null")

            val nBiasRef = LongByReference(0L)
            val biasPtr = lib.mtmd_helper_gen_audio_get_logit_bias(genCtx, nBiasRef)
            val nBias = nBiasRef.value.toInt()
            if (nBias > 0 && biasPtr != null && Pointer.nativeValue(biasPtr) != 0L) {
                val biasSampler = lib.llama_sampler_init_logit_bias(nVocab, nBias, biasPtr)
                if (biasSampler != null) {
                    lib.llama_sampler_chain_add(samplerChain, biasSampler)
                }
            }

            val repPen = inferenceConfig.repetitionPenalty.coerceIn(1.00f, 1.35f)
            if (repPen > 1.005f) {
                val penSampler = lib.omni_sampler_penalties(nVocab, 64, repPen, 0.0f, 0.0f)
                if (penSampler != null) lib.llama_sampler_chain_add(samplerChain, penSampler)
            }

            if (inferenceConfig.temperature <= 0.02f) {
                val greedySampler = lib.llama_sampler_init_greedy()
                if (greedySampler != null) lib.llama_sampler_chain_add(samplerChain, greedySampler)
            } else {
                val topKSampler = lib.llama_sampler_init_top_k(40)
                if (topKSampler != null) lib.llama_sampler_chain_add(samplerChain, topKSampler)

                val topPSampler = lib.llama_sampler_init_top_p(inferenceConfig.topP.coerceIn(0.1f, 1.0f), 1L)
                if (topPSampler != null) lib.llama_sampler_chain_add(samplerChain, topPSampler)

                val tempSampler = lib.llama_sampler_init_temp(inferenceConfig.temperature.coerceIn(0.02f, 1.4f))
                if (tempSampler != null) lib.llama_sampler_chain_add(samplerChain, tempSampler)

                val distSampler = lib.llama_sampler_init_dist(Random.nextInt())
                if (distSampler != null) lib.llama_sampler_chain_add(samplerChain, distSampler)
            }

            // 9. Autoregressive Interleaved Text + 24 kHz Audio Streaming-Schleife
            val maxSteps = if (isGreeting) {
                360
            } else {
                (inferenceConfig.maxNewTokens * 4).coerceIn(288, 1280)
            }

            val hNextRef = PointerByReference(Pointer.NULL)
            val stopByteRef = ByteByReference(0)
            val srRef = IntByReference(24000)
            val dataPtrRef = PointerByReference(Pointer.NULL)
            val dataLenRef = LongByReference(0L)
            val nSamplesRef = LongByReference(0L)
            val pieceBuf = ByteArray(256)

            val streamStartMs = System.currentTimeMillis()
            val rawTextSoFar = StringBuilder()
            val visibleEmittedSoFar = StringBuilder()
            var totalAudioSamplesEmitted = 0L
            var emittedAudioChunks = 0
            var audioFrameSteps = 0
            var textTokenSteps = 0
            var stepsExecuted = 0
            var consecutiveAudioTailSteps = 0
            var textPhaseFinished = false
            var firstTextMs = -1L
            var firstAudioMs = -1L
            var stopReason = "max_steps"

            for (step in 0 until maxSteps) {
                if (abortRequested.get()) {
                    stopReason = "aborted"
                    break
                }

                val isTextStep = lib.mtmd_helper_gen_audio_next_is_text(genCtx)
                val sampledToken: Int = if (isTextStep) {
                    textTokenSteps++
                    val tok = lib.llama_sampler_sample(samplerChain, lctx, -1)
                    lib.llama_sampler_accept(samplerChain, tok)
                    tok
                } else {
                    audioFrameSteps++
                    consecutiveAudioTailSteps++
                    -1 // LLAMA_TOKEN_NULL während der Audio-Codebook-Schritte im Interleaved-Pattern
                }
                stepsExecuted++

                stopByteRef.value = 0
                hNextRef.value = Pointer.NULL

                val stepRc = lib.mtmd_helper_gen_audio_step_gen(
                    genCtx,
                    sampledToken,
                    hStateMem,
                    hNextRef,
                    stopByteRef
                )
                if (stepRc != 0) {
                    Log.w(tag, "mtmd_helper_gen_audio_step_gen lieferte Code $stepRc bei Schritt $step. Beende Audio-Stream sauber.")
                    onLog?.invoke("PIPELINE WARN: step_gen meldete Code $stepRc bei Schritt $step. Schließe Audio-Stream sauber.")
                    stopReason = "audio_step_err_$stepRc"
                    break
                }

                val isEogToken = isTextStep && sampledToken >= 0 && lib.llama_vocab_is_eog(vocab, sampledToken)
                val shouldStop = stopByteRef.value.toInt() != 0
                var repetitionLoopHit = false

                // A. Falls ein Text-Token generiert wurde, sofort als Untertitel-Stream emittieren.
                // WICHTIG: In MIXED-Mode endet der Text-Stream (<|text_end|>) vor dem Audio-Stream (<|mixed_end|>).
                // Wir brechen bei Text-Ende NICHT vorzeitig ab, damit step_gen die restlichen Audio-Frames des Satzes fertigstellt!
                if (isTextStep && sampledToken >= 0 && !textPhaseFinished) {
                    consecutiveAudioTailSteps = 0
                    if (isEogToken) {
                        textPhaseFinished = true
                    } else if (!shouldStop && !lib.llama_vocab_is_control(vocab, sampledToken)) {
                        val nBytes = lib.llama_token_to_piece(vocab, sampledToken, pieceBuf, pieceBuf.size, 0, false)
                        if (nBytes > 0) {
                            val rawPiece = String(pieceBuf, 0, nBytes.coerceAtMost(pieceBuf.size), Charsets.UTF_8)
                            if (rawPiece.contains("<|text_end|>") || findStopMarkerIndex(rawPiece) != -1) {
                                textPhaseFinished = true
                            } else {
                                val cleanPiece = sanitizeToken(rawPiece)
                                if (cleanPiece.isNotEmpty()) {
                                    rawTextSoFar.append(cleanPiece)
                                    val fullRaw = rawTextSoFar.toString()
                                    if (findStopMarkerIndex(fullRaw) != -1) {
                                        textPhaseFinished = true
                                    } else {
                                        val cleanVisible = stripThinkingAndTags(fullRaw)
                                        if (hasRepetitionLoop(cleanVisible)) {
                                            Log.w(tag, "Wiederholungsschleife in Text erkannt -> Beende Stream sauber")
                                            repetitionLoopHit = true
                                            stopReason = "repetition_loop"
                                        } else if (cleanVisible.length > visibleEmittedSoFar.length) {
                                            if (firstTextMs < 0L) {
                                                firstTextMs = System.currentTimeMillis() - streamStartMs
                                            }
                                            val delta = cleanVisible.substring(visibleEmittedSoFar.length)
                                            visibleEmittedSoFar.append(delta)
                                            emitOutput(OmniStreamOutput.TextToken(delta))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (shouldStop) {
                    stopReason = "native_stop"
                }

                // B. Nächsten Hidden-State (h_next) in hStateMem übernehmen
                val hNextPtr = hNextRef.value
                if (hNextPtr != null && Pointer.nativeValue(hNextPtr) != 0L) {
                    val nextFloats = hNextPtr.getFloatArray(0L, nEmbd)
                    hStateMem.write(0L, nextFloats, 0, nEmbd)
                }

                // C. Sobald LiquidCodec ISTFT-Decoder neue 24 kHz Audio-Samples liefert, SOFORT streamen!
                srRef.value = 24000
                dataPtrRef.value = Pointer.NULL
                dataLenRef.value = 0L
                nSamplesRef.value = 0L

                if (lib.mtmd_helper_gen_audio_get_output(genCtx, srRef, dataPtrRef, dataLenRef, nSamplesRef) == 0) {
                    val nSamples = nSamplesRef.value.toInt()
                    val audioFloatPtr = dataPtrRef.value
                    if (nSamples > 0 && audioFloatPtr != null && Pointer.nativeValue(audioFloatPtr) != 0L) {
                        if (firstAudioMs < 0L) {
                            firstAudioMs = System.currentTimeMillis() - streamStartMs
                        }
                        val pcmBytes = LlamaNativeBridge.float32PointerToPcm16Le(
                            dataPtr = audioFloatPtr,
                            nSamples = nSamples,
                            gain = inferenceConfig.outputVolumeGain.coerceIn(0.5f, 2.5f)
                        )
                        totalAudioSamplesEmitted += nSamples
                        emittedAudioChunks++
                        emitOutput(OmniStreamOutput.AudioChunk(pcmBytes, srRef.value.takeIf { it > 0 } ?: 24000))
                    }
                }

                if (shouldStop || repetitionLoopHit) {
                    break
                }

                // Schutz gegen endlosen Audio-Tail ohne weitere Text-Tokens (~14.4 s Tail nach Text-Ende)
                if (outModeInt == LlamaNativeBridge.OUT_MODE_MIXED &&
                    visibleEmittedSoFar.isNotEmpty() &&
                    consecutiveAudioTailSteps >= 180
                ) {
                    stopReason = "audio_tail_cap"
                    break
                }
            }

            // 10. Streaming-Tail flushen (letzte überlappende ISTFT-Audio-Frames ausgeben)
            if (!abortRequested.get()) {
                val flushRc = lib.mtmd_helper_gen_audio_flush(genCtx)
                if (flushRc == 0) {
                    srRef.value = 24000
                    dataPtrRef.value = Pointer.NULL
                    dataLenRef.value = 0L
                    nSamplesRef.value = 0L
                    if (lib.mtmd_helper_gen_audio_get_output(genCtx, srRef, dataPtrRef, dataLenRef, nSamplesRef) == 0) {
                        val nSamples = nSamplesRef.value.toInt()
                        val audioFloatPtr = dataPtrRef.value
                        if (nSamples > 0 && audioFloatPtr != null && Pointer.nativeValue(audioFloatPtr) != 0L) {
                            if (firstAudioMs < 0L) {
                                firstAudioMs = System.currentTimeMillis() - streamStartMs
                            }
                            val pcmBytes = LlamaNativeBridge.float32PointerToPcm16Le(
                                dataPtr = audioFloatPtr,
                                nSamples = nSamples,
                                gain = inferenceConfig.outputVolumeGain.coerceIn(0.5f, 2.5f)
                            )
                            totalAudioSamplesEmitted += nSamples
                            emittedAudioChunks++
                            emitOutput(OmniStreamOutput.AudioChunk(pcmBytes, srRef.value.takeIf { it > 0 } ?: 24000))
                        }
                    }
                }
            }

            val elapsedMs = (System.currentTimeMillis() - streamStartMs).coerceAtLeast(1L)
            val audioDurationSec = totalAudioSamplesEmitted / 24000.0f
            val msPerAudioFrame = if (audioFrameSteps > 0) elapsedMs.toFloat() / audioFrameSteps else 0f
            val rtf = if (audioDurationSec > 0.05f) (elapsedMs / 1000.0f) / audioDurationSec else 0f
            val statsSummary = "TURN DONE ($stopReason): gen=${elapsedMs}ms (1stTxt=${firstTextMs}ms, 1stAud=${firstAudioMs}ms) | " +
                "steps=$stepsExecuted (txt=$textTokenSteps, audFrames=$audioFrameSteps @ ${String.format(Locale.US, "%.0f", msPerAudioFrame)}ms/Frame) | " +
                "audio=${String.format(Locale.US, "%.2f", audioDurationSec)}s | RTF=${String.format(Locale.US, "%.2f", rtf)}x"
            Log.i(tag, "$statsSummary | Text='${visibleEmittedSoFar}'")
            onLog?.invoke(statsSummary)

            if (!isGreeting && !abortRequested.get() && audioDurationSec >= 1.0f && rtf > 0f) {
                BackendTuner.record(context, activeGpuLayers > 0, "audio", rtf, onLog)
            }

            if (!abortRequested.get() && outModeInt != LlamaNativeBridge.OUT_MODE_TEXT && totalAudioSamplesEmitted == 0L) {
                throw IllegalStateException(
                    "LFM3-Omni hat 0 native Audio-Samples generiert (Text='${visibleEmittedSoFar}'). " +
                        "Überprüfe Projektor-Kompatibilität (mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf) und Diagnose-Logs."
                )
            }
        } finally {
            if (samplerChain != null) {
                try { lib.llama_sampler_free(samplerChain) } catch (_: Throwable) {}
            }
            if (chunksPtr != null) {
                try { lib.mtmd_input_chunks_free(chunksPtr) } catch (_: Throwable) {}
            }
            for (bmp in nativeBitmaps) {
                try { lib.mtmd_bitmap_free(bmp) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Dekodiert ein komprimiertes JPEG-Frame in rohe RGB24-Bytes (3 Bytes pro Pixel: R, G, B)
     * für `mtmd_bitmap_init(nx, ny, rgbData)`.
     */
    private fun decodeJpegToRgb24Bytes(jpegBytes: ByteArray): Triple<Int, Int, ByteArray>? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
            val width = bmp.width
            val height = bmp.height
            if (width <= 0 || height <= 0) {
                bmp.recycle()
                return null
            }
            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            bmp.recycle()

            val rgb = ByteArray(width * height * 3)
            var outIdx = 0
            for (px in pixels) {
                rgb[outIdx] = ((px shr 16) and 0xFF).toByte()
                rgb[outIdx + 1] = ((px shr 8) and 0xFF).toByte()
                rgb[outIdx + 2] = (px and 0xFF).toByte()
                outIdx += 3
            }
            Triple(width, height, rgb)
        } catch (e: Exception) {
            Log.w(tag, "JPEG -> RGB24 Dekodierung fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Baut das offizielle LFM3-Omni ChatML-Präfix auf:
     * - Für `SYSTEM_TTS` / `TEXT`: Reiner Text-System-Prompt ohne Audio-Direktive + `<|im_start|>assistant\n` am Ende
     * - Für `MIXED` / `AUDIO` / `AUTO`: Offizielle LiquidAI Audio/Interleaved-Direktive bis `<|im_end|>\n`
     */
    internal fun buildNativeOmniPrefixPrompt(
        userText: String,
        history: List<OmniMessage>,
        visionCount: Int,
        hasNativeAudio: Boolean,
        inferenceConfig: LfmInferenceConfig
    ): String {
        val sb = StringBuilder()
        val upperMode = inferenceConfig.omniOutputMode.uppercase(Locale.ROOT)
        val isPureTextMode = upperMode == "SYSTEM_TTS" || upperMode == "TEXT"

        val modeHeader = when (upperMode) {
            "SYSTEM_TTS", "TEXT" -> ""
            "AUDIO" -> "Respond with audio."
            else -> "Respond with interleaved text and audio."
        }
        val rawSysPrompt = inferenceConfig.systemPrompt.trim()
            .ifBlank { com.example.model.DEFAULT_SYSTEM_PROMPT }
        // Vermeidet doppeltes Einfügen der offiziellen LiquidAI-Direktive, falls der Nutzer sie manuell eingetragen hat
        val sysPrompt = rawSysPrompt
            .replace("Respond with interleaved text and audio.", "", ignoreCase = true)
            .replace("Respond with audio.", "", ignoreCase = true)
            .trim()

        sb.append("<|startoftext|><|im_start|>system\n")
        if (modeHeader.isNotEmpty()) {
            sb.append(modeHeader)
            if (sysPrompt.isNotEmpty()) {
                sb.append("\n").append(sysPrompt)
            }
        } else {
            sb.append(sysPrompt.ifEmpty { com.example.model.DEFAULT_SYSTEM_PROMPT })
        }
        if (visionCount > 0) {
            sb.append("\nBeachte die beigefügten Kamerabilder nur, wenn sie relevant sind.")
        }
        sb.append("<|im_end|>\n")

        // Strikte (User -> Assistant) Turn-Paar-Bildung für den Gesprächsverlauf:
        // 1. Keine isolierte Assistant-Begrüßung (isGreeting) am Anfang (verhindert "system -> assistant -> user").
        // 2. Keine historischen <__media__> Tags (weder alte Audios noch alte Bilder werden jemals erneut gesendet).
        // 3. Echte Hintergrund-Transkripte von Sprachaufnahmen werden direkt als kompakter Text-Turn genutzt.
        val filteredHistory = history
            .filter { !it.isError && !it.isGreeting && it.text.isNotBlank() }
            .let { list ->
                if (list.lastOrNull()?.isUser == true) {
                    list.dropLast(1)
                } else {
                    list
                }
            }

        val completePairs = mutableListOf<Pair<OmniMessage, OmniMessage>>()
        var idx = 0
        while (idx < filteredHistory.size) {
            val current = filteredHistory[idx]
            if (current.isUser) {
                var latestUser = current
                var nextIdx = idx + 1
                while (nextIdx < filteredHistory.size && filteredHistory[nextIdx].isUser) {
                    latestUser = filteredHistory[nextIdx]
                    nextIdx++
                }
                if (nextIdx < filteredHistory.size && !filteredHistory[nextIdx].isUser) {
                    completePairs.add(latestUser to filteredHistory[nextIdx])
                    idx = nextIdx + 1
                    continue
                }
            }
            idx++
        }

        val maxHistoryPairs = 2
        for ((userMsg, assistantMsg) in completePairs.takeLast(maxHistoryPairs)) {
            val rawUserClean = stripThinkingAndTags(userMsg.text)
                .replace("<__media__>", "")
                .trim()
            val rawAssistantClean = stripThinkingAndTags(assistantMsg.text)
                .replace("<__media__>", "")
                .trim()
            if (rawUserClean.isBlank() || rawAssistantClean.isBlank()) continue

            val isUntranscribedPlaceholder =
                rawUserClean.startsWith("Spracheingabe (") || !userMsg.isTranscriptResolved
            val userTurnText = if (isUntranscribedPlaceholder) {
                "(Gesprochene Frage auf Deutsch)"
            } else {
                rawUserClean
            }

            sb.append("<|im_start|>user\n")
            sb.append(userTurnText).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
            sb.append(rawAssistantClean).append("<|im_end|>\n")
        }

        // Aktueller Turn: NUR die Medien (Kamera-Frame / 16kHz Audio) der aktuellen Nachricht!
        sb.append("<|im_start|>user\n")
        for (i in 0 until visionCount) {
            sb.append("<__media__>\n")
        }
        if (hasNativeAudio) {
            sb.append("<__media__>\n")
        }
        if (userText.isNotBlank()) {
            sb.append(userText.trim())
        }
        sb.append("<|im_end|>\n")

        if (isPureTextMode) {
            sb.append("<|im_start|>assistant\n")
        }
        return sb.toString()
    }

    private fun stripThinkingAndTags(raw: String): String {
        var s = raw
        while (true) {
            val openIdx = s.indexOf("<think>", ignoreCase = true)
            if (openIdx == -1) break
            val closeIdx = s.indexOf("</think>", openIdx + 7, ignoreCase = true)
            if (closeIdx != -1) {
                s = s.removeRange(openIdx, closeIdx + 8)
            } else {
                s = s.substring(0, openIdx)
                break
            }
        }
        s = s.replace("</think>", "", ignoreCase = true)
            .replace("<think>", "", ignoreCase = true)
            .replace("<|think|>", "", ignoreCase = true)
            .replace("</|think|>", "", ignoreCase = true)
            .replace("<|text_start|>", "", ignoreCase = true)
            .replace("<|text_end|>", "", ignoreCase = true)
            .replace("<|audio_start|>", "", ignoreCase = true)
            .replace("<|audio_end|>", "", ignoreCase = true)
            .replace("<|mixed_start|>", "", ignoreCase = true)
            .replace("<|mixed_end|>", "", ignoreCase = true)

        val trailingTagStart = s.lastIndexOf('<')
        if (trailingTagStart != -1 && trailingTagStart >= s.length - 14) {
            val tail = s.substring(trailingTagStart).lowercase(Locale.ROOT)
            if ("<think>".startsWith(tail) ||
                "</think>".startsWith(tail) ||
                "<|im_end|>".startsWith(tail) ||
                "<|text_end|>".startsWith(tail) ||
                "<|mixed_end|>".startsWith(tail)
            ) {
                s = s.substring(0, trailingTagStart)
            }
        }
        return s.trimStart()
    }

    private fun sanitizeToken(rawToken: String): String {
        var cleaned = rawToken
        val markers = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|endoftext|>",
            "<|end_of_text|>",
            "<|startoftext|>",
            "<|text_start|>",
            "<|text_end|>",
            "<|audio_start|>",
            "<|audio_end|>",
            "<|mixed_start|>",
            "<|mixed_end|>",
            "</s>",
            "<|assistant|>",
            "<|user|>"
        )
        for (m in markers) {
            cleaned = cleaned.replace(m, "")
        }
        return cleaned
    }

    private fun findStopMarkerIndex(text: String): Int {
        val markers = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|endoftext|>",
            "<|end_of_text|>",
            "<|mixed_end|>",
            "\nUser:",
            "\nNutzer:",
            "\nSystem:"
        )
        for (m in markers) {
            val idx = text.indexOf(m)
            if (idx != -1) return idx
        }
        return -1
    }

    private fun hasRepetitionLoop(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.length < 56) return false
        for (windowLen in listOf(26, 36, 48)) {
            if (normalized.length >= windowLen * 2) {
                val tail = normalized.substring(normalized.length - windowLen)
                val prefix = normalized.substring(0, normalized.length - windowLen)
                if (prefix.contains(tail, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private data class GgufParsedInfo(
        val architecture: String = "",
        val name: String = "",
        val tensorCount: Long = 0,
        val kvCount: Long = 0,
        val hasVisionEncoder: Boolean = true,
        val hasAudioEncoder: Boolean = false,
        val hasAudioDecoder: Boolean = false
    )

    private fun inspectGgufMetadata(file: File, onLog: ((String) -> Unit)?, prefix: String): GgufParsedInfo {
        var arch = ""
        var name = ""
        var tensorCount = 0L
        var metadataCount = 0L
        var hasVision = prefix == "PROJEKTOR"
        var hasAudio = false
        var hasAudioDec = false

        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                raf.readFully(header.array())
                val magic = header.getInt(0)
                val version = header.getInt(4)
                tensorCount = header.getLong(8)
                metadataCount = header.getLong(16)

                val magicHex = "0x" + Integer.toHexString(magic).uppercase()
                val magicStr = if (magic == 0x46554747) "GGUF (Gültig)" else "Ungültig ($magicHex)"
                onLog?.invoke("[$prefix] Header: Magic=$magicStr | v$version | Tensoren=$tensorCount | KV-Einträge=$metadataCount")

                if (magic != 0x46554747) {
                    onLog?.invoke("[$prefix] WARNUNG: Datei ist kein gültiges GGUF-Format!")
                    return GgufParsedInfo()
                }

                if (prefix == "PROJEKTOR" && tensorCount >= 450L) {
                    hasAudio = true
                    hasAudioDec = true
                }

                val maxScan = metadataCount.coerceAtMost(512L).toInt()
                for (i in 0 until maxScan) {
                    val key = readGgufString(raf) ?: break
                    val valType = readUint32(raf)
                    val value = readGgufValueAsString(raf, valType)

                    when {
                        key == "general.architecture" -> {
                            arch = value ?: ""
                            onLog?.invoke("[$prefix] general.architecture = '$arch'")
                        }
                        key == "general.name" -> {
                            name = value ?: ""
                            onLog?.invoke("[$prefix] general.name = '$name'")
                        }
                        key == "clip.has_vision_encoder" -> {
                            hasVision = value?.toBooleanStrictOrNull() ?: true
                            onLog?.invoke("[$prefix] clip.has_vision_encoder = $hasVision")
                        }
                        key == "clip.has_audio_encoder" -> {
                            hasAudio = value?.toBooleanStrictOrNull() ?: hasAudio
                            onLog?.invoke("[$prefix] clip.has_audio_encoder = $hasAudio")
                        }
                        key == "clip.has_gen_audio_encoder" ||
                            key == "clip.has_audio_decoder" -> {
                            if (value?.toBooleanStrictOrNull() == true) {
                                hasAudioDec = true
                            }
                            onLog?.invoke("[$prefix] $key = '$value'")
                        }
                        key.startsWith("clip.audio.decoder") ||
                            key.startsWith("clip.audio.depthformer") ||
                            key.startsWith("codec.") ||
                            key.contains("lfm3a_gen") -> {
                            hasAudioDec = true
                            onLog?.invoke("[$prefix] $key = '$value'")
                        }
                        key == "clip.projector_type" || key == "clip.vision.image_size" || key == "clip.vision.patch_size" -> {
                            onLog?.invoke("[$prefix] $key = '$value'")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("[$prefix] GGUF-Scan Lesefehler: ${e.message}")
        }

        return GgufParsedInfo(
            architecture = arch,
            name = name,
            tensorCount = tensorCount,
            kvCount = metadataCount,
            hasVisionEncoder = hasVision,
            hasAudioEncoder = hasAudio,
            hasAudioDecoder = hasAudioDec
        )
    }

    private fun captureLogcatNativeLogs(onLog: ((String) -> Unit)?) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "80", "-v", "tag"))
            val lines = process.inputStream.bufferedReader().readLines()
            val relevant = lines.filter { line ->
                line.contains("llama", ignoreCase = true) ||
                    line.contains("ggml", ignoreCase = true) ||
                    line.contains("mtmd", ignoreCase = true) ||
                    line.contains("clip", ignoreCase = true) ||
                    line.contains("vulkan", ignoreCase = true) ||
                    line.contains("lfm", ignoreCase = true) ||
                    line.contains("error", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true)
            }
            if (relevant.isNotEmpty()) {
                onLog?.invoke("--- C++ NATIVE LOGCAT AUSZUG (${relevant.size} Treffer) ---")
                for (l in relevant.takeLast(20)) {
                    onLog?.invoke("  [C++] ${l.trim()}")
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("WARNUNG: Logcat konnte nicht ausgelesen werden: ${e.message}")
        }
    }

    private fun readUint32(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readUint64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readGgufString(raf: RandomAccessFile): String? {
        val len = readUint64(raf)
        if (len < 0 || len > 2048) return null
        val bytes = ByteArray(len.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipGgufValue(raf: RandomAccessFile, valType: Int) {
        when (valType) {
            0, 1, 7 -> raf.skipBytes(1)
            2, 3 -> raf.skipBytes(2)
            4, 5, 6 -> raf.skipBytes(4)
            10, 11, 12 -> raf.skipBytes(8)
            8 -> {
                val len = readUint64(raf)
                if (len in 0..8192) raf.skipBytes(len.toInt())
            }
            9 -> {
                val elemType = readUint32(raf)
                val elemCount = readUint64(raf)
                for (i in 0 until elemCount.coerceAtMost(5000L).toInt()) {
                    skipGgufValue(raf, elemType)
                }
            }
        }
    }

    private fun readGgufValueAsString(raf: RandomAccessFile, valType: Int): String? {
        return when (valType) {
            0 -> raf.readUnsignedByte().toString()
            1 -> raf.readByte().toString()
            2 -> (ByteBuffer.wrap(ByteArray(2).also { raf.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF).toString()
            3 -> ByteBuffer.wrap(ByteArray(2).also { raf.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).short.toString()
            4 -> (readUint32(raf).toLong() and 0xFFFFFFFFL).toString()
            5 -> readUint32(raf).toString()
            6 -> ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).float.toString()
            7 -> (raf.readByte() != 0.toByte()).toString()
            8 -> readGgufString(raf)
            10, 11 -> readUint64(raf).toString()
            12 -> ByteBuffer.wrap(ByteArray(8).also { raf.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).double.toString()
            9 -> {
                val elemType = readUint32(raf)
                val elemCount = readUint64(raf)
                val list = mutableListOf<String>()
                for (i in 0 until elemCount.toInt()) {
                    if (i < 2) {
                        val v = readGgufValueAsString(raf, elemType)
                        if (v != null) list.add(v)
                    } else {
                        skipGgufValue(raf, elemType)
                    }
                }
                "Array[$elemCount] (${list.joinToString(", ")})"
            }
            else -> null
        }
    }

    suspend fun unloadModel() {
        stopCurrentGeneration()
        engineMutex.withLock {
            inferenceMutex.withLock {
                try {
                    freeNativeAudioGenPipeline()
                    val handle = omniHandle
                    omniHandle = null
                    if (handle != null) {
                        LlamaNativeBridge.llamaLib?.omni_free(handle)
                    }
                    isModelLoaded = false
                    projectorHasAudioGen = false
                    activeModelPath = null
                    activeProjectorPath = null
                } catch (e: Exception) {
                    Log.w(tag, "Fehler beim Entladen", e)
                }
            }
        }
    }

    fun release() {
        stopCurrentGeneration()
        kotlinx.coroutines.runBlocking {
            unloadModel()
        }
    }
}
