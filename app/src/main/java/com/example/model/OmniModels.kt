package com.example.model

import android.graphics.Bitmap
import com.example.engine.ComputeBackendMode
import com.example.hardware.DeviceHardwareInfo
import com.example.vision.VisionStreamTelemetry

enum class OmniCallState {
  IDLE,
  CONNECTING,
  CONNECTED
}

fun formatCallDuration(seconds: Long): String {
  val minutes = seconds / 60
  val remSeconds = seconds % 60
  return String.format(java.util.Locale.US, "%02d:%02d", minutes, remSeconds)
}

enum class OmniStatus {
  IDLE,
  LISTENING,
  THINKING,
  SPEAKING,
  LOADING,
  ERROR
}

enum class LfmExecutionPhase {
  OFFLINE,         // Phase 0: Model weights not loaded or missing
  MAPPING_WEIGHTS, // Phase 1: GGUF direct memory mapping & tensor validation
  READY_IDLE,      // Phase 2: State-space buffers allocated, ready for input
  CONV_PREFILL,    // Phase 3: Gated Conv1D state ingestion & multimodal projection
  OMNI_DECODE,     // Phase 4: Autoregressive token & 24kHz audio head decoding
  FAULT            // Phase 5: Hardware or file error
}

enum class CameraDisplayMode {
  PIP,
  FULLSCREEN,
  HIDDEN
}

enum class CameraLens {
  FRONT,
  BACK
}

data class LfmModelArchitecture(
  val modelFamily: String = "Liquid Foundation Models (LFM3-Omni Abliter8)",
  val modelName: String = "LFM3-Omni-2B-Abliter8",
  val totalLayers: Int = 32,
  val conv1dLayers: Int = 16,
  val swaLayers: Int = 8,
  val globalAttentionLayers: Int = 8,
  val hiddenDimension: Int = 2048,
  val intermediateDimension: Int = 8192,
  val convKernelSize: Int = 4,
  val contextWindowTokens: Int = 4096,
  val vocabularySize: Int = 100352,
  val audioSampleRateHz: Int = 24000,
  val nativeModality: String = "Speech-to-Speech Omni (Text + Vision + 16kHz Audio In -> Text & 24kHz Audio Out)"
)

const val DEFAULT_SYSTEM_PROMPT =
  "Antworte immer auf Deutsch, kurz und natürlich in 1 bis 2 gesprochenen Sätzen."

const val DEFAULT_GREETING_PROMPT =
  "Begrüße mich ganz kurz und freundlich auf Deutsch."

data class LfmInferenceConfig(
  val temperature: Float = 0.0f,
  val topP: Float = 0.90f,
  val repetitionPenalty: Float = 1.05f,
  val cpuThreads: Int = 6,
  /**
   * Rechen-Backend-Wunsch. AUTO entscheidet anhand der echten Geräte-Tabelle der
   * Engine; GPU meldet einen Fehler statt still auf CPU zurückzufallen; CPU erzwingt
   * n_gpu_layers = 0 (z. B. um instabilen Vulkan-Treiber zu umgehen).
   */
  val backendMode: ComputeBackendMode = ComputeBackendMode.AUTO,
  /** Gewünschte Auslagerungstiefe bei [backendMode] = GPU. 0 bedeutet "alle Schichten". */
  val gpuLayers: Int = 99,
  val maxNewTokens: Int = 128,
  val enableThinking: Boolean = false,
  val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
  val greetingPrompt: String = DEFAULT_GREETING_PROMPT,
  // Sprachausgabe-Modus: "SYSTEM_TTS" (Nur Text vom Modell + satzweise Android System-TTS),
  // "MIXED" (Nativ: Interleaved Text+24kHz Audio), "AUDIO" (Nativ: Nur 24kHz Audio), "AUTO"
  val omniVoiceGender: String = "female",
  val omniOutputMode: String = "SYSTEM_TTS",
  val audioTemperature: Float = 0.40f,
  val audioRepetitionPenalty: Float = 1.20f,
  val outputVolumeGain: Float = 1.15f,
  val speechRate: Float = 1.0f,
  val speechPitch: Float = 1.0f,
  val selectedVoiceName: String = "female",
  // Mikrofon- & Spracherkennungs-Optionen
  val micGain: Float = 2.5f,
  val vadSensitivity: Float = 0.045f,
  val silenceTimeoutMs: Long = 1100L,
  // Live-Video / Vision-Stream-Optionen
  val visionFps: Float = 2.0f,
  val visionKeyframesCount: Int = 1,
  val visionResolution: Int = 384,
  val autoStartCameraOnCall: Boolean = false,
  val enableSentenceSplitting: Boolean = true
)

