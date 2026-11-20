package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DownloadProgressInfo
import com.example.model.GgufModelCatalog
import com.example.model.GgufModelVariant
import com.example.model.LocalModelFile
import com.example.model.ModelStorageStatus
import com.example.ui.theme.LiquidAmber
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.OmniViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GgufModelManagerDialog(
  viewModel: OmniViewModel,
  localModels: List<LocalModelFile>,
  modelStorageStatus: ModelStorageStatus,
  activeDownloads: Map<String, DownloadProgressInfo> = emptyMap(),
  onDismiss: () -> Unit,
  onDownloadModel: (GgufModelVariant) -> Unit,
  onRefreshScan: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val hasMainModel = localModels.any { !it.isProjector && it.sizeMb > 50 } || modelStorageStatus.isModelFilePresent
  val hasProjector = localModels.any { it.isProjector && it.sizeMb > 50 } || modelStorageStatus.isProjectorPresent

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SpaceDark,
    contentColor = TextPrimary
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.88f)
        .padding(horizontal = 20.dp)
        .verticalScroll(rememberScrollState())
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "MODELL-MANAGER",
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            letterSpacing = 1.sp
          )
          Text(
            text = "abliter8-ai/lfm3-2b-omni-abliter8-gguf",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = LiquidCyan
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onRefreshScan,
            modifier = Modifier.size(36.dp).testTag("refresh_model_scan_btn")
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Aktualisieren",
              tint = LiquidCyan,
              modifier = Modifier.size(20.dp)
            )
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp).testTag("close_model_mgr_btn")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Schließen",
              tint = Color.White,
              modifier = Modifier.size(20.dp)
            )
          }
        }
      }

      // Status summary banner
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
          1.dp,
          if (hasMainModel && hasProjector) LiquidTeal.copy(alpha = 0.6f) else LiquidAmber.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(10.dp)
              .background(
                when {
                  modelStorageStatus.isModelLoaded -> LiquidTeal
                  hasMainModel && hasProjector -> LiquidCyan
                  else -> Color(0xFFFF9800)
                },
                CircleShape
              )
          )
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = when {
                modelStorageStatus.isModelLoaded -> "LFM3-Omni Abliter8 aktiv (Speech-to-Speech)"
                hasMainModel && hasProjector -> "Beide Komponenten installiert (Vollständig)"
                hasMainModel -> "Omni-Projektor (mmproj) fehlt noch!"
                else -> "Kein Modell installiert"
              },
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
            Text(
              text = when {
                hasMainModel && hasProjector -> "Hauptmodell (${String.format(Locale.US, "%.1f", modelStorageStatus.modelFileSizeMb)} MB) + Omni-Projektor (${String.format(Locale.US, "%.1f", modelStorageStatus.projectorFileSizeMb)} MB)"
                hasMainModel -> "Hauptmodell vorhanden (${String.format(Locale.US, "%.1f", modelStorageStatus.modelFileSizeMb)} MB). Bitte mmproj-Projektor für Vision & 24kHz Sprachausgabe herunterladen."
                else -> "Bitte Hauptmodell + Omni-Projektor aus abliter8-ai/lfm3-2b-omni-abliter8-gguf herunterladen."
              },
              fontSize = 11.sp,
              color = TextSecondary
            )
          }
        }
      }

      // SECTION 1: HAUPTMODELLE (ABLITER8-AI)
      Text(
        text = "HAUPTMODELL (LFM3-OMNI-2B-ABLITER8)",
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        color = LiquidCyan,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
      )

      GgufModelCatalog.mainModelVariants.forEach { variant ->
        ModelVariantItemCard(
          variant = variant,
          localModels = localModels,
          activeProgress = activeDownloads[variant.fileName],
          viewModel = viewModel,
          onDownloadModel = onDownloadModel
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      // SECTION 2: OMNI-PROJEKTOR (ERFORDERLICH FÜR VISION + AUDIO IN + 24KHZ SPEECH OUT)
      Text(
        text = "OMNI-PROJEKTOR (VISION + 16KHZ AUDIO IN + 24KHZ SPEECH OUT)",
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        color = LiquidTeal,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
      )

      GgufModelCatalog.projectorVariants.forEach { variant ->
        ModelVariantItemCard(
          variant = variant,
          localModels = localModels,
          activeProgress = activeDownloads[variant.fileName],
          viewModel = viewModel,
          onDownloadModel = onDownloadModel
        )
      }

      Spacer(modifier = Modifier.height(28.dp))
    }
  }
}

