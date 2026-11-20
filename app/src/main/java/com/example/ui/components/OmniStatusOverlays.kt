package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DownloadProgressInfo
import com.example.model.LfmExecutionPhase
import com.example.model.ModelStorageStatus
import com.example.model.OmniCallState
import com.example.model.OmniStatus
import com.example.model.formatCallDuration
import com.example.ui.theme.LiquidAmber
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceDark
import java.util.Locale

/**
 * Prominent banner showing active model downloads with live progress,
 * download speed, MB transferred, and remaining time (ETA).
 */
@Composable
fun ActiveDownloadsBanner(
  activeDownloads: Map<String, DownloadProgressInfo>,
  onOpenModelManager: () -> Unit,
  onCancelDownload: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  if (activeDownloads.isEmpty()) return

  Column(
    modifier = modifier
      .fillMaxWidth(0.94f)
      .padding(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    activeDownloads.values.forEach { download ->
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag("active_download_banner_${download.fileName}")
      ) {
        Column(
          modifier = Modifier.padding(12.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = LiquidCyan,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(
                  text = download.fileName,
                  color = Color.White,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (download.statusMessage.isNotBlank()) download.statusMessage else "Download läuft...",
                  color = Color.Gray,
                  fontSize = 11.sp
                )
              }
            }

            IconButton(
              onClick = { onCancelDownload(download.fileName) },
              modifier = Modifier.size(24.dp).testTag("cancel_download_btn_${download.fileName}")
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Download abbrechen",
                tint = Color.LightGray,
                modifier = Modifier.size(16.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Linear Progress Bar
          LinearProgressIndicator(
            progress = { download.progressFraction },
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = LiquidCyan,
            trackColor = Color.White.copy(alpha = 0.15f)
          )

          Spacer(modifier = Modifier.height(6.dp))

          // Download Metrics: MB, Speed, ETA
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = String.format(Locale.US, "%.1f MB / %.1f MB (%.0f%%)", download.downloadedMb, download.totalMb, download.progressFraction * 100f),
              color = Color.LightGray,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
              if (download.speedMbPerSec > 0.05f) {
                Text(
                  text = String.format(Locale.US, "%.1f MB/s", download.speedMbPerSec),
                  color = LiquidCyan,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold
                )
                if (download.etaSeconds > 0) {
                  Text(
                    text = " • noch ${download.etaSeconds}s",
                    color = Color.Gray,
                    fontSize = 11.sp
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Dedicated status banner when model weights are being mapped or initialized into RAM.
 * Displays real granular steps and progress percentage.
 */
@Composable
fun ModelInitializationBanner(
  executionPhase: LfmExecutionPhase,
  status: OmniStatus,
  modelStatus: ModelStorageStatus,
  modifier: Modifier = Modifier
) {
  val isInitializing = executionPhase == LfmExecutionPhase.MAPPING_WEIGHTS ||
                       status == OmniStatus.LOADING

  if (!isInitializing) return

  Surface(
    color = Color(0xFF1E2638),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, LiquidAmber.copy(alpha = 0.7f)),
    modifier = modifier
      .fillMaxWidth(0.94f)
      .padding(vertical = 4.dp)
      .testTag("model_init_banner")
  ) {
    Column(
      modifier = Modifier.padding(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(22.dp),
          strokeWidth = 2.5.dp,
          color = LiquidAmber
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Hardware-Initialisierung",
            color = LiquidAmber,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = if (modelStatus.initStepText.isNotBlank()) modelStatus.initStepText else "Mappe GGUF-Tensoren in Arbeitsspeicher...",
            color = Color.LightGray,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        if (modelStatus.initProgressFraction > 0f) {
          Text(
            text = "${(modelStatus.initProgressFraction * 100).toInt()}%",
            color = LiquidAmber,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          )
        }
      }

      if (modelStatus.initProgressFraction > 0f) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { modelStatus.initProgressFraction },
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
          color = LiquidAmber,
          trackColor = Color.White.copy(alpha = 0.1f)
        )
      }
    }
  }
}

/**
 * Clear notice when no model is found in storage, providing an immediate call-to-action button.
 */
@Composable
fun ModelOfflineNoticeCard(
  onOpenModelManager: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    color = SpaceCard,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.4f)),
    modifier = modifier
      .fillMaxWidth(0.94f)
      .padding(vertical = 4.dp)
      .testTag("model_offline_notice_card")
  ) {
    Column(
      modifier = Modifier.padding(14.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Folder,
          contentDescription = null,
          tint = Color(0xFFFF9800),
          modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Kein LFM-Modell vorhanden",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Laden Sie das Modell im Modell-Manager herunter, um die Sprach- und Bildverarbeitung zu starten.",
            color = Color.LightGray,
            fontSize = 11.sp,
            lineHeight = 15.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Button(
        onClick = onOpenModelManager,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("open_model_mgr_from_notice_btn")
      ) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Modell-Manager öffnen",
          color = Color.Black,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp
        )
      }
    }
  }
}

/**
 * Minimalistischer, rahmenloser Verbindungs-Indikator während des Anrufaufbaus.
 */
@Composable
fun CallConnectingOverlayCard(
  modelStatus: ModelStorageStatus,
  onCancelCall: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth(0.84f)
      .padding(vertical = 8.dp)
      .testTag("call_connecting_card"),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = "Verbinde…",
      color = Color.White,
      fontSize = 18.sp,
      fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = if (modelStatus.initStepText.isNotBlank()) modelStatus.initStepText else "Lade Modell in Arbeitsspeicher…",
      color = Color.White.copy(alpha = 0.55f),
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    if (modelStatus.initProgressFraction > 0f) {
      Spacer(modifier = Modifier.height(12.dp))
      LinearProgressIndicator(
        progress = { modelStatus.initProgressFraction },
        modifier = Modifier
          .fillMaxWidth(0.65f)
          .height(2.dp)
          .clip(RoundedCornerShape(1.dp)),
        color = LiquidCyan,
        trackColor = Color.White.copy(alpha = 0.10f)
      )
    }
  }
}

/**
 * Rahmenloser, minimalistischer Anruf-Timer im oberen Bereich während eines aktiven Gesprächs.
 */
@Composable
fun ActiveCallTimerHeader(
  callDurationSeconds: Long,
  modelStatus: ModelStorageStatus,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .padding(vertical = 4.dp)
      .testTag("active_call_timer_header"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center
  ) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(LiquidTeal)
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = formatCallDuration(callDurationSeconds),
      color = Color.White.copy(alpha = 0.90f),
      fontSize = 14.sp,
      fontWeight = FontWeight.Medium,
      fontFamily = FontFamily.Monospace
    )
  }
}

