package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LfmExecutionPhase
import com.example.model.OmniCallState
import com.example.model.OmniStatus
import com.example.ui.components.ActiveCallTimerHeader
import com.example.ui.components.ActiveDownloadsBanner
import com.example.ui.components.AiResponseTranscriptCard
import com.example.ui.components.CallConnectingOverlayCard
import com.example.ui.components.CameraView
import com.example.ui.components.ConversationHistorySheet
import com.example.ui.components.GgufModelManagerDialog
import com.example.ui.components.LiquidOrbVisualizer
import com.example.ui.components.ModelOfflineNoticeCard
import com.example.ui.components.SettingsSheet
import com.example.ui.components.UserLiveTranscriptCard
import com.example.ui.components.UserVoiceAmplitudeVisualizer
import com.example.ui.theme.LiquidAmber
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.SpaceBlack
import com.example.viewmodel.OmniViewModel

@Composable
fun OmniMainScreen(
  viewModel: OmniViewModel,
  modifier: Modifier = Modifier
) {
  val uiState by viewModel.uiState.collectAsState()

  var showSettings by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var showModelManager by remember { mutableStateOf(false) }

  val isCallConnected = uiState.callState == OmniCallState.CONNECTED
  val isCallConnecting = uiState.callState == OmniCallState.CONNECTING
  val isCallIdle = uiState.callState == OmniCallState.IDLE

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(SpaceBlack)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // 1. Minimalist Top Bar
      if (isCallConnected) {
        ActiveCallTopBar(
          callDurationSeconds = uiState.callDurationSeconds,
          modelStatus = uiState.modelStorageStatus,
          onOpenHistory = { showHistory = true },
          onOpenSettings = { showSettings = true },
          onOpenLogs = { viewModel.toggleDiagnosticsSheet() }
        )
      } else {
        TopAppBarMinimal(
          isModelLoaded = uiState.modelStorageStatus.isModelLoaded,
          isModelFilePresent = uiState.modelStorageStatus.isModelFilePresent,
          executionPhase = uiState.executionPhase,
          activeDownloadsCount = uiState.activeDownloads.size,
          onOpenModelManager = { showModelManager = true },
          onOpenHistory = { showHistory = true },
          onOpenSettings = { showSettings = true },
          onOpenLogs = { viewModel.toggleDiagnosticsSheet() }
        )
      }

      // Active Downloads Banner (only visible while downloading)
      ActiveDownloadsBanner(
        activeDownloads = uiState.activeDownloads,
        onOpenModelManager = { showModelManager = true },
        onCancelDownload = { fileName -> viewModel.cancelDownload(fileName) }
      )

      // Missing model notice (only in IDLE when GGUF files are not yet on disk)
      if (isCallIdle && !uiState.modelStorageStatus.isModelFilePresent && uiState.activeDownloads.isEmpty() && uiState.status != OmniStatus.LOADING) {
        ModelOfflineNoticeCard(
          onOpenModelManager = { showModelManager = true }
        )
      }

      // Error banner if hardware or file error occurred
      AnimatedVisibility(
        visible = uiState.errorMessage != null,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        uiState.errorMessage?.let { errorText ->
          Surface(
            color = Color(0xFFB71C1C).copy(alpha = 0.85f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth(0.92f)
              .padding(vertical = 4.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = errorText,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
              )
              Text(
                text = "LOGS",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                  .padding(horizontal = 8.dp)
                  .clickable { viewModel.toggleDiagnosticsSheet() }
                  .testTag("open_diagnostics_from_error_btn")
              )
              IconButton(
                onClick = { viewModel.dismissError() },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Fehler schließen",
                  tint = Color.White,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }
      }

      // 2. Main Visual Area: Liquid Orb (always present, adapts to IDLE / CONNECTING / CONNECTED)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(if (isCallConnected && uiState.isCameraActive) 0.75f else 1.15f)
          .padding(horizontal = 24.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.fillMaxSize()
        ) {
          LiquidOrbVisualizer(
            status = uiState.status,
            executionPhase = uiState.executionPhase,
            audioRms = uiState.audioRms,
            speechAmplitude = uiState.audioRms,
            callState = uiState.callState,
            onOrbClick = {
              if (uiState.status == OmniStatus.SPEAKING) {
                viewModel.stopAudioPlayback()
              } else if (isCallIdle) {
                viewModel.startCall()
              }
            },
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
          )

          // Subtle frameless status text in IDLE or CONNECTING
          if (isCallIdle && uiState.modelStorageStatus.isModelFilePresent) {
            Text(
              text = "Bereit für Sprachanruf",
              color = Color.White.copy(alpha = 0.38f),
              fontSize = 13.sp,
              fontWeight = FontWeight.Normal,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(bottom = 12.dp)
            )
          } else if (isCallConnecting) {
            CallConnectingOverlayCard(
              modelStatus = uiState.modelStorageStatus,
              onCancelCall = { viewModel.endCall() }
            )
          }
        }
      }

      // =========================================================================
      // 3. IN-CALL ONLY ELEMENTS: Subtitles, Audio Wave & Live Camera
      //    Exist strictly when a call is active (CONNECTED)
      // =========================================================================
      if (isCallConnected) {
        val currentAiText = if (uiState.streamingAiResponse.isNotBlank()) {
          uiState.streamingAiResponse
        } else {
          uiState.lastAiResponse
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // 3A. Frameless AI Subtitle above the wave
          AiResponseTranscriptCard(
            text = currentAiText,
            isStreaming = uiState.status == OmniStatus.SPEAKING || uiState.status == OmniStatus.THINKING,
            spokenCharCount = uiState.spokenCharCount,
            latencyMs = uiState.latencyMs,
            isCallActive = true,
            onReplayClick = { textToSpeak -> viewModel.replayVoice(textToSpeak) }
          )

          // 3B. True Continuous Sinusoidal Audio Wave (no pane, no border, no text)
          UserVoiceAmplitudeVisualizer(
            amplitude = uiState.userVoiceRms,
            waveform = uiState.userWaveform,
            isValidSpeech = uiState.isSpeechValid,
            isMuted = uiState.isMuted,
            isCallActive = true,
            aiSpeechAmplitude = uiState.audioRms,
            isAiSpeaking = uiState.status == OmniStatus.SPEAKING,
            vadThreshold = uiState.inferenceConfig.vadSensitivity,
            modifier = Modifier.padding(vertical = 4.dp)
          )

          // 3C. Frameless User Live Transcript below the wave
          UserLiveTranscriptCard(
            liveTranscript = uiState.userLiveTranscript,
            lastTranscript = uiState.lastUserTranscript,
            isCallActive = true,
            isMuted = uiState.isMuted,
            isCameraActive = uiState.isCameraActive,
            visionFramesCount = uiState.visionTelemetry.selectedKeyframesCount,
            visionWindowSec = uiState.visionTelemetry.windowDurationSeconds
          )
        }

        // 4. Live Camera Preview (only during an active call when camera is enabled)
        if (uiState.isCameraActive) {
          Box(
            modifier = Modifier
              .fillMaxWidth(0.92f)
              .weight(0.95f)
              .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
          ) {
            CameraView(
              isCameraActive = uiState.isCameraActive,
              cameraLens = uiState.cameraLens,
              isTorchOn = uiState.isTorchOn,
              torchLevel = uiState.torchLevel,
              onToggleLens = { viewModel.switchCameraLens() },
              onToggleTorch = { viewModel.toggleTorch() },
              onTorchLevelChange = { viewModel.setTorchLevel(it) },
              onCaptureFrame = { bmp -> viewModel.updateCameraFrame(bmp) },
              onStartCamera = { viewModel.toggleCamera() },
              modifier = Modifier.fillMaxSize()
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // 5. Bottom Call Controls (Strictly adapts to IDLE vs CONNECTING vs CONNECTED)
      OmniBottomInterface(
        callState = uiState.callState,
        isMuted = uiState.isMuted,
        isCameraActive = uiState.isCameraActive,
        onToggleCall = { viewModel.toggleCall() },
        onToggleMute = { viewModel.toggleMute() },
        onToggleCamera = { viewModel.toggleCamera() },
        onSendChat = { text -> viewModel.sendTextPromptWithVision(text) },
        modifier = Modifier.fillMaxWidth()
      )
    }
  }

  // Dialogs
  if (showModelManager) {
    GgufModelManagerDialog(
      viewModel = viewModel,
      localModels = uiState.localModelFiles,
      modelStorageStatus = uiState.modelStorageStatus,
      activeDownloads = uiState.activeDownloads,
      onDismiss = { showModelManager = false },
      onDownloadModel = { variant -> viewModel.downloadModel(variant) },
      onRefreshScan = { viewModel.refreshModelStorageStatus() }
    )
  }

  if (showSettings) {
    SettingsSheet(
      inferenceConfig = uiState.inferenceConfig,
      architecture = uiState.architecture,
      hardwareInfo = uiState.hardwareInfo,
      backendReport = uiState.backendReport,
      onRemeasureBackends = { viewModel.remeasureBackends() },
      availableVoices = uiState.availableVoices,
      activeSystemTtsEngine = uiState.activeSystemTtsEngine,
      onRefreshSystemTts = { viewModel.refreshSystemDefaultTts() },
      onDismiss = { showSettings = false },
      onConfigChanged = { cfg -> viewModel.updateInferenceConfig(cfg) },
      onTestVoice = { viewModel.testVoice() },
      onOpenModelManager = { showModelManager = true },
      onOpenLogs = { viewModel.toggleDiagnosticsSheet() }
    )
  }

  if (showHistory) {
    ConversationHistorySheet(
      messages = uiState.messages,
      onDismiss = { showHistory = false },
      onReplayVoice = { msg ->
        viewModel.replayVoice(
          text = msg.text,
          audioPcm = msg.generatedAudioPcm,
          sampleRate = msg.generatedAudioSampleRate
        )
      },
      onClearHistory = { viewModel.clearHistory() }
    )
  }

  val context = LocalContext.current
  if (uiState.showDiagnosticsSheet) {
    com.example.ui.components.NativeDiagnosticsSheet(
      diagnosticLogs = uiState.diagnosticLogs,
      onDismiss = { viewModel.toggleDiagnosticsSheet() },
      onClear = { viewModel.clearDiagnostics() },
      onCopy = {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("LFM3-Omni Diagnostics", uiState.diagnosticLogs.joinToString("\n"))
        clipboard?.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "Diagnose-Logs kopiert (${uiState.diagnosticLogs.size} Zeilen)", android.widget.Toast.LENGTH_SHORT).show()
      }
    )
  }
}

@Composable
private fun ActiveCallTopBar(
  callDurationSeconds: Long,
  modelStatus: com.example.model.ModelStorageStatus,
  onOpenHistory: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenLogs: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    ActiveCallTimerHeader(
      callDurationSeconds = callDurationSeconds,
      modelStatus = modelStatus
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = "Logs",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.35f),
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable(onClick = onOpenLogs)
          .padding(horizontal = 8.dp, vertical = 4.dp)
      )
      IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp).testTag("top_open_history_btn")) {
        Icon(Icons.Default.History, contentDescription = "Verlauf", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
      }
      IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp).testTag("top_open_settings_btn")) {
        Icon(Icons.Default.Settings, contentDescription = "Einstellungen", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
private fun TopAppBarMinimal(
  isModelLoaded: Boolean,
  isModelFilePresent: Boolean,
  executionPhase: LfmExecutionPhase,
  activeDownloadsCount: Int,
  onOpenModelManager: () -> Unit,
  onOpenHistory: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenLogs: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      val dotColor = when {
        activeDownloadsCount > 0 -> LiquidCyan
        executionPhase == LfmExecutionPhase.MAPPING_WEIGHTS -> LiquidAmber
        isModelLoaded -> LiquidTeal
        isModelFilePresent -> LiquidTeal.copy(alpha = 0.65f)
        else -> Color(0xFFFF9800).copy(alpha = 0.7f)
      }
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(dotColor)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "LFM3-Omni",
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = Color.White.copy(alpha = 0.92f)
      )
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = "Logs",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.35f),
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable(onClick = onOpenLogs)
          .padding(horizontal = 8.dp, vertical = 4.dp)
      )
      IconButton(onClick = onOpenModelManager, modifier = Modifier.size(36.dp).testTag("top_open_model_mgr_btn")) {
        Box(contentAlignment = Alignment.TopEnd) {
          Icon(
            Icons.Default.Folder,
            contentDescription = "Modelldateien",
            tint = if (activeDownloadsCount > 0) LiquidCyan else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
          )
          if (activeDownloadsCount > 0) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(LiquidCyan)
            )
          }
        }
      }
      IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp).testTag("top_open_history_btn")) {
        Icon(Icons.Default.History, contentDescription = "Verlauf", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
      }
      IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp).testTag("top_open_settings_btn")) {
        Icon(Icons.Default.Settings, contentDescription = "Einstellungen", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
private fun OmniBottomInterface(
  callState: OmniCallState,
  isMuted: Boolean,
  isCameraActive: Boolean,
  onToggleCall: () -> Unit,
  onToggleMute: () -> Unit,
  onToggleCamera: () -> Unit,
  onSendChat: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var chatInput by remember { mutableStateOf("") }
  var showKeyboardInput by remember(callState) { mutableStateOf(false) }

  val isCallConnected = callState == OmniCallState.CONNECTED
  val isCallActiveOrConnecting = callState != OmniCallState.IDLE

  Column(
    modifier = modifier
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Optional minimalist text input bar — strictly available ONLY during an active connected call
    if (isCallConnected && showKeyboardInput) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 12.dp)
          .clip(RoundedCornerShape(24.dp))
          .background(Color.White.copy(alpha = 0.07f))
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        BasicTextField(
          value = chatInput,
          onValueChange = { chatInput = it },
          modifier = Modifier
            .weight(1f)
            .padding(vertical = 8.dp)
            .testTag("chat_input_textfield"),
          textStyle = TextStyle(
            color = Color.White,
            fontSize = 14.sp
          ),
          cursorBrush = SolidColor(LiquidCyan),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = {
            if (chatInput.isNotBlank()) {
              onSendChat(chatInput.trim())
              chatInput = ""
            }
          }),
          decorationBox = { innerTextField ->
            if (chatInput.isEmpty()) {
              Text(
                text = "Nachricht eingeben...",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 14.sp
              )
            }
            innerTextField()
          }
        )

        if (chatInput.isNotBlank()) {
          IconButton(
            onClick = {
              onSendChat(chatInput.trim())
              chatInput = ""
            },
            modifier = Modifier
              .size(34.dp)
              .testTag("send_chat_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Send,
              contentDescription = "Senden",
              tint = Color.White.copy(alpha = 0.9f),
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }
    }

    // Main Phone Control Row
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(72.dp),
      horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // In-Call ONLY: Camera Toggle
      if (isCallConnected) {
        Surface(
          onClick = onToggleCamera,
          modifier = Modifier
            .requiredSize(50.dp)
            .testTag("bottom_camera_toggle_btn"),
          shape = CircleShape,
          color = if (isCameraActive) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = if (isCameraActive) Icons.Default.Videocam else Icons.Default.VideocamOff,
              contentDescription = "Kamera umschalten",
              tint = if (isCameraActive) LiquidCyan else Color.White.copy(alpha = 0.85f),
              modifier = Modifier.requiredSize(22.dp)
            )
          }
        }
      }

      // Always Present: Call Start (Green) / Call End (Red)
      Surface(
        onClick = onToggleCall,
        modifier = Modifier
          .requiredSize(64.dp)
          .testTag("bottom_call_toggle_btn"),
        shape = CircleShape,
        color = if (isCallActiveOrConnecting) Color(0xFFEA4335) else Color(0xFF34A853),
        shadowElevation = 6.dp
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = if (isCallActiveOrConnecting) Icons.Default.CallEnd else Icons.Default.Call,
            contentDescription = if (isCallActiveOrConnecting) "Anruf beenden" else "Anruf starten",
            tint = Color.White,
            modifier = Modifier.requiredSize(28.dp)
          )
        }
      }

      // In-Call ONLY: Microphone Mute Toggle + Subtle Keyboard Toggle
      if (isCallConnected) {
        Surface(
          onClick = onToggleMute,
          modifier = Modifier
            .requiredSize(50.dp)
            .testTag("bottom_mic_toggle_btn"),
          shape = CircleShape,
          color = if (isMuted) Color(0xFFEA4335).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
              contentDescription = if (isMuted) "Mikrofon stummgeschaltet" else "Mikrofon aktiv",
              tint = if (isMuted) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.85f),
              modifier = Modifier.requiredSize(22.dp)
            )
          }
        }

        Surface(
          onClick = { showKeyboardInput = !showKeyboardInput },
          modifier = Modifier
            .requiredSize(42.dp)
            .testTag("bottom_keyboard_toggle_btn"),
          shape = CircleShape,
          color = if (showKeyboardInput) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.Keyboard,
              contentDescription = "Texteingabe ein-/ausblenden",
              tint = Color.White.copy(alpha = 0.7f),
              modifier = Modifier.requiredSize(18.dp)
            )
          }
        }
      }
    }
  }
}