@Composable
private fun ModelVariantItemCard(
  variant: GgufModelVariant,
  localModels: List<LocalModelFile>,
  activeProgress: DownloadProgressInfo?,
  viewModel: OmniViewModel,
  onDownloadModel: (GgufModelVariant) -> Unit
) {
  val localFile = localModels.find { local ->
    local.isProjector == variant.isMultimodalProjector &&
    (local.name.equals(variant.fileName, ignoreCase = true) ||
     local.file.name.equals(variant.fileName, ignoreCase = true))
  }
  val isInstalled = localFile != null
  val isCompleteAndValid = localFile != null && localFile.isComplete && localFile.isValidGguf

  Surface(
    color = SpaceCard,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
  ) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = variant.displayName,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
            if (isCompleteAndValid) {
              Spacer(modifier = Modifier.width(6.dp))
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Installiert",
                tint = LiquidTeal,
                modifier = Modifier.size(14.dp)
              )
            }
          }
          Text(
            text = when {
              isCompleteAndValid -> {
                val sizeStr = if ((localFile?.sizeMb ?: 0f) >= 1000f) {
                  String.format(Locale.US, "%.1f MB (%.2f GB)", localFile?.sizeMb, (localFile?.sizeMb ?: 0f) / 1024f)
                } else {
                  String.format(Locale.US, "%.1f MB", localFile?.sizeMb)
                }
                "$sizeStr • Vollständig & Gültig"
              }
              isInstalled -> {
                "${String.format(Locale.US, "%.1f", localFile?.sizeMb ?: 0f)} MB • ${localFile?.validationMessage ?: "Unvollständig"}"
              }
              else -> {
                "Dateigröße: ${variant.sizeFormatted}"
              }
            },
            fontSize = 11.sp,
            color = when {
              isCompleteAndValid -> LiquidTeal
              isInstalled -> Color(0xFFEA4335)
              else -> TextSecondary
            }
          )
        }

        when {
          activeProgress != null && activeProgress.isDownloading -> {
            FilledTonalButton(
              onClick = { viewModel.cancelDownload(variant.fileName) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color(0xFFEA4335).copy(alpha = 0.2f),
                contentColor = Color(0xFFEA4335)
              ),
              modifier = Modifier.height(34.dp).testTag("cancel_dl_${variant.fileName}")
            ) {
              Text("STOPP", fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
          }
          isInstalled && !isCompleteAndValid -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              FilledTonalButton(
                onClick = { onDownloadModel(variant) },
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = Color(0xFFEA4335).copy(alpha = 0.2f),
                  contentColor = Color(0xFFEA4335)
                ),
                modifier = Modifier.height(34.dp).testTag("redownload_${variant.fileName}")
              ) {
                Text("NEU LADEN", fontSize = 10.sp, fontWeight = FontWeight.Black)
              }
              IconButton(
                onClick = { viewModel.deleteModel(localFile!!) },
                modifier = Modifier.size(32.dp).testTag("delete_${variant.fileName}")
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Löschen",
                  tint = Color.Gray,
                  modifier = Modifier.size(18.dp)
                )
              }
            }
          }
          isInstalled -> {
            IconButton(
              onClick = { viewModel.deleteModel(localFile!!) },
              modifier = Modifier.size(32.dp).testTag("delete_${variant.fileName}")
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Löschen",
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
              )
            }
          }
          else -> {
            FilledTonalButton(
              onClick = { onDownloadModel(variant) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SpaceDark,
                contentColor = LiquidCyan
              ),
              modifier = Modifier.height(34.dp).testTag("download_${variant.fileName}")
            ) {
              Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Herunterladen",
                tint = LiquidCyan,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("LADEN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      if (activeProgress != null && activeProgress.isDownloading) {
        Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
          LinearProgressIndicator(
            progress = { activeProgress.progressFraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = LiquidCyan,
            trackColor = SpaceDark
          )
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = String.format(
                Locale.US,
                "%.1f MB / %.1f MB (%.0f%%)",
                activeProgress.downloadedMb,
                activeProgress.totalMb,
                activeProgress.progressFraction * 100f
              ),
              fontSize = 10.sp,
              color = Color.White.copy(alpha = 0.9f)
            )
            Text(
              text = String.format(
                Locale.US,
                "%.2f MB/s • Restzeit: %s",
                activeProgress.speedMbPerSec,
                formatEta(activeProgress.etaSeconds)
              ),
              fontSize = 10.sp,
              color = LiquidTeal
            )
          }
        }
      }
    }
  }
}

private fun formatEta(seconds: Long): String {
  if (seconds <= 0) return "Berechnung..."
  val mins = seconds / 60
  val secs = seconds % 60
  return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}


