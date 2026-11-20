package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AsyncHistoryTranscriber
import com.example.audio.FullDuplexAudioController
import com.example.audio.OmniAudioPlayer
import com.example.engine.Lfm3OmniNativeEngine
import com.example.engine.NativeBackendProbe
import com.example.engine.NativeLogSink
import com.example.engine.OmniStreamOutput
import com.example.hardware.HardwareProfiler
import com.example.model.CameraLens
import com.example.model.DownloadProgressInfo
import com.example.model.GgufModelCatalog
import com.example.model.GgufModelVariant
import com.example.model.LfmExecutionPhase
import com.example.model.LfmInferenceConfig
import com.example.model.LocalModelFile
import com.example.model.OmniCallState
import com.example.model.OmniMessage
import com.example.model.OmniStatus
import com.example.model.OmniUiState
import com.example.vision.CameraVisionSampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class OmniViewModel(application: Application) : AndroidViewModel(application) {
  private val tag = "OmniViewModel"

  private val _uiState = MutableStateFlow(OmniUiState())
  val uiState: StateFlow<OmniUiState> = _uiState.asStateFlow()

  private val fullDuplexAudio = FullDuplexAudioController(application)
  private val audioPlayer = OmniAudioPlayer(application)
  private val asyncHistoryTranscriber = AsyncHistoryTranscriber(application)
  private val visionSampler = CameraVisionSampler(
    sampleIntervalMs = 500L,
    targetDimension = 384,
    maxRingBufferFrames = 12,
    maxKeyframesPerTurn = 1
  )
  private val nativeEngine = Lfm3OmniNativeEngine(application)

  private var activeInferenceJob: Job? = null
  private var callTimerJob: Job? = null
  private var initializationJob: Job? = null
  private var sessionResetJob: Job? = null
  private val directDownloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

  @Volatile
  private var lastGeneratedAudioPcm: ByteArray? = null
  @Volatile
  private var lastGeneratedSampleRate: Int = 24000
  @Volatile
  private var activeCallSessionId: Long = 0L

  init {
    initPipeline()
  }

  private fun initPipeline() {
    val hw = HardwareProfiler.getHardwareInfo(getApplication())
    val models = GgufModelCatalog.scanLocalModels(getApplication())

    _uiState.update {
      it.copy(
        hardwareInfo = hw,
        localModelFiles = models,
        cameraLens = CameraLens.BACK,
        executionPhase = LfmExecutionPhase.OFFLINE
      )
    }

    audioPlayer.onSpeechCompleted = {
      fullDuplexAudio.isAssistantSpeaking = false
      if (activeInferenceJob?.isActive != true) {
        _uiState.update {
          it.copy(
            status = if (it.isCallActive) OmniStatus.LISTENING else OmniStatus.IDLE,
            executionPhase = if (it.modelStorageStatus.isModelLoaded) LfmExecutionPhase.READY_IDLE else LfmExecutionPhase.OFFLINE,
            audioRms = 0f
          )
        }
      }
    }

    viewModelScope.launch {
      audioPlayer.audioAmplitude.collect { amp ->
        if (_uiState.value.status == OmniStatus.SPEAKING || audioPlayer.isSpeaking.value) {
          _uiState.update { it.copy(audioRms = amp) }
        }
      }
    }

    viewModelScope.launch {
      audioPlayer.isSpeaking.collect { speaking ->
        fullDuplexAudio.isAssistantSpeaking = speaking
      }
    }

    viewModelScope.launch {
      audioPlayer.spokenCharCount.collect { charCount ->
        _uiState.update { it.copy(spokenCharCount = charCount) }
      }
    }

    viewModelScope.launch {
      audioPlayer.availableVoices.collect { voices ->
        _uiState.update { it.copy(availableVoices = voices) }
      }
    }

    viewModelScope.launch {
      audioPlayer.activeSystemTtsEngine.collect { engineLabel ->
        _uiState.update { it.copy(activeSystemTtsEngine = engineLabel) }
      }
    }

    viewModelScope.launch {
      visionSampler.telemetry.collect { telemetry ->
        _uiState.update { it.copy(visionTelemetry = telemetry) }
      }
    }

    // Echte 16 kHz Mikrofon-Pegel & Wellenform direkt vom FullDuplexAudioController
    viewModelScope.launch {
      fullDuplexAudio.userVoiceRms.collect { rms ->
        val thresh = _uiState.value.inferenceConfig.vadSensitivity
        _uiState.update {
          it.copy(
            userVoiceRms = rms,
            isSpeechValid = rms >= thresh,
            audioRms = if (it.status == OmniStatus.LISTENING) rms else it.audioRms
          )
        }
      }
    }

    viewModelScope.launch {
      fullDuplexAudio.realWaveform.collect { wf ->
        _uiState.update { it.copy(userWaveform = wf) }
      }
    }

    // Sobald VAD eine abgeschlossene 16 kHz Sprach-Äußerung des Nutzers erfasst hat,
    // wird sie direkt als rohes 16 kHz Audio an LFM3-Omni übergeben (ohne Zwischen-STT).
    viewModelScope.launch {
      fullDuplexAudio.completedUtteranceFlow.collect { pcmBytes ->
        val state = _uiState.value
        if (state.isCallActive && !state.isMuted) {
          if (state.status != OmniStatus.THINKING && state.status != OmniStatus.LOADING) {
            handleVadSpeechFinished(pcmBytes)
          }
        }
      }
    }

    refreshModelStorageStatus()
  }

  fun refreshModelStorageStatus() {
    viewModelScope.launch {
      val status = nativeEngine.checkLocalModelStatus()
      val models = GgufModelCatalog.scanLocalModels(getApplication())
      _uiState.update {
        it.copy(
          modelStorageStatus = status,
          localModelFiles = models,
          executionPhase = if (status.isModelLoaded) LfmExecutionPhase.READY_IDLE else LfmExecutionPhase.OFFLINE,
          errorMessage = null
        )
      }
    }
  }

  /**
   * Veröffentlicht den Backend-Bericht der nativen Engine im UI-State
   * (Gemessenegerätetabelle + Entscheidung + native Log-Zeilen).
   */
  private fun publishBackendReport() {
    val report = try {
      nativeEngine.backendReport()
    } catch (e: Throwable) {
      "Backend-Bericht nicht verfügbar: ${e.message}"
    }
    _uiState.update { it.copy(backendReport = report) }
  }

  /**
   * Misst die Rechen-Backends neu, ohne das Modell zu entladen. Dieselbe Messung wie
   * im Ladepfad, damit das Einstellungspanel und die Engine sich nicht widersprechen.
   */
  fun remeasureBackends() {
    logDiagnostic("BACKEND REMESSEN: frage GGML-Geräte und Treiber erneut ab ...")
    viewModelScope.launch {
      try {
        val libDir = getApplication<Application>().applicationInfo.nativeLibraryDir
        NativeBackendProbe.probe(libDir, forceReread = true, onLog = { logDiagnostic(it) })
      } catch (e: Throwable) {
        logDiagnostic("BACKEND REMESSEN FEHLGESCHLAGEN: ${e.javaClass.simpleName}: ${e.message}")
      }
      publishBackendReport()
    }
  }

  fun logDiagnostic(text: String) {
    val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
    val entry = "[$timestamp] $text"
    _uiState.update { it.copy(diagnosticLogs = it.diagnosticLogs + entry) }
    Log.i("OmniDiagnostics", entry)
  }

  fun toggleDiagnosticsSheet() {
    _uiState.update { it.copy(showDiagnosticsSheet = !it.showDiagnosticsSheet) }
  }

  fun initializeModel() {
    if (_uiState.value.modelStorageStatus.isModelLoaded && nativeEngine.isReady()) return
    if (initializationJob?.isActive == true) {
      logDiagnostic("INIT: Initialisierung läuft bereits...")
      _uiState.update { it.copy(showDiagnosticsSheet = true) }
      return
    }
    _uiState.update { it.copy(diagnosticLogs = emptyList(), showDiagnosticsSheet = true) }
    logDiagnostic("INIT START: Starte LFM3-Omni Speech-to-Speech Initialisierung...")
    initializationJob = viewModelScope.launch {
      try {
        _uiState.update {
          it.copy(
            status = OmniStatus.LOADING,
            executionPhase = LfmExecutionPhase.MAPPING_WEIGHTS,
            errorMessage = null
          )
        }
        nativeEngine.loadModel(
          config = _uiState.value.inferenceConfig,
          onLog = { logDiagnostic(it) },
          onProgress = { stepText, fraction ->
            logDiagnostic("INIT STEP: $stepText (${(fraction * 100).toInt()}%)")
            _uiState.update { state ->
              state.copy(
                modelStorageStatus = state.modelStorageStatus.copy(
                  initStepText = stepText,
                  initProgressFraction = fraction
                )
              )
            }
          }
        )
        logDiagnostic("INIT SUCCESS: Native LFM3-Omni Speech-to-Speech Engine bereit!")
        publishBackendReport()
        _uiState.update { it.copy(showDiagnosticsSheet = false) }
        refreshModelStorageStatus()
      } catch (e: CancellationException) {
        logDiagnostic("INIT: Initialisierung wurde abgebrochen.")
        _uiState.update { it.copy(showDiagnosticsSheet = false) }
      } catch (e: Exception) {
        val nativePart = NativeLogSink.lastProblem()
        val errText = if (nativePart.isNullOrBlank()) {
          e.message ?: "Initialisierungsfehler"
        } else {
          "${e.message ?: "Initialisierungsfehler"} | native: $nativePart"
        }
        logDiagnostic("INIT FAIL: $errText")
        publishBackendReport()

        _uiState.update {
          it.copy(
            status = OmniStatus.ERROR,
            executionPhase = LfmExecutionPhase.FAULT,
            errorMessage = errText,
            showDiagnosticsSheet = true
          )
        }
      }
    }
  }

  fun downloadModel(variant: GgufModelVariant) {
    viewModelScope.launch {
      val models = GgufModelCatalog.scanLocalModels(getApplication())
      val existing = models.firstOrNull {
        it.name.equals(variant.fileName, ignoreCase = true) ||
          it.file.name.equals(variant.fileName, ignoreCase = true)
      }

      if (existing != null && existing.isComplete && existing.isValidGguf) {
        _uiState.update { it.copy(errorMessage = "Modell ${variant.fileName} ist bereits vollständig vorhanden.") }
        return@launch
      }

      if (existing != null) {
        logDiagnostic("DOWNLOAD: Entferne unvollständige Datei '${existing.name}' (${String.format(Locale.US, "%.1f", existing.sizeMb)} MB)...")
        try {
          existing.file.delete()
        } catch (e: Exception) {
          Log.w(tag, "Konnte alte Datei vor Download nicht entfernen: ${e.message}")
        }
      }

      logDiagnostic("DOWNLOAD: Starte Download für '${variant.fileName}' (${variant.sizeFormatted}) von ${variant.downloadUrl}...")

      try {
        val downloadId = GgufModelCatalog.enqueueDownload(getApplication(), variant)
        if (downloadId != -1L) {
          monitorDownloadProgress(variant, downloadId)
        } else {
          logDiagnostic("DOWNLOAD: DownloadManager nicht verfügbar -> Starte OkHttp Direktdownload...")
          startDirectDownload(variant)
        }
      } catch (e: Exception) {
        logDiagnostic("DOWNLOAD: Fehler bei DownloadManager: ${e.message} -> Wechsle zu Direktdownload")
        startDirectDownload(variant)
      }
    }
  }

  private fun startDirectDownload(variant: GgufModelVariant) {
    val job = viewModelScope.launch {
      val initialInfo = DownloadProgressInfo(
        downloadId = -999L,
        fileName = variant.fileName,
        totalBytes = variant.estimatedSizeMb.toLong() * 1024L * 1024L,
        isDownloading = true,
        statusMessage = "Starte Direktdownload..."
      )
      _uiState.update { state ->
        state.copy(activeDownloads = state.activeDownloads + (variant.fileName to initialInfo))
      }

      try {
        GgufModelCatalog.downloadDirectly(getApplication(), variant) { progress ->
          _uiState.update { state ->
            state.copy(activeDownloads = state.activeDownloads + (variant.fileName to progress))
          }
        }
        _uiState.update { state -> state.copy(activeDownloads = state.activeDownloads - variant.fileName) }
        refreshModelStorageStatus()
      } catch (e: Exception) {
        val err = e.localizedMessage ?: "Direktdownload fehlgeschlagen"
        Log.e(tag, "Direktdownload fehlgeschlagen für ${variant.fileName}", e)
        _uiState.update { state ->
          state.copy(
            activeDownloads = state.activeDownloads - variant.fileName,
            errorMessage = "Download fehlgeschlagen (${variant.displayName}): $err"
          )
        }
      } finally {
        directDownloadJobs.remove(variant.fileName)
      }
    }
    directDownloadJobs[variant.fileName] = job
  }

  fun cancelDownload(fileName: String) {
    val progress = _uiState.value.activeDownloads[fileName]
    if (progress != null && progress.downloadId > 0) {
      GgufModelCatalog.cancelDownload(getApplication(), progress.downloadId)
    }
    directDownloadJobs[fileName]?.cancel()
    directDownloadJobs.remove(fileName)
    _uiState.update { state ->
      state.copy(activeDownloads = state.activeDownloads - fileName)
    }
  }

  fun deleteModel(model: LocalModelFile) {
    viewModelScope.launch {
      val success = GgufModelCatalog.deleteLocalModel(model)
      if (success) {
        refreshModelStorageStatus()
      } else {
        _uiState.update { it.copy(errorMessage = "Löschen von ${model.name} fehlgeschlagen.") }
      }
    }
  }

  private fun monitorDownloadProgress(variant: GgufModelVariant, downloadId: Long) {
    viewModelScope.launch {
      var currentSpeedBytesPerSec = 0L
      var lastDownloadedBytes = 0L
      var lastTimeMs = System.currentTimeMillis()

      val initialInfo = DownloadProgressInfo(
        downloadId = downloadId,
        fileName = variant.fileName,
        totalBytes = variant.estimatedSizeMb.toLong() * 1024L * 1024L,
        isDownloading = true,
        statusMessage = "Starte Download..."
      )
      _uiState.update { state ->
        state.copy(activeDownloads = state.activeDownloads + (variant.fileName to initialInfo))
      }

      while (coroutineContext[Job]?.isActive == true) {
        delay(350L)

        val progress = GgufModelCatalog.queryDownloadStatus(
          context = getApplication(),
          downloadId = downloadId,
          fileName = variant.fileName,
          estimatedSizeMb = variant.estimatedSizeMb,
          previousDownloadedBytes = lastDownloadedBytes,
          previousTimeMs = lastTimeMs
        )

        val nowMs = System.currentTimeMillis()
        val dtSec = (nowMs - lastTimeMs) / 1000.0
        if (dtSec > 0) {
          val instantSpeed = ((progress.downloadedBytes - lastDownloadedBytes).coerceAtLeast(0L) / dtSec).toLong()
          currentSpeedBytesPerSec = if (currentSpeedBytesPerSec == 0L) instantSpeed else (0.7 * currentSpeedBytesPerSec + 0.3 * instantSpeed).toLong()
        }

        val remainingBytes = (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0L)
        val smoothedEta = if (currentSpeedBytesPerSec > 0) remainingBytes / currentSpeedBytesPerSec else 0L

        val smoothedProgress = progress.copy(
          speedBytesPerSec = currentSpeedBytesPerSec,
          etaSeconds = smoothedEta
        )

        lastDownloadedBytes = progress.downloadedBytes
        lastTimeMs = nowMs

        _uiState.update { state ->
          state.copy(activeDownloads = state.activeDownloads + (variant.fileName to smoothedProgress))
        }

        if (progress.isCompleted) {
          _uiState.update { state -> state.copy(activeDownloads = state.activeDownloads - variant.fileName) }
          refreshModelStorageStatus()
          break
        }
        if (progress.isFailed) {
          val errorDesc = progress.statusMessage.ifBlank { "Download fehlgeschlagen" }
          Log.w(tag, "DownloadManager fehlgeschlagen für ${variant.fileName}: $errorDesc. Wechsle zu Direktdownload...")
          _uiState.update { state ->
            state.copy(
              activeDownloads = state.activeDownloads - variant.fileName,
              errorMessage = "DownloadManager meldet: $errorDesc. Starte Direktdownload..."
            )
          }
          startDirectDownload(variant)
          break
        }
        if (!_uiState.value.activeDownloads.containsKey(variant.fileName)) {
          break
        }
      }
    }
  }

  fun updateCameraFrame(bitmap: Bitmap) {
    visionSampler.updateFrame(bitmap)
  }

  private fun handleBargeIn() {
    nativeEngine.stopCurrentGeneration()
    activeInferenceJob?.cancel()
    audioPlayer.stop()
    fullDuplexAudio.isAssistantSpeaking = false
    _uiState.update {
      it.copy(
        status = OmniStatus.LISTENING,
        executionPhase = LfmExecutionPhase.READY_IDLE,
        audioRms = 0f
      )
    }
  }

  fun startCall() {
    if (_uiState.value.callState != OmniCallState.IDLE) return

    val models = GgufModelCatalog.scanLocalModels(getApplication())
    val hasMainModel = models.any { !it.isProjector && it.isComplete && it.isValidGguf }
    val hasProjector = models.any { it.isProjector && it.isComplete && it.isValidGguf }

    if (!hasMainModel || !hasProjector) {
      val missingPart = when {
        !hasMainModel && !hasProjector -> "Hauptmodell und Omni-Projektor (mmproj)"
        !hasMainModel -> "LFM3-Omni-2B-Abliter8 Hauptmodell"
        else -> "Omni-Projektor (mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf)"
      }
      _uiState.update {
        it.copy(
          errorMessage = "$missingPart fehlt auf dem Gerät. Bitte im Modell-Manager aus abliter8-ai/lfm3-2b-omni-abliter8-gguf herunterladen."
        )
      }
      return
    }

    if (_uiState.value.modelStorageStatus.isModelLoaded && nativeEngine.isReady()) {
      enterConnectedCall()
      return
    }

    _uiState.update {
      it.copy(
        callState = OmniCallState.CONNECTING,
        isCallActive = false,
        status = OmniStatus.LOADING,
        executionPhase = LfmExecutionPhase.MAPPING_WEIGHTS,
        errorMessage = null,
        diagnosticLogs = emptyList(),
        showDiagnosticsSheet = false
      )
    }

    if (initializationJob?.isActive == true) {
      logDiagnostic("INIT: Initialisierung ist bereits aktiv...")
      return
    }

    initializationJob = viewModelScope.launch {
      try {
        nativeEngine.loadModel(
          config = _uiState.value.inferenceConfig,
          onLog = { logDiagnostic(it) },
          onProgress = { stepText, fraction ->
            _uiState.update { state ->
              state.copy(
                modelStorageStatus = state.modelStorageStatus.copy(
                  initStepText = stepText,
                  initProgressFraction = fraction
                )
              )
            }
          }
        )
        refreshModelStorageStatus()
        enterConnectedCall()
      } catch (e: CancellationException) {
        logDiagnostic("INIT: Hardware-Initialisierung abgebrochen.")
        _uiState.update { it.copy(showDiagnosticsSheet = false) }
      } catch (e: Exception) {
        val err = e.message ?: "Initialisierungsfehler"
        Log.e(tag, "Hardware-Initialisierung beim Anrufen fehlgeschlagen: $err")
        _uiState.update {
          it.copy(
            callState = OmniCallState.IDLE,
            isCallActive = false,
            status = OmniStatus.ERROR,
            executionPhase = LfmExecutionPhase.FAULT,
            errorMessage = err,
            showDiagnosticsSheet = true
          )
        }
      }
    }
  }

  private fun vibratePattern(timings: LongArray) {
    try {
      val app = getApplication<Application>()
      val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val vm = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
      }
      if (vibrator != null && vibrator.hasVibrator()) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
          vibrator.vibrate(android.os.VibrationEffect.createWaveform(timings, -1))
        } else {
          @Suppress("DEPRECATION")
          vibrator.vibrate(timings, -1)
        }
      }
    } catch (e: Exception) {
      Log.d(tag, "Vibration skipped: ${e.message}")
    }
  }

  private fun enterConnectedCall() {
    val sessionId = ++activeCallSessionId
    vibratePattern(longArrayOf(0, 50))
    nativeEngine.stopCurrentGeneration()
    activeInferenceJob?.cancel()
    activeInferenceJob = null
    audioPlayer.stop()
    lastGeneratedAudioPcm = null

    val cfg = _uiState.value.inferenceConfig
    val isCurrentlyMuted = _uiState.value.isMuted
    val shouldActivateCam = _uiState.value.isCameraActive || cfg.autoStartCameraOnCall
    fullDuplexAudio.resetSessionBuffers()
    fullDuplexAudio.isMuted = isCurrentlyMuted
    fullDuplexAudio.isAssistantSpeaking = false

    fullDuplexAudio.updateConfig(cfg.micGain, cfg.vadSensitivity, cfg.silenceTimeoutMs)
    visionSampler.updateConfig(cfg.visionFps, cfg.visionKeyframesCount, cfg.visionResolution)
    if (!shouldActivateCam) {
      visionSampler.stopSampling()
    }

    _uiState.update {
      it.copy(
        callState = OmniCallState.CONNECTED,
        isCallActive = true,
        isCameraActive = shouldActivateCam,
        isTorchOn = false,
        callDurationSeconds = 0L,
        status = OmniStatus.LISTENING,
        executionPhase = LfmExecutionPhase.READY_IDLE,
        isSpeechValid = false,
        userVoiceRms = 0f,
        userWaveform = List(32) { 0f },
        userLiveTranscript = "",
        lastUserTranscript = "",
        streamingAiResponse = "",
        lastAiResponse = "",
        spokenCharCount = 0,
        audioRms = 0f,
        latencyMs = 0L,
        messages = emptyList(),
        errorMessage = null,
        showDiagnosticsSheet = false
      )
    }

    // Exklusiver 16 kHz AudioRecord + Hardware AEC Zugriff für natives Speech-to-Speech Input & Barge-In
    fullDuplexAudio.startAudioEngine {
      if (sessionId == activeCallSessionId) {
        handleBargeIn()
      }
    }

    if (shouldActivateCam) {
      visionSampler.startContinuousSampling { }
    }

    callTimerJob?.cancel()
    callTimerJob = viewModelScope.launch {
      while (coroutineContext[Job]?.isActive == true && sessionId == activeCallSessionId) {
        delay(1000L)
        if (_uiState.value.callState == OmniCallState.CONNECTED && sessionId == activeCallSessionId) {
          _uiState.update { it.copy(callDurationSeconds = it.callDurationSeconds + 1L) }
        }
      }
    }

    triggerCallGreeting(sessionId)
  }

  private fun triggerCallGreeting(sessionId: Long = activeCallSessionId) {
    if (_uiState.value.callState != OmniCallState.CONNECTED || sessionId != activeCallSessionId) return

    _uiState.update {
      it.copy(
        status = OmniStatus.THINKING,
        executionPhase = LfmExecutionPhase.CONV_PREFILL,
        streamingAiResponse = "",
        spokenCharCount = 0,
        errorMessage = null
      )
    }

    executeOmniPipeline(
      userPromptText = _uiState.value.inferenceConfig.greetingPrompt,
      rawPcmAudio = null,
      temporalVisionFrames = null,
      isGreeting = true,
      expectedSessionId = sessionId
    )
  }

  fun endCall() {
    activeCallSessionId++
    vibratePattern(longArrayOf(0, 60, 60, 60))
    initializationJob?.cancel()
    initializationJob = null
    callTimerJob?.cancel()
    callTimerJob = null
    nativeEngine.stopCurrentGeneration()
    activeInferenceJob?.cancel()
    activeInferenceJob = null
    audioPlayer.stop()
    fullDuplexAudio.stopAudioEngine()
    asyncHistoryTranscriber.release()
    visionSampler.stopSampling()
    lastGeneratedAudioPcm = null

    // Beim Beenden des Anrufs ALLE schweren Hardware- und RAM-Ressourcen (1.9 GB Modell + Projektor + C++ Kontexte) vollständig entladen
    sessionResetJob?.cancel()
    sessionResetJob = viewModelScope.launch {
      nativeEngine.unloadModel()
    }

    _uiState.update {
      it.copy(
        callState = OmniCallState.IDLE,
        isCallActive = false,
        isCameraActive = false,
        isTorchOn = false,
        callDurationSeconds = 0L,
        status = OmniStatus.IDLE,
        executionPhase = LfmExecutionPhase.OFFLINE,
        isSpeechValid = false,
        userVoiceRms = 0f,
        userWaveform = List(32) { 0f },
        userLiveTranscript = "",
        lastUserTranscript = "",
        streamingAiResponse = "",
        lastAiResponse = "",
        spokenCharCount = 0,
        audioRms = 0f,
        latencyMs = 0L,
        messages = emptyList(),
        errorMessage = null,
        showDiagnosticsSheet = false,
        modelStorageStatus = it.modelStorageStatus.copy(
          isModelLoaded = false,
          isProjectorLoaded = false,
          projectorHasAudioGen = false,
          initStepText = "",
          initProgressFraction = 0f,
          statusDescription = when {
            it.modelStorageStatus.isModelFilePresent && it.modelStorageStatus.isProjectorPresent ->
              "LFM3-Omni Abliter8: Auf Gerät bereit (RAM entladen)"
            else -> "Modell oder Projektor fehlt"
          }
        )
      )
    }
    Log.i(tag, "Anruf beendet: Alle schweren Modell-, Audio-, Kamera- und C++-Ressourcen vollständig aus dem RAM entladen.")
  }

  fun toggleCall() {
    if (_uiState.value.callState != OmniCallState.IDLE) {
      endCall()
    } else {
      startCall()
    }
  }

  fun toggleMute() {
    val nextMute = !_uiState.value.isMuted
    fullDuplexAudio.isMuted = nextMute
    _uiState.update { it.copy(isMuted = nextMute, isSpeechValid = false, userVoiceRms = 0f) }
  }

  private fun handleVadSpeechFinished(rawPcm: ByteArray) {
    val sessionId = activeCallSessionId
    if (!_uiState.value.isCallActive || rawPcm.size < 3200) return
    val durationSec = (rawPcm.size / 2) / 16000.0f
    val label = "Spracheingabe (${String.format(Locale.US, "%.1f", durationSec)}s • 16kHz Audio)"
    val msgId = "voice_${System.currentTimeMillis()}"

    // Nur aktuelle Kamera-Frames dieses Turns verwenden (und auch nur dann, wenn die Kamera aktiv ist)
    val temporalFrames = if (_uiState.value.isCameraActive) {
      visionSampler.getTemporalFramesSequence()
    } else null

    val windowSec = temporalFrames?.firstOrNull()?.relativeSecondsAgo ?: 0f
    val userMsg = OmniMessage(
      id = msgId,
      isUser = true,
      text = label,
      snapshotBitmap = if (_uiState.value.isCameraActive) visionSampler.latestRawBitmap else null,
      videoFramesCount = temporalFrames?.size ?: 0,
      videoWindowSeconds = windowSec,
      isVoiceInput = true,
      isTranscriptResolved = false
    )

    _uiState.update { state ->
      // Entferne speicherhungrige Audio-/Bitmap-Blobs aus vergangenen Nachrichten im Verlauf
      val compactedHistory = state.messages.map { oldMsg ->
        if (oldMsg.generatedAudioPcm != null || oldMsg.snapshotBitmap != null) {
          oldMsg.copy(generatedAudioPcm = null, snapshotBitmap = null)
        } else {
          oldMsg
        }
      }
      state.copy(
        messages = compactedHistory + userMsg,
        userLiveTranscript = label,
        lastUserTranscript = label,
        status = OmniStatus.THINKING,
        executionPhase = LfmExecutionPhase.CONV_PREFILL,
        isSpeechValid = false,
        streamingAiResponse = "",
        spokenCharCount = 0,
        errorMessage = null
      )
    }

    // 1. Echter Speech-to-Speech Aufruf: rawPcm (16 kHz) + aktuelle Vision-Frames gehen SOFORT nativ an LFM3-Omni
    executeOmniPipeline(
      userPromptText = "",
      rawPcmAudio = rawPcm,
      temporalVisionFrames = temporalFrames,
      isGreeting = false,
      expectedSessionId = sessionId
    )

    // 2. Parallele Hintergrund-Transkription via ParcelFileDescriptor-Pipe & OnDeviceSpeechRecognizer:
    //    Ersetzt den Audio-Platzhalter in der ChatHistory durch den kompakten Text-String für Folgefragen.
    asyncHistoryTranscriber.transcribePcm16kAsync(
      messageId = msgId,
      pcm16kBytes = rawPcm,
      onLog = { logDiagnostic(it) },
      onPartialTranscript = { id, partialText ->
        if (sessionId == activeCallSessionId && partialText.isNotBlank()) {
          _uiState.update { state ->
            state.copy(
              userLiveTranscript = partialText,
              messages = state.messages.map { m ->
                if (m.id == id && !m.isTranscriptResolved) m.copy(text = partialText) else m
              }
            )
          }
        }
      },
      onFinalTranscript = { id, finalTranscript ->
        if (sessionId == activeCallSessionId && !finalTranscript.isNullOrBlank()) {
          _uiState.update { state ->
            state.copy(
              userLiveTranscript = finalTranscript,
              lastUserTranscript = finalTranscript,
              messages = state.messages.map { m ->
                if (m.id == id) {
                  m.copy(text = finalTranscript, isTranscriptResolved = true)
                } else {
                  m
                }
              }
            )
          }
        }
      }
    )
  }

  fun sendTextPromptWithVision(text: String) {
    val clean = text.trim()
    if (clean.isBlank()) return
    val temporalFrames = if (_uiState.value.isCameraActive) {
      visionSampler.getTemporalFramesSequence()
    } else null

    val windowSec = temporalFrames?.firstOrNull()?.relativeSecondsAgo ?: 0f
    val userMsg = OmniMessage(
      isUser = true,
      text = clean,
      snapshotBitmap = if (_uiState.value.isCameraActive) visionSampler.latestRawBitmap else null,
      videoFramesCount = temporalFrames?.size ?: 0,
      videoWindowSeconds = windowSec,
      isTranscriptResolved = true
    )

    _uiState.update { state ->
      val compactedHistory = state.messages.map { oldMsg ->
        if (oldMsg.generatedAudioPcm != null || oldMsg.snapshotBitmap != null) {
          oldMsg.copy(generatedAudioPcm = null, snapshotBitmap = null)
        } else {
          oldMsg
        }
      }
      state.copy(
        messages = compactedHistory + userMsg,
        userLiveTranscript = "",
        lastUserTranscript = clean,
        status = OmniStatus.THINKING,
        executionPhase = LfmExecutionPhase.CONV_PREFILL,
        streamingAiResponse = "",
        spokenCharCount = 0,
        errorMessage = null
      )
    }

    executeOmniPipeline(
      userPromptText = clean,
      rawPcmAudio = null,
      temporalVisionFrames = temporalFrames,
      isGreeting = false
    )
  }

  private fun executeOmniPipeline(
    userPromptText: String,
    rawPcmAudio: ByteArray?,
    temporalVisionFrames: List<com.example.vision.TemporalVideoFrame>?,
    isGreeting: Boolean = false,
    expectedSessionId: Long = activeCallSessionId
  ) {
    if (expectedSessionId != activeCallSessionId) return
    nativeEngine.stopCurrentGeneration()
    activeInferenceJob?.cancel()
    audioPlayer.stop()
    audioPlayer.beginNewResponseTurn()
    fullDuplexAudio.isAssistantSpeaking = true

    val startTime = System.currentTimeMillis()
    val currentHistory = if (isGreeting) emptyList() else _uiState.value.messages
    val currentConfig = _uiState.value.inferenceConfig
    val femaleVoiceDirective = currentConfig.omniVoiceGender
    val useSystemTts = currentConfig.omniOutputMode.equals("SYSTEM_TTS", ignoreCase = true) ||
      currentConfig.omniOutputMode.equals("TEXT", ignoreCase = true)
    audioPlayer.updateTtsConfig(currentConfig.speechRate, currentConfig.speechPitch)

    activeInferenceJob = viewModelScope.launch {
      val textAcc = StringBuilder()

      try {
        sessionResetJob?.join()
        if (expectedSessionId != activeCallSessionId) return@launch

        if (!nativeEngine.isReady()) {
          _uiState.update {
            it.copy(
              status = OmniStatus.LOADING,
              executionPhase = LfmExecutionPhase.MAPPING_WEIGHTS
            )
          }
          nativeEngine.loadModel(
            config = currentConfig,
            onLog = { logDiagnostic(it) },
            onProgress = { stepText, fraction ->
              if (expectedSessionId == activeCallSessionId) {
                _uiState.update { state ->
                  state.copy(
                    modelStorageStatus = state.modelStorageStatus.copy(
                      initStepText = stepText,
                      initProgressFraction = fraction
                    )
                  )
                }
              }
            }
          )
          refreshModelStorageStatus()
        } else if (isGreeting) {
          nativeEngine.resetSession()
        }

        if (expectedSessionId != activeCallSessionId) return@launch

        nativeEngine.executeMultimodalStream(
          userTextPrompt = userPromptText,
          rawAudioPcm = rawPcmAudio,
          sampledCameraFrames = temporalVisionFrames?.map { it.jpegBytes },
          temporalCameraFrames = temporalVisionFrames,
          femaleVoiceDirective = femaleVoiceDirective,
          conversationHistory = currentHistory,
          inferenceConfig = currentConfig,
          isGreeting = isGreeting,
          onLog = { logDiagnostic(it) }
        ).catch { e ->
          if (e is CancellationException) throw e
          if (expectedSessionId != activeCallSessionId) return@catch
          val msg = e.localizedMessage ?: "Native LFM3-Omni Inferenzfehler"
          Log.e(tag, "Inference pipeline error: $msg", e)
          logDiagnostic("PIPELINE FAIL: $msg")
          audioPlayer.stop()
          fullDuplexAudio.isAssistantSpeaking = false
          _uiState.update {
            it.copy(
              status = OmniStatus.ERROR,
              executionPhase = LfmExecutionPhase.FAULT,
              errorMessage = msg
            )
          }
        }.collect { output ->
          if (expectedSessionId != activeCallSessionId) {
            throw CancellationException("Session gewechselt")
          }
          when (output) {
            is OmniStreamOutput.PhaseChange -> {
              _uiState.update { it.copy(executionPhase = output.phase) }
            }
            is OmniStreamOutput.TextToken -> {
              textAcc.append(output.text)
              if (useSystemTts) {
                // Satzweise Echtzeit-Weitergabe des gestreamten Modell-Textes an die Android Standard-System-TTS
                audioPlayer.enqueueTtsTextDelta(output.text)
              }
              _uiState.update {
                it.copy(
                  status = OmniStatus.SPEAKING,
                  executionPhase = LfmExecutionPhase.OMNI_DECODE,
                  streamingAiResponse = textAcc.toString(),
                  lastAiResponse = textAcc.toString()
                )
              }
            }
            is OmniStreamOutput.AudioChunk -> {
              if (!useSystemTts) {
                // Direktes Echtzeit-Streaming der vom Modell generierten 24 kHz Sprach-Audio-Chunks!
                audioPlayer.playPcmChunk(
                  pcmData = output.pcm24k,
                  sampleRate = output.sampleRate,
                  currentTextLength = textAcc.length
                )
                _uiState.update {
                  it.copy(
                    status = OmniStatus.SPEAKING,
                    executionPhase = LfmExecutionPhase.OMNI_DECODE
                  )
                }
              }
            }
          }
        }

        if (expectedSessionId != activeCallSessionId) return@launch

        if (useSystemTts) {
          audioPlayer.flushTtsTextStream()
        } else {
          audioPlayer.finishAudioStream()
        }

        val generatedAudio = if (useSystemTts) null else audioPlayer.getBufferedTurnAudio()
        if (generatedAudio != null) {
          lastGeneratedAudioPcm = generatedAudio
          lastGeneratedSampleRate = audioPlayer.lastTurnSampleRate
        } else if (!useSystemTts && textAcc.isNotBlank()) {
          // Falls im nativen Modus keine Audio-Frames erzeugt wurden, nahtlos über System-TTS sprechen
          logDiagnostic("AUDIO-FALLBACK: Keine nativen Audio-Frames -> Spreche Text über Android System-TTS")
          audioPlayer.speakFullTextWithSystemTts(textAcc.toString().trim())
        }

        val finalText = textAcc.toString().trim()
        val latency = System.currentTimeMillis() - startTime
        val stillSpeakingAudio = audioPlayer.isSpeaking.value

        if (finalText.isNotEmpty() || generatedAudio != null) {
          val displayReply = finalText.ifBlank { "[24 kHz Sprach-Antwort]" }
          val aiMessage = OmniMessage(
            isUser = false,
            text = displayReply,
            modelLatencyMs = latency,
            generatedAudioPcm = generatedAudio,
            generatedAudioSampleRate = audioPlayer.lastTurnSampleRate,
            isGreeting = isGreeting
          )
          _uiState.update { state ->
            val cleanedMessages = state.messages.map { oldMsg ->
              if (oldMsg.generatedAudioPcm != null) oldMsg.copy(generatedAudioPcm = null) else oldMsg
            }
            state.copy(
              messages = cleanedMessages + aiMessage,
              userLiveTranscript = "",
              lastAiResponse = displayReply,
              latencyMs = latency,
              status = if (stillSpeakingAudio) {
                OmniStatus.SPEAKING
              } else if (state.isCallActive) {
                OmniStatus.LISTENING
              } else {
                OmniStatus.IDLE
              },
              executionPhase = LfmExecutionPhase.READY_IDLE
            )
          }
        } else {
          _uiState.update {
            it.copy(
              status = if (it.isCallActive) OmniStatus.LISTENING else OmniStatus.IDLE,
              executionPhase = LfmExecutionPhase.READY_IDLE
            )
          }
        }

        if (!stillSpeakingAudio) {
          fullDuplexAudio.isAssistantSpeaking = false
        }
      } catch (e: CancellationException) {
        Log.d(tag, "Inferenz-Coroutine regulär abgebrochen.")
      } catch (e: Exception) {
        if (expectedSessionId != activeCallSessionId) return@launch
        fullDuplexAudio.isAssistantSpeaking = false
        val errorMsg = e.localizedMessage ?: "Fehler bei nativer Modell-Inferenz"
        Log.e(tag, "Hardware-Inferenz fehlgeschlagen: $errorMsg", e)
        logDiagnostic("PIPELINE ERROR: $errorMsg")
        _uiState.update {
          it.copy(
            status = OmniStatus.ERROR,
            executionPhase = LfmExecutionPhase.FAULT,
            errorMessage = errorMsg
          )
        }
      }
    }
  }

  fun stopAudioPlayback() {
    nativeEngine.stopCurrentGeneration()
    activeInferenceJob?.cancel()
    audioPlayer.stop()
    fullDuplexAudio.isAssistantSpeaking = false
    _uiState.update {
      it.copy(
        status = if (it.isCallActive) OmniStatus.LISTENING else OmniStatus.IDLE,
        executionPhase = LfmExecutionPhase.READY_IDLE,
        audioRms = 0f
      )
    }
  }

  fun toggleCamera() {
    val nextActive = !_uiState.value.isCameraActive
    _uiState.update { it.copy(isCameraActive = nextActive) }
    if (nextActive) {
      visionSampler.startContinuousSampling { }
    } else {
      visionSampler.stopSampling()
      _uiState.update { it.copy(isTorchOn = false) }
    }
  }

  fun switchCameraLens() {
    _uiState.update {
      it.copy(
        cameraLens = if (it.cameraLens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT,
        isTorchOn = false
      )
    }
  }

  fun toggleTorch() {
    if (_uiState.value.cameraLens == CameraLens.BACK) {
      _uiState.update { it.copy(isTorchOn = !it.isTorchOn) }
    }
  }

  fun setTorchLevel(level: Float) {
    _uiState.update { it.copy(torchLevel = level.coerceIn(0.05f, 1f)) }
  }

  fun updateInferenceConfig(config: LfmInferenceConfig) {
    _uiState.update { it.copy(inferenceConfig = config) }
    audioPlayer.updateTtsConfig(config.speechRate, config.speechPitch)
    audioPlayer.enableSentenceSplitting = config.enableSentenceSplitting
    fullDuplexAudio.updateConfig(
      micGain = config.micGain,
      vadSensitivity = config.vadSensitivity,
      silenceTimeoutMs = config.silenceTimeoutMs
    )
    visionSampler.updateConfig(
      fps = config.visionFps,
      keyframesCount = config.visionKeyframesCount,
      resolution = config.visionResolution
    )
  }

  fun refreshSystemDefaultTts() {
    audioPlayer.refreshSystemDefaultTtsEngine()
  }

  fun ensureSystemDefaultTtsOnResume() {
    audioPlayer.ensureSystemDefaultTtsBound()
  }

  /**
   * Testet die aktuell ausgewählte Sprachausgabe (entweder Android System-TTS oder native 24 kHz Modell-Stimme).
   */
  fun testVoice() {
    val cfg = _uiState.value.inferenceConfig
    val isSysTts = cfg.omniOutputMode.equals("SYSTEM_TTS", ignoreCase = true) ||
      cfg.omniOutputMode.equals("TEXT", ignoreCase = true)

    if (isSysTts) {
      audioPlayer.ensureSystemDefaultTtsBound()
      audioPlayer.updateTtsConfig(cfg.speechRate, cfg.speechPitch)
      audioPlayer.speakFullTextWithSystemTts(
        "Hallo! Deine auf Android als Standard eingestellte System-Sprachausgabe ist aktiv. Jeder generierte Satz wird direkt hierüber gesprochen."
      )
      return
    }
    if (!nativeEngine.isReady()) {
      _uiState.update {
        it.copy(
          errorMessage = "Bitte zuerst LFM3-Omni initialisieren oder Anruf starten, um die native 24kHz Modell-Stimme zu testen."
        )
      }
      return
    }
    executeOmniPipeline(
      userPromptText = "Sag kurz auf Deutsch in einem Satz: Hallo, die Sprachausgabe ist bereit.",
      rawPcmAudio = null,
      temporalVisionFrames = null,
      isGreeting = false
    )
  }

  /**
   * Spielt die Nachricht erneut ab (über 24 kHz PCM-Audio falls vorhanden, oder über die Android System-TTS).
   */
  fun replayVoice(text: String, audioPcm: ByteArray? = null, sampleRate: Int = 24000) {
    val pcmToPlay = audioPcm ?: lastGeneratedAudioPcm
    if (pcmToPlay != null && pcmToPlay.isNotEmpty()) {
      audioPlayer.replayPcm(
        pcmData = pcmToPlay,
        sampleRate = if (audioPcm != null) sampleRate else lastGeneratedSampleRate,
        totalTextLength = text.length
      )
    } else if (text.isNotBlank()) {
      val cfg = _uiState.value.inferenceConfig
      audioPlayer.updateTtsConfig(cfg.speechRate, cfg.speechPitch)
      audioPlayer.speakFullTextWithSystemTts(text)
    }
  }

  fun clearHistory() {
    lastGeneratedAudioPcm = null
    sessionResetJob?.cancel()
    sessionResetJob = viewModelScope.launch {
      if (nativeEngine.isReady()) {
        nativeEngine.resetSession()
      }
    }
    _uiState.update {
      it.copy(
        messages = emptyList(),
        streamingAiResponse = "",
        lastAiResponse = "",
        userLiveTranscript = "",
        lastUserTranscript = "",
        spokenCharCount = 0,
        audioRms = 0f
      )
    }
  }

  fun clearDiagnostics() {
    _uiState.update { it.copy(diagnosticLogs = emptyList()) }
  }

  fun dismissError() {
    _uiState.update {
      it.copy(
        errorMessage = null,
        status = if (it.isCallActive) OmniStatus.LISTENING else OmniStatus.IDLE,
        executionPhase = if (it.modelStorageStatus.isModelLoaded) LfmExecutionPhase.READY_IDLE else LfmExecutionPhase.OFFLINE
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    audioPlayer.release()
    fullDuplexAudio.release()
    asyncHistoryTranscriber.release()
    visionSampler.stopSampling()
    nativeEngine.release()
  }
}
