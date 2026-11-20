package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.LiquidViolet
import kotlin.math.PI
import kotlin.math.sin

/**
 * Rahmenlose, kontinuierliche Echtzeit-Audio-Wellenform (Oszilloskop- / Sinus-Harmonische Welle).
 *
 * Komplett frei von Hintergrund-Boxen, Umrandungen oder Text-Labels:
 * - Im Ruhezustand während des Anrufs: eine hauchdünne, sanft atmende Horizontlinie.
 * - Bei Nutzersprache (16 kHz PCM) oder Modell-Sprachausgabe (24 kHz PCM):
 *   mehrschichtige, fließende Wellenpfade, die direkt durch das reale Frequenz-/Amplitudenprofil
 *   gesteuert werden.
 */
@Composable
fun UserVoiceAmplitudeVisualizer(
  amplitude: Float,
  waveform: List<Float>,
  isValidSpeech: Boolean,
  isMuted: Boolean,
  isCallActive: Boolean,
  aiSpeechAmplitude: Float = 0f,
  isAiSpeaking: Boolean = false,
  vadThreshold: Float = 0.045f,
  modifier: Modifier = Modifier
) {
  if (!isCallActive) return

  val isUserActive = !isMuted && (amplitude >= vadThreshold || isValidSpeech)
  val rawActiveAmp = when {
    isAiSpeaking -> aiSpeechAmplitude.coerceIn(0f, 1f)
    !isMuted -> amplitude.coerceIn(0f, 1f)
    else -> 0f
  }

  val smoothedAmp by animateFloatAsState(
    targetValue = rawActiveAmp,
    animationSpec = tween(durationMillis = 65, easing = LinearEasing),
    label = "SmoothedWaveAmp"
  )

  val infiniteTransition = rememberInfiniteTransition(label = "AudioWavePhase")
  val phase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = (2.0 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1100, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "WavePhase"
  )

  val primaryWaveColor by animateColorAsState(
    targetValue = when {
      isAiSpeaking -> LiquidCyan
      isUserActive -> LiquidTeal
      isMuted -> Color.White.copy(alpha = 0.16f)
      else -> Color.White.copy(alpha = 0.35f)
    },
    animationSpec = tween(durationMillis = 150),
    label = "PrimaryWaveColor"
  )

  val secondaryWaveColor by animateColorAsState(
    targetValue = when {
      isAiSpeaking -> LiquidViolet
      isUserActive -> LiquidCyan
      else -> LiquidTeal.copy(alpha = 0.20f)
    },
    animationSpec = tween(durationMillis = 150),
    label = "SecondaryWaveColor"
  )

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(52.dp)
      .testTag("center_line_audio_wave_visualizer"),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val width = size.width
      val height = size.height
      val midY = height / 2f
      val maxDisplacement = height * 0.44f

      val edgeFadeBrush = Brush.horizontalGradient(
        0.0f to Color.Transparent,
        0.12f to primaryWaveColor,
        0.50f to primaryWaveColor,
        0.88f to primaryWaveColor,
        1.0f to Color.Transparent
      )

      val secondaryFadeBrush = Brush.horizontalGradient(
        0.0f to Color.Transparent,
        0.15f to secondaryWaveColor.copy(alpha = 0.55f),
        0.50f to secondaryWaveColor.copy(alpha = 0.70f),
        0.85f to secondaryWaveColor.copy(alpha = 0.55f),
        1.0f to Color.Transparent
      )

      // Wenn stummgeschaltet oder nahezu völlige Stille herrscht: dezente, ruhige Horizontlinie
      if (smoothedAmp < 0.015f && !isAiSpeaking && !isUserActive) {
        drawLine(
          brush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.20f to primaryWaveColor.copy(alpha = 0.28f),
            0.50f to primaryWaveColor.copy(alpha = 0.48f),
            0.80f to primaryWaveColor.copy(alpha = 0.28f),
            1.0f to Color.Transparent
          ),
          start = Offset(0f, midY),
          end = Offset(width, midY),
          strokeWidth = 1.2.dp.toPx(),
          cap = StrokeCap.Round
        )
        return@Canvas
      }

      val points = 72
      val stepX = width / points.toFloat()

      fun sampleBandAmplitude(normX: Float): Float {
        if (waveform.isEmpty() || isAiSpeaking) {
          return smoothedAmp.coerceAtLeast(if (isAiSpeaking) 0.14f else 0.04f)
        }
        val exactIdx = (normX * (waveform.size - 1)).coerceIn(0f, (waveform.size - 1).toFloat())
        val idx0 = exactIdx.toInt()
        val idx1 = (idx0 + 1).coerceAtMost(waveform.lastIndex)
        val frac = exactIdx - idx0
        val interp = waveform[idx0] * (1f - frac) + waveform[idx1] * frac
        return (interp * 0.65f + smoothedAmp * 0.35f).coerceIn(0.03f, 1.0f)
      }

      fun buildSmoothWavePath(
        frequency: Float,
        phaseShift: Float,
        amplitudeScale: Float
      ): Path {
        val path = Path()
        var prevX = 0f
        var prevY = midY
        for (i in 0..points) {
          val normX = i.toFloat() / points.toFloat()
          val x = i * stepX
          // Hanning-ähnliche Fensterung: sanftes Auslaufen zu den Bildschirmrändern
          val window = sin(normX * PI).toFloat().let { it * it }
          val localEnergy = sampleBandAmplitude(normX)
          val carrier = sin((normX * frequency * 2.0 * PI) - phase + phaseShift).toFloat()
          val y = midY + carrier * localEnergy * window * maxDisplacement * amplitudeScale

          if (i == 0) {
            path.moveTo(x, y)
          } else {
            val ctrlX = (prevX + x) / 2f
            path.cubicTo(ctrlX, prevY, ctrlX, y, x, y)
          }
          prevX = x
          prevY = y
        }
        return path
      }

      // 1. Tertiäre Oberwelle (weicher Hintergrund-Schimmer)
      val tertiaryPath = buildSmoothWavePath(
        frequency = 3.6f,
        phaseShift = 2.2f,
        amplitudeScale = 0.45f
      )
      drawPath(
        path = tertiaryPath,
        brush = Brush.horizontalGradient(
          0.0f to Color.Transparent,
          0.20f to primaryWaveColor.copy(alpha = 0.22f),
          0.80f to secondaryWaveColor.copy(alpha = 0.22f),
          1.0f to Color.Transparent
        ),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      )

      // 2. Sekundäre gegenläufige Harmonische
      val secondaryPath = buildSmoothWavePath(
        frequency = 2.4f,
        phaseShift = -phase * 1.6f + 1.1f,
        amplitudeScale = 0.72f
      )
      drawPath(
        path = secondaryPath,
        brush = secondaryFadeBrush,
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      )

      // 3. Primäre Trägerwelle
      val primaryPath = buildSmoothWavePath(
        frequency = 1.8f,
        phaseShift = 0f,
        amplitudeScale = 1.0f
      )
      drawPath(
        path = primaryPath,
        brush = edgeFadeBrush,
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      )
    }
  }
}