data class OmniMessage(
  val id: String = System.currentTimeMillis().toString(),
  val isUser: Boolean,
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  val snapshotBitmap: Bitmap? = null,
  val videoFramesCount: Int = 0,
  val videoWindowSeconds: Float = 0f,
  val modelLatencyMs: Long = 0L,
  val generatedAudioPcm: ByteArray? = null,
  val generatedAudioSampleRate: Int = 24000,
  val isError: Boolean = false,
  val isVoiceInput: Boolean = false,
  val isTranscriptResolved: Boolean = !isVoiceInput,
  val isGreeting: Boolean = false
)

data class ModelStorageStatus(
  val isModelLoaded: Boolean = false,
  val isModelFilePresent: Boolean = false,
  val isProjectorPresent: Boolean = false,
  val modelFileName: String? = null,
  val modelFileSizeMb: Float = 0f,
  val isProjectorLoaded: Boolean = false,
  val projectorFileSizeMb: Float = 0f,
  val projectorHasVision: Boolean = true,
  val projectorHasAudio: Boolean = false,
  val projectorHasAudioGen: Boolean = false,
  val activeContextTokens: Int = 4096,
  val tensorCount: Int = 0,
  val ggufArchitecture: String = "lfm3",
  val statusDescription: String = "Überprüfe Gerätespeicher...",
  val initStepText: String = "",
  val initProgressFraction: Float = 0f
)

data class DownloadProgressInfo(
  val downloadId: Long = -1L,
  val fileName: String = "",
  val downloadedBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val speedBytesPerSec: Long = 0L,
  val etaSeconds: Long = 0L,
  val isDownloading: Boolean = false,
  val isCompleted: Boolean = false,
  val isFailed: Boolean = false,
  val statusMessage: String = ""
) {
  val progressFraction: Float
    get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

  val downloadedMb: Float
    get() = downloadedBytes / (1024f * 1024f)

  val totalMb: Float
    get() = totalBytes / (1024f * 1024f)

  val speedMbPerSec: Float
    get() = speedBytesPerSec / (1024f * 1024f)
}

data class OmniUiState(
  val callState: OmniCallState = OmniCallState.IDLE,
  val callDurationSeconds: Long = 0L,
  val status: OmniStatus = OmniStatus.IDLE,
  val executionPhase: LfmExecutionPhase = LfmExecutionPhase.OFFLINE,
  val isCallActive: Boolean = false,
  val isMuted: Boolean = false,
  val isSpeechValid: Boolean = false,
  val userVoiceRms: Float = 0.0f,
  val userWaveform: List<Float> = emptyList(),
  val userLiveTranscript: String = "",
  val lastUserTranscript: String = "",
  val modelStorageStatus: ModelStorageStatus = ModelStorageStatus(),
  val architecture: LfmModelArchitecture = LfmModelArchitecture(),
  val inferenceConfig: LfmInferenceConfig = LfmInferenceConfig(),
  val availableVoices: List<String> = emptyList(),
  val activeSystemTtsEngine: String = "Android System-Standard",
  val hardwareInfo: DeviceHardwareInfo? = null,
  /**
   * Textueller Backend-Bericht der nativen Engine (Gemäßemtabelle, Entscheidung,
   * Begründung, letzte native Log-Zeilen). Leer, solange noch nicht gemessen wurde.
   */
  val backendReport: String = "",
  val localModelFiles: List<LocalModelFile> = emptyList(),
  val activeDownloads: Map<String, DownloadProgressInfo> = emptyMap(),
  val isCameraActive: Boolean = false,
  val cameraLens: CameraLens = CameraLens.BACK,
  val isTorchOn: Boolean = false,
  val torchLevel: Float = 0.8f,
  val maxTorchLevel: Int = 1,
  val cameraDisplayMode: CameraDisplayMode = CameraDisplayMode.PIP,
  val visionTelemetry: VisionStreamTelemetry = VisionStreamTelemetry(),
  val currentPartialTranscript: String = "",
  val streamingAiResponse: String = "",
  val lastAiResponse: String = "",
  val spokenCharCount: Int = 0,
  val audioRms: Float = 0.0f,
  val latencyMs: Long = 0L,
  val messages: List<OmniMessage> = emptyList(),
  val isAnalyzingFrame: Boolean = false,
  val errorMessage: String? = null,
  val diagnosticLogs: List<String> = emptyList(),
  val showDiagnosticsSheet: Boolean = false
)