/**
 * Status card shown outside of call when the model is downloaded and ready on the device.
 */
@Composable
fun ModelReadyStatusCard(
  modelStatus: ModelStorageStatus,
  onStartCall: () -> Unit,
  onInitializeModel: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    color = SpaceCard,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, LiquidTeal.copy(alpha = 0.35f)),
    modifier = modifier
      .fillMaxWidth(0.94f)
      .padding(vertical = 4.dp)
      .testTag("model_ready_status_card")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (modelStatus.isModelLoaded) LiquidTeal else LiquidCyan)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "LFM3-Omni-2B Bereit",
              color = Color.White,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (modelStatus.isModelLoaded) "• Im RAM aktiv" else "• Auf Gerät installiert",
              color = if (modelStatus.isModelLoaded) LiquidTeal else LiquidCyan,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium
            )
          }
          Text(
            text = "${modelStatus.modelFileName ?: "Abliter8 Q4_K_M"} (${String.format(Locale.US, "%.1f", modelStatus.modelFileSizeMb)} MB) • Vision + 24kHz Speech",
            color = Color.Gray,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Surface(
        onClick = onStartCall,
        color = LiquidTeal.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, LiquidTeal.copy(alpha = 0.5f)),
        modifier = Modifier.testTag("start_call_from_card_btn")
      ) {
        Text(
          text = "START",
          color = LiquidTeal,
          fontSize = 10.sp,
          fontWeight = FontWeight.Black,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
      }
    }
  }
}

/**
 * Live status bar during active call indicating current model execution phase
 * AND active temporal video/audio context telemetry.
 */
