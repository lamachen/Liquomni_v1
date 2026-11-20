package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ComputeBackendMode
import com.example.hardware.DeviceHardwareInfo
import com.example.model.DEFAULT_GREETING_PROMPT
import com.example.model.DEFAULT_SYSTEM_PROMPT
import com.example.model.LfmInferenceConfig
import com.example.model.LfmModelArchitecture
import com.example.ui.theme.LiquidAmber
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.LiquidViolet
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
  inferenceConfig: LfmInferenceConfig,
  architecture: LfmModelArchitecture,
  hardwareInfo: DeviceHardwareInfo?,
  backendReport: String = "",
  onRemeasureBackends: () -> Unit = {},
  availableVoices: List<String> = emptyList(),
  activeSystemTtsEngine: String = "Android System-Standard",
  onRefreshSystemTts: () -> Unit = {},
  onDismiss: () -> Unit,
  onConfigChanged: (LfmInferenceConfig) -> Unit,
  onTestVoice: () -> Unit = {},
  onOpenModelManager: () -> Unit,
  onOpenLogs: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val context = androidx.compose.ui.platform.LocalContext.current

  // Sprachausgabe-Modus & Stimm-Parameter
  var voiceGenderVal by remember(inferenceConfig.omniVoiceGender) { mutableStateOf(inferenceConfig.omniVoiceGender) }
  var outputModeVal by remember(inferenceConfig.omniOutputMode) { mutableStateOf(inferenceConfig.omniOutputMode) }
  var outputGainVal by remember(inferenceConfig.outputVolumeGain) { mutableFloatStateOf(inferenceConfig.outputVolumeGain) }
  var speechRateVal by remember(inferenceConfig.speechRate) { mutableFloatStateOf(inferenceConfig.speechRate) }
  var speechPitchVal by remember(inferenceConfig.speechPitch) { mutableFloatStateOf(inferenceConfig.speechPitch) }

  // Prompts & Thinking State
  var systemPromptText by remember(inferenceConfig.systemPrompt) { mutableStateOf(inferenceConfig.systemPrompt) }
  var greetingPromptText by remember(inferenceConfig.greetingPrompt) { mutableStateOf(inferenceConfig.greetingPrompt) }
  var enableThinkingVal by remember(inferenceConfig.enableThinking) { mutableStateOf(inferenceConfig.enableThinking) }

  // Mic & Speech Recognition State
  var micGainVal by remember(inferenceConfig.micGain) { mutableFloatStateOf(inferenceConfig.micGain) }
  var vadSensVal by remember(inferenceConfig.vadSensitivity) { mutableFloatStateOf(inferenceConfig.vadSensitivity) }
  var silenceTimeoutVal by remember(inferenceConfig.silenceTimeoutMs) { mutableLongStateOf(inferenceConfig.silenceTimeoutMs) }

  // Vision & Camera State
  var autoStartCamVal by remember(inferenceConfig.autoStartCameraOnCall) { mutableStateOf(inferenceConfig.autoStartCameraOnCall) }
  var visionFpsVal by remember(inferenceConfig.visionFps) { mutableFloatStateOf(inferenceConfig.visionFps) }
  var visionKeyframesVal by remember(inferenceConfig.visionKeyframesCount) { mutableIntStateOf(inferenceConfig.visionKeyframesCount) }
  var visionResVal by remember(inferenceConfig.visionResolution) { mutableIntStateOf(inferenceConfig.visionResolution) }

  // Sampling & Hardware State
  var tempVal by remember(inferenceConfig.temperature) { mutableFloatStateOf(inferenceConfig.temperature) }
  var topPVal by remember(inferenceConfig.topP) { mutableFloatStateOf(inferenceConfig.topP) }
  var repPenaltyVal by remember(inferenceConfig.repetitionPenalty) { mutableFloatStateOf(inferenceConfig.repetitionPenalty) }
  var maxTokensVal by remember(inferenceConfig.maxNewTokens) { mutableIntStateOf(inferenceConfig.maxNewTokens) }
  var cpuThreadsVal by remember(inferenceConfig.cpuThreads) { mutableIntStateOf(inferenceConfig.cpuThreads) }
  var backendModeVal by remember(inferenceConfig.backendMode) { mutableStateOf(inferenceConfig.backendMode) }
  var gpuLayersVal by remember(inferenceConfig.gpuLayers) { mutableIntStateOf(inferenceConfig.gpuLayers) }
  var enableSentenceSplittingVal by remember(inferenceConfig.enableSentenceSplitting) { mutableStateOf(inferenceConfig.enableSentenceSplitting) }

  fun currentFullConfig(): LfmInferenceConfig = inferenceConfig.copy(
    omniVoiceGender = voiceGenderVal,
    omniOutputMode = outputModeVal,
    outputVolumeGain = outputGainVal,
    speechRate = speechRateVal,
    speechPitch = speechPitchVal,
    selectedVoiceName = voiceGenderVal,
    systemPrompt = systemPromptText,
    greetingPrompt = greetingPromptText,
    enableThinking = enableThinkingVal,
    micGain = micGainVal,
    vadSensitivity = vadSensVal,
    silenceTimeoutMs = silenceTimeoutVal,
    autoStartCameraOnCall = autoStartCamVal,
    visionFps = visionFpsVal,
    visionKeyframesCount = visionKeyframesVal,
    visionResolution = visionResVal,
    temperature = tempVal,
    topP = topPVal,
    repetitionPenalty = repPenaltyVal,
    maxNewTokens = maxTokensVal,
    cpuThreads = cpuThreadsVal,
    backendMode = backendModeVal,
    gpuLayers = gpuLayersVal,
    enableSentenceSplitting = enableSentenceSplittingVal
  )

  val isSystemTtsSelected = outputModeVal.equals("SYSTEM_TTS", ignoreCase = true) ||
    outputModeVal.equals("TEXT", ignoreCase = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SpaceDark,
    contentColor = TextPrimary
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.90f)
        .padding(horizontal = 18.dp)
        .verticalScroll(rememberScrollState())
    ) {
      // Header
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "LFM3-Omni Optionen & Steuerung",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
          Text(
            text = "Sprachausgabe (System-TTS oder Nativ 24kHz), Prompts, Mikrofon & Vision",
            fontSize = 12.sp,
            color = TextSecondary
          )
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.testTag("close_settings_sheet_btn")
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Schließen",
            tint = Color.White
          )
        }
      }

      // =========================================================================
      // 1. SPRACHAUSGABE-MODUS: ANDROID SYSTEM-TTS (SATZWEISE) ODER NATIV 24KHZ
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = LiquidCyan,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Sprachausgabe & Stimm-Modus",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }

            OutlinedButton(
              onClick = {
                onConfigChanged(currentFullConfig())
                onTestVoice()
              },
              border = BorderStroke(1.dp, LiquidCyan),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.height(32.dp).testTag("test_voice_btn")
            ) {
              Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = LiquidCyan,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Stimme testen", fontSize = 11.sp, color = LiquidCyan, fontWeight = FontWeight.Bold)
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Text(
            text = "Ausgabe-Stream-Modus wählen:",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(
              "SYSTEM_TTS" to "System-TTS (Nur Text • Satzweise)",
              "MIXED" to "Nativ: Text + 24kHz Audio",
              "AUDIO" to "Nativ: Nur 24kHz Audio",
              "AUTO" to "Nativ: Auto"
            ).forEach { (mode, label) ->
              FilterChip(
                selected = outputModeVal.equals(mode, ignoreCase = true),
                onClick = {
                  outputModeVal = mode
                  onConfigChanged(currentFullConfig().copy(omniOutputMode = mode))
                },
                modifier = Modifier.testTag("output_mode_chip_$mode"),
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = LiquidTeal.copy(alpha = 0.22f),
                  selectedLabelColor = LiquidTeal
                )
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          if (isSystemTtsSelected) {
            Surface(
              color = SpaceDark,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, LiquidTeal.copy(alpha = 0.35f)),
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text(
                  text = "Aktive Android System-Standard-TTS:",
                  fontSize = 11.sp,
                  color = TextSecondary
                )
                Text(
                  text = activeSystemTtsEngine,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = LiquidCyan,
                  modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  OutlinedButton(
                    onClick = onRefreshSystemTts,
                    border = BorderStroke(1.dp, LiquidTeal.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                      .weight(1f)
                      .height(30.dp)
                      .testTag("refresh_system_tts_btn")
                  ) {
                    Text("Neu verbinden", fontSize = 11.sp, color = LiquidTeal)
                  }
                  OutlinedButton(
                    onClick = {
                      try {
                        val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                      } catch (_: Throwable) {}
                    },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                      .weight(1f)
                      .height(30.dp)
                      .testTag("open_android_tts_settings_btn")
                  ) {
                    Text("Android TTS-Menü", fontSize = 11.sp, color = Color.White)
                  }
                }
              }
            }

            Text(
              text = "Modell generiert reinen Text mit maximaler Geschwindigkeit. Jeder Satz wird sofort während der Generierung über deine oben angezeigte Android System-Standard-TTS gesprochen (Stimme & Sprache der Custom-TTS werden unverändert übernommen).",
              fontSize = 11.sp,
              color = LiquidTeal,
              modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "System-TTS Sprechgeschwindigkeit", fontSize = 13.sp, color = Color.White)
              Text(
                text = String.format(Locale.GERMAN, "%.2fx", speechRateVal),
                fontSize = 13.sp,
                color = LiquidCyan,
                fontWeight = FontWeight.Bold
              )
            }
            Slider(
              value = speechRateVal,
              onValueChange = {
                speechRateVal = it
                onConfigChanged(currentFullConfig().copy(speechRate = it))
              },
              valueRange = 0.70f..1.50f,
              colors = SliderDefaults.colors(thumbColor = LiquidCyan, activeTrackColor = LiquidCyan, inactiveTrackColor = SpaceDark)
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "System-TTS Tonhöhe (Pitch)", fontSize = 13.sp, color = Color.White)
              Text(
                text = String.format(Locale.GERMAN, "%.2fx", speechPitchVal),
                fontSize = 13.sp,
                color = LiquidTeal,
                fontWeight = FontWeight.Bold
              )
            }
            Slider(
              value = speechPitchVal,
              onValueChange = {
                speechPitchVal = it
                onConfigChanged(currentFullConfig().copy(speechPitch = it))
              },
              valueRange = 0.70f..1.40f,
              colors = SliderDefaults.colors(thumbColor = LiquidTeal, activeTrackColor = LiquidTeal, inactiveTrackColor = SpaceDark)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(text = "Satzweises Text-Splitting", fontSize = 13.sp, color = Color.White)
                Text(
                  text = "Wenn aktiv, spricht Satz für Satz. Wenn aus, liest den gesamten Text am Stück vor.",
                  fontSize = 11.sp,
                  color = TextSecondary
                )
              }
              Switch(
                checked = enableSentenceSplittingVal,
                onCheckedChange = {
                  enableSentenceSplittingVal = it
                  onConfigChanged(currentFullConfig().copy(enableSentenceSplitting = it))
                },
                modifier = Modifier.testTag("sentence_splitting_switch"),
                colors = SwitchDefaults.colors(
                  checkedThumbColor = LiquidCyan,
                  checkedTrackColor = LiquidCyan.copy(alpha = 0.4f)
                )
              )
            }
          } else {
            Text(
              text = "Native LFM3-Omni Stimme (Depthformer + LiquidCodec 24 kHz):",
              fontSize = 12.sp,
              color = TextSecondary,
              modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              listOf("female" to "Weiblich (Female)", "male" to "Männlich (Male)").forEach { (key, label) ->
                FilterChip(
                  selected = voiceGenderVal.equals(key, ignoreCase = true),
                  onClick = {
                    voiceGenderVal = key
                    onConfigChanged(currentFullConfig().copy(omniVoiceGender = key, selectedVoiceName = key))
                  },
                  label = { Text(label, fontSize = 11.sp) },
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = LiquidCyan.copy(alpha = 0.22f),
                    selectedLabelColor = LiquidCyan
                  )
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "24kHz Audio-Ausgangsverstärkung", fontSize = 13.sp, color = Color.White)
              Text(
                text = String.format(Locale.GERMAN, "%.2fx", outputGainVal),
                fontSize = 13.sp,
                color = LiquidCyan,
                fontWeight = FontWeight.Bold
              )
            }
            Slider(
              value = outputGainVal,
              onValueChange = {
                outputGainVal = it
                onConfigChanged(currentFullConfig().copy(outputVolumeGain = it))
              },
              valueRange = 0.75f..2.0f,
              colors = SliderDefaults.colors(thumbColor = LiquidCyan, activeTrackColor = LiquidCyan, inactiveTrackColor = SpaceDark)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // =========================================================================
      // 2. SYSTEM PROMPT & BEGRÜSSUNG (SICHTBAR & EDITIERBAR)
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidTeal.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.EditNote,
                contentDescription = null,
                tint = LiquidTeal,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "System-Prompt & Instruktionen",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }

            IconButton(
              onClick = {
                systemPromptText = DEFAULT_SYSTEM_PROMPT
                greetingPromptText = DEFAULT_GREETING_PROMPT
                enableThinkingVal = false
                onConfigChanged(
                  currentFullConfig().copy(
                    systemPrompt = DEFAULT_SYSTEM_PROMPT,
                    greetingPrompt = DEFAULT_GREETING_PROMPT,
                    enableThinking = false
                  )
                )
              },
              modifier = Modifier.size(28.dp).testTag("reset_prompts_btn")
            ) {
              Icon(
                imageVector = Icons.Default.RestartAlt,
                contentDescription = "Prompts zurücksetzen",
                tint = LiquidAmber,
                modifier = Modifier.size(18.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = "System-Prompt (Verhalten & Persönlichkeit des Modells):",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
          )
          OutlinedTextField(
            value = systemPromptText,
            onValueChange = {
              systemPromptText = it
              onConfigChanged(currentFullConfig().copy(systemPrompt = it))
            },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("system_prompt_input"),
            minLines = 3,
            maxLines = 6,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = LiquidTeal,
              unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              cursorColor = LiquidTeal
            )
          )

          Spacer(modifier = Modifier.height(10.dp))

          Text(
            text = "Anruf-Begrüßungs-Prompt (Initiale Instruktion bei Anrufstart):",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
          )
          OutlinedTextField(
            value = greetingPromptText,
            onValueChange = {
              greetingPromptText = it
              onConfigChanged(currentFullConfig().copy(greetingPrompt = it))
            },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("greeting_prompt_input"),
            minLines = 2,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = LiquidCyan,
              unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              cursorColor = LiquidCyan
            )
          )

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Modell-Denkschritte (<think>) vor Antwort erlauben",
                fontSize = 13.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
              )
              Text(
                text = "Standardmäßig AUS: Verhindert '<think></think>' Verzögerungen und Vorlese-Artefakte.",
                fontSize = 11.sp,
                color = TextSecondary
              )
            }
            Switch(
              checked = enableThinkingVal,
              onCheckedChange = {
                enableThinkingVal = it
                onConfigChanged(currentFullConfig().copy(enableThinking = it))
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = LiquidCyan,
                checkedTrackColor = LiquidCyan.copy(alpha = 0.35f)
              )
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // =========================================================================
      // 3. MIKROFON & SPRACHERKENNUNG (EMPFINDLICHKEIT FÜR LEISES SPRECHEN)
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidAmber.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Mic,
              contentDescription = null,
              tint = LiquidAmber,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Mikrofon-Empfindlichkeit & Spracherkennung",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Mikrofon-Vorverstärkung (Mic Gain)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Mikrofon-Vorverstärkung (Leises Sprechen)", fontSize = 13.sp, color = Color.White)
            Text(
              text = String.format(Locale.GERMAN, "%.1fx Gain", micGainVal),
              fontSize = 13.sp,
              color = LiquidAmber,
              fontWeight = FontWeight.Bold
            )
          }
          Slider(
            value = micGainVal,
            onValueChange = {
              micGainVal = it
              onConfigChanged(currentFullConfig().copy(micGain = it))
            },
            valueRange = 1.0f..5.0f,
            colors = SliderDefaults.colors(thumbColor = LiquidAmber, activeTrackColor = LiquidAmber, inactiveTrackColor = SpaceDark)
          )

          // VAD Sprachaktivierungs-Schwelle
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Mindest-Lautstärke Schwelle (VAD)", fontSize = 13.sp, color = Color.White)
            Text(
              text = String.format(Locale.GERMAN, "%.3f (Niedrig = Sensibler)", vadSensVal),
              fontSize = 13.sp,
              color = LiquidCyan
            )
          }
          Slider(
            value = vadSensVal,
            onValueChange = {
              vadSensVal = it
              onConfigChanged(currentFullConfig().copy(vadSensitivity = it))
            },
            valueRange = 0.015f..0.150f,
            colors = SliderDefaults.colors(thumbColor = LiquidCyan, activeTrackColor = LiquidCyan, inactiveTrackColor = SpaceDark)
          )

          // Sprechpausen-Timeout
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Sprechpausen-Timeout (Satzende)", fontSize = 13.sp, color = Color.White)
            Text(
              text = "${silenceTimeoutVal} ms",
              fontSize = 13.sp,
              color = LiquidTeal
            )
          }
          Slider(
            value = silenceTimeoutVal.toFloat(),
            onValueChange = {
              silenceTimeoutVal = it.toLong()
              onConfigChanged(currentFullConfig().copy(silenceTimeoutMs = it.toLong()))
            },
            valueRange = 600f..2500f,
            colors = SliderDefaults.colors(thumbColor = LiquidTeal, activeTrackColor = LiquidTeal, inactiveTrackColor = SpaceDark)
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // =========================================================================
      // 4. KAMERA & LIVE-VISION EINSTELLUNGEN
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidViolet.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Videocam,
              contentDescription = null,
              tint = LiquidViolet,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Live-Kamera & Vision-Pipeline",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Kamera automatisch bei Anruf aktivieren",
                fontSize = 13.sp,
                color = Color.White
              )
              Text(
                text = "Sendet kontinuierlich echte Live-Videoframes an den Vision-Projektor",
                fontSize = 11.sp,
                color = TextSecondary
              )
            }
            Switch(
              checked = autoStartCamVal,
              onCheckedChange = {
                autoStartCamVal = it
                onConfigChanged(currentFullConfig().copy(autoStartCameraOnCall = it))
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = LiquidTeal,
                checkedTrackColor = LiquidTeal.copy(alpha = 0.35f)
              )
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Vision FPS
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Video-Stream Abtastrate (FPS)", fontSize = 13.sp, color = Color.White)
            Text(
              text = String.format(Locale.GERMAN, "%.1f FPS", visionFpsVal),
              fontSize = 13.sp,
              color = LiquidCyan
            )
          }
          Slider(
            value = visionFpsVal,
            onValueChange = {
              visionFpsVal = it
              onConfigChanged(currentFullConfig().copy(visionFps = it))
            },
            valueRange = 0.5f..4.0f,
            steps = 6,
            colors = SliderDefaults.colors(thumbColor = LiquidCyan, activeTrackColor = LiquidCyan, inactiveTrackColor = SpaceDark)
          )

          // Vision Keyframes pro Antwort
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Zeitliche Video-Keyframes pro Antwort", fontSize = 13.sp, color = Color.White)
            Text(
              text = "$visionKeyframesVal Frames",
              fontSize = 13.sp,
              color = LiquidTeal
            )
          }
          Slider(
            value = visionKeyframesVal.toFloat(),
            onValueChange = {
              visionKeyframesVal = it.toInt()
              onConfigChanged(currentFullConfig().copy(visionKeyframesCount = it.toInt()))
            },
            valueRange = 1f..6f,
            steps = 4,
            colors = SliderDefaults.colors(thumbColor = LiquidTeal, activeTrackColor = LiquidTeal, inactiveTrackColor = SpaceDark)
          )

          // Vision Auflösung
          Text(
            text = "Vision-Encoder Bildauflösung:",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(256, 384, 448, 512).forEach { res ->
              FilterChip(
                selected = visionResVal == res,
                onClick = {
                  visionResVal = res
                  onConfigChanged(currentFullConfig().copy(visionResolution = res))
                },
                label = { Text("${res}px", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = LiquidViolet.copy(alpha = 0.25f),
                  selectedLabelColor = Color.White
                )
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // =========================================================================
      // 5. INFERENZ- & SAMPLING-PARAMETER
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Tune,
              contentDescription = null,
              tint = LiquidCyan,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Sampling & C++ Engine Parameter",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Temperature
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Temperatur", fontSize = 13.sp, color = Color.White)
            Text(text = String.format(Locale.GERMAN, "%.2f", tempVal), fontSize = 13.sp, color = LiquidCyan)
          }
          Slider(
            value = tempVal,
            onValueChange = {
              tempVal = it
              onConfigChanged(currentFullConfig().copy(temperature = it))
            },
            valueRange = 0.0f..1.5f,
            colors = SliderDefaults.colors(thumbColor = LiquidCyan, activeTrackColor = LiquidCyan, inactiveTrackColor = SpaceDark)
          )

          // Top-P
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Top-P (Nucleus Sampling)", fontSize = 13.sp, color = Color.White)
            Text(text = String.format(Locale.GERMAN, "%.2f", topPVal), fontSize = 13.sp, color = LiquidViolet)
          }
          Slider(
            value = topPVal,
            onValueChange = {
              topPVal = it
              onConfigChanged(currentFullConfig().copy(topP = it))
            },
            valueRange = 0.1f..1.0f,
            colors = SliderDefaults.colors(thumbColor = LiquidViolet, activeTrackColor = LiquidViolet, inactiveTrackColor = SpaceDark)
          )

          // Repetition Penalty
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Wiederholungsstrafe (Repetition Penalty)", fontSize = 13.sp, color = Color.White)
            Text(text = String.format(Locale.GERMAN, "%.2f", repPenaltyVal), fontSize = 13.sp, color = LiquidAmber)
          }
          Slider(
            value = repPenaltyVal,
            onValueChange = {
              repPenaltyVal = it
              onConfigChanged(currentFullConfig().copy(repetitionPenalty = it))
            },
            valueRange = 1.00f..1.50f,
            colors = SliderDefaults.colors(thumbColor = LiquidAmber, activeTrackColor = LiquidAmber, inactiveTrackColor = SpaceDark)
          )

          // Max New Tokens
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "Max. Antwort-Länge (Tokens)", fontSize = 13.sp, color = Color.White)
            Text(text = "$maxTokensVal Tokens", fontSize = 13.sp, color = LiquidCyan)
          }
          Slider(
            value = maxTokensVal.toFloat(),
            onValueChange = {
              maxTokensVal = it.toInt()
              onConfigChanged(currentFullConfig().copy(maxNewTokens = it.toInt()))
            },
            valueRange = 64f..512f,
            colors = SliderDefaults.colors(thumbColor = LiquidCyan, activeTrackColor = LiquidCyan, inactiveTrackColor = SpaceDark)
          )

          // CPU Threads
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(text = "CPU Inferenz-Threads", fontSize = 13.sp, color = Color.White)
            Text(
              text = if (cpuThreadsVal == 0) "Auto (nach gemessener Topologie)" else "$cpuThreadsVal Threads",
              fontSize = 13.sp,
              color = LiquidTeal
            )
          }
          Slider(
            value = cpuThreadsVal.toFloat(),
            onValueChange = {
              cpuThreadsVal = it.toInt()
              onConfigChanged(currentFullConfig().copy(cpuThreads = it.toInt()))
            },
            valueRange = 0f..8f,
            steps = 7,
            colors = SliderDefaults.colors(thumbColor = LiquidTeal, activeTrackColor = LiquidTeal, inactiveTrackColor = SpaceDark)
          )
          Text(
            text = "0 bedeutet: die App zählt beim Laden die schnellen Kerne über /sys und wählt selbst. Ein Wert über der Zahl physisch schneller Kerne wird nach unten begrenzt.",
            fontSize = 10.sp,
            color = TextSecondary
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // =========================================================================
      // 5b. RECHEN-BACKEND (GPU / CPU) — Messung statt Annahme
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidAmber.copy(alpha = 0.25f)),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("compute_backend_section")
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Memory,
              contentDescription = null,
              tint = LiquidAmber,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Rechen-Backend (GPU / CPU)",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "GPU-Beschleunigung gibt es nur, wenn die geladene libllama.so ein GPU-Backend einkompiliert hat. Was dein Gerät wirklich bietet, wird beim Modell-Laden über die Engine abgefragt und unten angezeigt — n_gpu_layers wird daraus abgeleitet, nicht geraten.",
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp)
          )

          Text(
            text = "Modus:",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(
              ComputeBackendMode.AUTO to "Automatisch (GPU, wenn verfügbar)",
              ComputeBackendMode.GPU to "GPU erzwingen",
              ComputeBackendMode.CPU to "Nur CPU"
            ).forEach { (mode, label) ->
              FilterChip(
                selected = backendModeVal == mode,
                onClick = {
                  backendModeVal = mode
                  onConfigChanged(currentFullConfig().copy(backendMode = mode))
                },
                modifier = Modifier.testTag("backend_mode_chip_$mode"),
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = LiquidAmber.copy(alpha = 0.22f),
                  selectedLabelColor = LiquidAmber
                )
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = "Ausgelagerte Schichten (n_gpu_layers)",
              fontSize = 13.sp,
              color = Color.White
            )
            Text(
              text = if (gpuLayersVal >= 99) "99 = alle" else "$gpuLayersVal",
              fontSize = 13.sp,
              color = LiquidAmber
            )
          }
          Slider(
            value = gpuLayersVal.toFloat(),
            onValueChange = {
              gpuLayersVal = it.toInt()
              onConfigChanged(currentFullConfig().copy(gpuLayers = it.toInt()))
            },
            valueRange = 0f..99f,
            enabled = backendModeVal != ComputeBackendMode.CPU,
            colors = SliderDefaults.colors(thumbColor = LiquidAmber, activeTrackColor = LiquidAmber, inactiveTrackColor = SpaceDark)
          )

          if (backendModeVal == ComputeBackendMode.CPU) {
            Text(
              text = "Modus \"Nur CPU\": es werden immer 0 Schichten ausgelagert, unabhängig von diesem Regler.",
              fontSize = 11.sp,
              color = TextSecondary
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          Surface(
            color = SpaceDark,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 300.dp)
              .testTag("backend_report_box")
          ) {
            Column(
              modifier = Modifier
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
            ) {
              Text(
                text = if (backendReport.isBlank())
                  "Noch nicht gemessen. Der Bericht erscheint, sobald ein Modell geladen wurde — oder nach \"Backend neu messen\"."
                else backendReport,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          OutlinedButton(
            onClick = onRemeasureBackends,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("remeasure_backends_btn"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LiquidAmber)
          ) {
            Icon(
              imageVector = Icons.Default.RestartAlt,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Backend neu messen", fontWeight = FontWeight.SemiBold)
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Neu messen fragt die Engine-Geräte erneut ab, ohne das Modell neu zu laden. Die Entscheidung wirkt beim nächsten Modell-Laden.",
            fontSize = 10.sp,
            color = TextSecondary
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // =========================================================================
      // 6. HARDWARE & ARCHITEKTUR ÜBERSICHT
      // =========================================================================
      Surface(
        color = SpaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Memory,
              contentDescription = null,
              tint = LiquidCyan,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Geräte-Hardware & Architektur",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          HardwareDetailRow("Modell", "${architecture.modelName} (${architecture.totalLayers}L Tri-Hybrid)")
          hardwareInfo?.let { hi ->
            HardwareDetailRow("Gerät / SoC", "${hi.deviceModel} (${hi.socFamily})")
            HardwareDetailRow("CPU / SIMD", "${hi.cpuCores} Kerne • ${hi.simdArchitecture}")
            HardwareDetailRow("RAM (Frei / Total)", String.format(Locale.GERMAN, "%.1f GB / %.1f GB", hi.freeRamGb, hi.totalRamGb))
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      FilledTonalButton(
        onClick = {
          onDismiss()
          onOpenModelManager()
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
          .testTag("open_model_mgr_from_settings_btn"),
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = SpaceCard,
          contentColor = LiquidTeal
        )
      ) {
        Icon(
          imageVector = Icons.Default.Folder,
          contentDescription = "Modell-Manager",
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "GGUF Modell-Dateien verwalten", fontWeight = FontWeight.SemiBold)
      }

      FilledTonalButton(
        onClick = {
          onDismiss()
          onOpenLogs()
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
          .testTag("open_logs_from_settings_btn"),
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = SpaceCard,
          contentColor = LiquidCyan
        )
      ) {
        Icon(
          imageVector = Icons.Default.Tune,
          contentDescription = "Diagnose",
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "Native C++ Diagnose-Logs anzeigen", fontWeight = FontWeight.SemiBold)
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun HardwareDetailRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 1.dp),
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(text = label, fontSize = 11.sp, color = TextSecondary)
    Text(text = value, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
  }
}