@Composable
fun ModelGenerationStatusCard(
  status: OmniStatus,
  executionPhase: LfmExecutionPhase,
  isMuted: Boolean,
  userVoiceRms: Float,
  isCameraActive: Boolean = false,
  visionTelemetry: com.example.vision.VisionStreamTelemetry = com.example.vision.VisionStreamTelemetry(),
  modifier: Modifier = Modifier
) {
  val visionSuffix = if (isCameraActive && visionTelemetry.bufferedFramesCount > 0) {
    " • Video: ${visionTelemetry.selectedKeyframesCount}F (${String.format(Locale.US, "%.1f", visionTelemetry.windowDurationSeconds)}s @ ${visionTelemetry.targetFps.toInt()}fps)"
  } else if (isCameraActive) {
    " • Video-Stream (2 FPS)"
  } else {
    ""
  }

  val (badgeColor, badgeTitle, badgeDesc) = when {
    status == OmniStatus.THINKING || executionPhase == LfmExecutionPhase.CONV_PREFILL ->
      Triple(LiquidAmber, "VERARBEITEN", "Multimodaler Prefill (Audio$visionSuffix)")
    status == OmniStatus.SPEAKING || executionPhase == LfmExecutionPhase.OMNI_DECODE ->
      Triple(LiquidCyan, "ANTWORTEN", "Sprachausgabe & Sync-Untertitel$visionSuffix")
    status == OmniStatus.LISTENING ->
      if (!isMuted && userVoiceRms > 0.08f) Triple(LiquidTeal, "ZUHÖREN (AKTIV)", "Live-Spracheingabe$visionSuffix")
      else if (isMuted) Triple(LiquidTeal.copy(alpha = 0.7f), "BEREIT", "Wartet auf Eingabe$visionSuffix")
      else Triple(LiquidTeal.copy(alpha = 0.7f), "ZUHÖREN", "Bereit für Sprache$visionSuffix")
    else -> Triple(Color.Gray, "BEREIT", "Wartet auf Eingabe$visionSuffix")
  }

  Surface(
    color = SpaceDark,
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f)),
    modifier = modifier
      .fillMaxWidth(0.94f)
      .padding(vertical = 3.dp)
      .testTag("model_generation_status_card")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(badgeColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = badgeTitle,
          color = badgeColor,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.5.sp
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = badgeDesc,
        color = Color.LightGray,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

private fun cleanSubtitleDisplayText(raw: String): String {
  if (raw.isBlank()) return ""
  return raw
    .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
    .replace(Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
    .replace("</think>", "", ignoreCase = true)
    .replace("<think>", "", ignoreCase = true)
    .replace("<|think|>", "", ignoreCase = true)
    .replace("</|think|>", "", ignoreCase = true)
    .trim()
}

/**
 * Rahmenloser, minimalistischer Untertitel für die erkannte Nutzersprache
 * direkt UNTER der Audio-Welle (ohne Hintergrund-Pane, ohne Umrandung, ohne Technik-Labels).
 */
@Composable
fun UserLiveTranscriptCard(
  liveTranscript: String,
  lastTranscript: String = "",
  isCallActive: Boolean = false,
  isMuted: Boolean = false,
  isCameraActive: Boolean = false,
  visionFramesCount: Int = 0,
  visionWindowSec: Float = 0f,
  modifier: Modifier = Modifier
) {
  if (!isCallActive) return

  val rawCandidate = if (liveTranscript.isNotBlank()) liveTranscript else lastTranscript
  val cleaned = cleanSubtitleDisplayText(rawCandidate)
  // Verberge interne Telemetrie-Platzhalter wie "Spracheingabe (2.1s • 16kHz Audio)"
  val displayTranscript = if (cleaned.startsWith("Spracheingabe (")) "" else cleaned
  if (displayTranscript.isBlank()) return

  Box(
    modifier = modifier
      .fillMaxWidth(0.88f)
      .padding(vertical = 4.dp)
      .testTag("user_live_transcript_card"),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = displayTranscript,
      color = Color.White.copy(alpha = 0.62f),
      fontSize = 14.sp,
      lineHeight = 20.sp,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * Rahmenloser, synchronisierter Untertitel der Modell-Antwort direkt ÜBER der Audio-Welle.
 * Komplett ohne Hintergrund-Box, ohne farbige Umrandung und ohne Header-Beschriftungen.
 */
@Composable
fun AiResponseTranscriptCard(
  text: String,
  isStreaming: Boolean,
  spokenCharCount: Int = 0,
  latencyMs: Long,
  isCallActive: Boolean = false,
  onReplayClick: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  if (!isCallActive) return
  val cleanText = cleanSubtitleDisplayText(text)
  if (cleanText.isBlank()) return

  val styledSubtitle = androidx.compose.runtime.remember(cleanText, isStreaming, spokenCharCount) {
    androidx.compose.ui.text.buildAnnotatedString {
      if (!isStreaming || spokenCharCount <= 0 || spokenCharCount >= cleanText.length) {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.94f)))
        append(cleanText)
        pop()
      } else {
        val activeEnd = spokenCharCount.coerceIn(0, cleanText.length)
        val currentWordStart = cleanText.lastIndexOf(' ', (activeEnd - 2).coerceAtLeast(0))
          .let { if (it == -1) 0 else it + 1 }
        val currentWordEnd = cleanText.indexOf(' ', activeEnd)
          .let { if (it == -1) cleanText.length else it }

        if (currentWordStart > 0) {
          pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.94f)))
          append(cleanText.substring(0, currentWordStart))
          pop()
        }
        if (currentWordEnd > currentWordStart) {
          pushStyle(
            androidx.compose.ui.text.SpanStyle(
              color = LiquidCyan,
              fontWeight = FontWeight.SemiBold
            )
          )
          append(cleanText.substring(currentWordStart, currentWordEnd))
          pop()
        }
        if (currentWordEnd < cleanText.length) {
          pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.45f)))
          append(cleanText.substring(currentWordEnd))
          pop()
        }
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth(0.88f)
      .padding(vertical = 6.dp)
      .let { mod ->
        if (!isStreaming && onReplayClick != null) {
          mod.clickable { onReplayClick(cleanText) }
        } else mod
      }
      .testTag("ai_transcript_card"),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = styledSubtitle,
      color = Color.White,
      fontSize = 16.sp,
      fontWeight = FontWeight.Normal,
      lineHeight = 23.sp,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      maxLines = 4,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * Diagnostic log bottom sheet rendering real-time C++ JNI & file execution logs.
 * Provides granular insights into hardware mapping and parameter attempts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeDiagnosticsSheet(
  diagnosticLogs: List<String>,
  onDismiss: () -> Unit,
  onClear: () -> Unit = {},
  onCopy: () -> Unit = {}
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SpaceDark,
    contentColor = Color.White
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.80f)
        .padding(horizontal = 20.dp)
        .padding(bottom = 20.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "NATIVE C++ DIAGNOSE-LOGS",
              fontSize = 15.sp,
              fontWeight = FontWeight.Black,
              color = LiquidCyan,
              letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              color = Color.White.copy(alpha = 0.1f),
              shape = RoundedCornerShape(4.dp)
            ) {
              Text(
                text = "${diagnosticLogs.size} Einträge",
                color = Color.LightGray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }
          Text(
            text = "Echtzeit GGUF-Header, RAM, File-Deskriptoren & C++ Logcat",
            fontSize = 11.sp,
            color = Color.Gray
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onCopy, modifier = Modifier.size(36.dp).testTag("copy_diagnostics_btn")) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Kopieren", tint = LiquidCyan, modifier = Modifier.size(18.dp))
          }
          IconButton(onClick = onClear, modifier = Modifier.size(36.dp).testTag("clear_diagnostics_btn")) {
            Icon(Icons.Default.Delete, contentDescription = "Leeren", tint = Color.LightGray, modifier = Modifier.size(18.dp))
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).testTag("close_diagnostics_sheet_btn")) {
            Icon(Icons.Default.Close, contentDescription = "Schließen", tint = Color.White, modifier = Modifier.size(20.dp))
          }
        }
      }

      val scrollState = rememberScrollState()
      androidx.compose.runtime.LaunchedEffect(diagnosticLogs.size) {
        if (diagnosticLogs.isNotEmpty()) {
          scrollState.animateScrollTo(scrollState.maxValue)
        }
      }

      Surface(
        color = Color(0xFF090D12),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().weight(1f)
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(scrollState)
        ) {
          if (diagnosticLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                text = "Keine Diagnose-Einträge vorhanden.\nLogs werden bei Initialisierung oder Download generiert.",
                color = Color.Gray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
            }
          } else {
            diagnosticLogs.forEach { logLine ->
              val textColor = when {
                logLine.contains("FAIL", ignoreCase = true) || 
                logLine.contains("CRITICAL", ignoreCase = true) || 
                logLine.contains("FEHLER", ignoreCase = true) ||
                logLine.contains("Error", ignoreCase = true) -> Color(0xFFFF5252)
                
                logLine.contains("SUCCESS", ignoreCase = true) || 
                logLine.contains("Erfolg", ignoreCase = true) ||
                logLine.contains("BEREIT", ignoreCase = true) -> LiquidTeal
                
                logLine.contains("INIT", ignoreCase = true) || 
                logLine.contains("START", ignoreCase = true) || 
                logLine.contains("SYS:", ignoreCase = true) ||
                logLine.contains("PATH:", ignoreCase = true) ||
                logLine.contains("FD-TEST", ignoreCase = true) -> LiquidCyan
                
                logLine.contains("[C++]", ignoreCase = true) -> Color(0xFF90CAF9)
                logLine.contains("[MODELL]", ignoreCase = true) || logLine.contains("[PROJEKTOR]", ignoreCase = true) -> LiquidAmber
                
                else -> Color.White.copy(alpha = 0.85f)
              }

              Text(
                text = logLine,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 1.dp)
              )
            }
          }
        }
      }
    }
  }
}

