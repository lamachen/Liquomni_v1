package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.model.LfmExecutionPhase
import com.example.model.OmniCallState
import com.example.model.OmniStatus
import com.example.ui.theme.LiquidAmber
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidCyanLight
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.LiquidViolet
import com.example.ui.theme.LiquidVioletLight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual Liquid Orb & Call Visualizer.
 *
 * Distinct visual behaviors across call lifecycle:
 * - IDLE (Standby): Dormant resting circle with calm ambient glow. No frantic waves or
 *   acoustic ripples. Clearly conveys that the model is in standby awaiting a call.
 * - CONNECTING: Dialing / Radar sonar pulses and orbital sweep indicating hardware mapping
 *   and connection establishment.
 * - CONNECTED: Fully dynamic, audio-reactive fluid dynamics responding to microphone input
 *   and 24kHz speech synthesis tokens.
 */
@Composable
fun LiquidOrbVisualizer(
  status: OmniStatus,
  executionPhase: LfmExecutionPhase,
  audioRms: Float,
  speechAmplitude: Float,
  callState: OmniCallState = OmniCallState.IDLE,
  modifier: Modifier = Modifier,
  onOrbClick: () -> Unit = {}
) {
  val infiniteTransition = rememberInfiniteTransition(label = "LiquidOrbTransition")

  val isProcessing = executionPhase == LfmExecutionPhase.CONV_PREFILL ||
                     executionPhase == LfmExecutionPhase.MAPPING_WEIGHTS ||
                     status == OmniStatus.THINKING ||
                     status == OmniStatus.LOADING

  val isIdle = callState == OmniCallState.IDLE
  val isConnecting = callState == OmniCallState.CONNECTING
  val isConnected = callState == OmniCallState.CONNECTED

  val rotDuration = when {
    isIdle -> 20000
    isConnecting -> 3000
    isProcessing -> 2400
    else -> 9000
  }

  val rotationAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = rotDuration, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "Rotation"
  )

  val breathDuration = when {
    isIdle -> 4000
    isConnecting -> 1200
    isProcessing -> 700
    else -> 2200
  }

  val breathScale by infiniteTransition.animateFloat(
    initialValue = if (isIdle) 0.98f else if (isProcessing) 0.90f else 0.95f,
    targetValue = if (isIdle) 1.02f else if (isProcessing) 1.10f else 1.05f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = breathDuration, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "Breathing"
  )

  val ripplePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = if (isConnecting) 1600 else 1300, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "RipplePhase"
  )

  val targetAmplitude = when {
    isIdle -> 0.02f
    isConnecting -> 0.10f
    executionPhase == LfmExecutionPhase.FAULT || status == OmniStatus.ERROR -> 0.15f
    status == OmniStatus.LISTENING -> (audioRms * 1.6f).coerceIn(0.12f, 1.0f)
    status == OmniStatus.SPEAKING || executionPhase == LfmExecutionPhase.OMNI_DECODE -> (speechAmplitude * 1.5f).coerceIn(0.25f, 1.0f)
    isProcessing -> 0.5f
    executionPhase == LfmExecutionPhase.OFFLINE -> 0.02f
    else -> 0.06f
  }

  val animatedAmp = remember { Animatable(0.02f) }
  LaunchedEffect(targetAmplitude) {
    animatedAmp.animateTo(
      targetValue = targetAmplitude,
      animationSpec = tween(durationMillis = 75, easing = LinearEasing)
    )
  }

  val primaryColor = when {
    isIdle -> LiquidTeal.copy(alpha = 0.5f)
    isConnecting -> LiquidAmber
    executionPhase == LfmExecutionPhase.OFFLINE -> Color(0xFFFF9800).copy(alpha = 0.6f)
    executionPhase == LfmExecutionPhase.MAPPING_WEIGHTS -> LiquidAmber
    executionPhase == LfmExecutionPhase.READY_IDLE -> if (status == OmniStatus.LISTENING) LiquidCyan else LiquidCyanLight
    executionPhase == LfmExecutionPhase.CONV_PREFILL -> LiquidAmber
    executionPhase == LfmExecutionPhase.OMNI_DECODE -> LiquidViolet
    executionPhase == LfmExecutionPhase.FAULT -> Color(0xFFFF1744)
    else -> LiquidTeal
  }

  val secondaryColor = when {
    isIdle -> Color(0xFF1E2838)
    isConnecting -> LiquidCyan
    executionPhase == LfmExecutionPhase.OFFLINE -> Color(0xFF5D4037)
    executionPhase == LfmExecutionPhase.MAPPING_WEIGHTS -> Color(0xFFFFB300)
    executionPhase == LfmExecutionPhase.READY_IDLE -> LiquidTeal
    executionPhase == LfmExecutionPhase.CONV_PREFILL -> LiquidVioletLight
    executionPhase == LfmExecutionPhase.OMNI_DECODE -> Color(0xFFFF4081)
    executionPhase == LfmExecutionPhase.FAULT -> Color(0xFFFF5252)
    else -> LiquidCyan
  }

  Box(
    modifier = modifier
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onOrbClick
      ),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val maxRadius = (size.minDimension / 2f) * 0.88f
      val baseRadius = maxRadius * 0.62f * breathScale

      // ==========================================
      // STATE 1: IDLE / STANDBY (Ruhezustand außerhalb des Anrufs)
      // ==========================================
      if (isIdle) {
        // Subtle resting aura (calm ambient glow, no glare)
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              LiquidTeal.copy(alpha = 0.12f),
              Color.Transparent
            ),
            center = center,
            radius = maxRadius * 1.1f
          ),
          radius = maxRadius * 1.1f,
          center = center
        )

        // Dark resting core disk
        val idleCoreRadius = baseRadius * 0.75f
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              Color(0xFF161E2E),
              Color(0xFF0F1420)
            ),
            center = center,
            radius = idleCoreRadius
          ),
          radius = idleCoreRadius,
          center = center
        )

        // Clean static boundary ring
        drawCircle(
          color = LiquidTeal.copy(alpha = 0.35f),
          radius = idleCoreRadius,
          center = center,
          style = Stroke(width = 1.5.dp.toPx())
        )

        // Inner resting concentric ring
        drawCircle(
          color = LiquidTeal.copy(alpha = 0.18f),
          radius = idleCoreRadius * 0.55f,
          center = center,
          style = Stroke(width = 1.dp.toPx())
        )

        // Center resting indicator dot
        drawCircle(
          color = LiquidTeal.copy(alpha = 0.7f),
          radius = 4.dp.toPx(),
          center = center
        )
        return@Canvas
      }

      // ==========================================
      // STATE 2: CONNECTING / DIALING (Rufe an / Hardware initialisieren)
      // ==========================================
      if (isConnecting) {
        // Dialing sonar radar rings expanding outward
        val radarRadius1 = baseRadius * (0.6f + ripplePhase * 0.8f)
        val radarAlpha1 = (1f - ripplePhase) * 0.6f
        drawCircle(
          color = LiquidAmber.copy(alpha = radarAlpha1),
          radius = radarRadius1,
          center = center,
          style = Stroke(width = 2.dp.toPx())
        )

        val radarRadius2 = baseRadius * (0.4f + ((ripplePhase + 0.5f) % 1f) * 0.8f)
        val radarAlpha2 = (1f - ((ripplePhase + 0.5f) % 1f)) * 0.4f
        drawCircle(
          color = LiquidCyan.copy(alpha = radarAlpha2),
          radius = radarRadius2,
          center = center,
          style = Stroke(width = 1.5.dp.toPx())
        )

        // Connecting central pulsing core
        val connCoreRadius = baseRadius * 0.65f
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              Color.White.copy(alpha = 0.9f),
              LiquidAmber.copy(alpha = 0.8f),
              LiquidAmber.copy(alpha = 0.2f),
              Color.Transparent
            ),
            center = center,
            radius = connCoreRadius
          ),
          radius = connCoreRadius,
          center = center
        )

        // Orbital sweep ring
        drawArc(
          brush = Brush.sweepGradient(
            colors = listOf(LiquidAmber, LiquidCyan, LiquidAmber),
            center = center
          ),
          startAngle = rotationAngle,
          sweepAngle = 120f,
          useCenter = false,
          topLeft = Offset(center.x - baseRadius * 0.85f, center.y - baseRadius * 0.85f),
          size = androidx.compose.ui.geometry.Size(baseRadius * 1.7f, baseRadius * 1.7f),
          style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        return@Canvas
      }

      // ==========================================
      // STATE 3: CONNECTED (Aktiver Anruf - Voll fluid-dynamisch & audio-reaktiv)
      // ==========================================
      // Outer atmospheric aura
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            primaryColor.copy(alpha = 0.35f + (animatedAmp.value * 0.3f)),
            secondaryColor.copy(alpha = 0.18f),
            Color.Transparent
          ),
          center = center,
          radius = maxRadius * 1.35f
        ),
        radius = maxRadius * 1.35f,
        center = center
      )

      // Radiating ripples during speaking/decoding or listening
      if (executionPhase == LfmExecutionPhase.OMNI_DECODE || status == OmniStatus.SPEAKING || status == OmniStatus.LISTENING) {
        val rippleRadius = baseRadius + (ripplePhase * maxRadius * 0.65f)
        val rippleAlpha = (1f - ripplePhase) * (0.35f + animatedAmp.value * 0.45f)
        drawCircle(
          color = primaryColor.copy(alpha = rippleAlpha.coerceIn(0f, 1f)),
          radius = rippleRadius,
          center = center,
          style = Stroke(width = 2.dp.toPx())
        )
      }

      // Inner glowing energy core
      val coreRadius = baseRadius * (0.58f + (animatedAmp.value * 0.28f))
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            Color.White.copy(alpha = 0.95f),
            primaryColor.copy(alpha = 0.9f),
            secondaryColor.copy(alpha = 0.7f),
            Color.Transparent
          ),
          center = center,
          radius = coreRadius
        ),
        radius = coreRadius,
        center = center
      )

      // Layer 1: Reactive Deformed Liquid Ring
      val pointsCount = 64
      val ringPath1 = Path()
      val amp1 = animatedAmp.value * 32f + 8f

      for (i in 0..pointsCount) {
        val angle = (i.toFloat() / pointsCount) * 2 * PI
        val rotOffset = (rotationAngle * PI / 180.0)
        val wave = sin(angle * 3 + rotOffset) * cos(angle * 2 - rotOffset * 0.8)
        val r = baseRadius + (wave * amp1).toFloat()
        val x = (center.x + r * cos(angle)).toFloat()
        val y = (center.y + r * sin(angle)).toFloat()

        if (i == 0) ringPath1.moveTo(x, y) else ringPath1.lineTo(x, y)
      }
      ringPath1.close()

      drawPath(
        path = ringPath1,
        brush = Brush.sweepGradient(
          colors = listOf(primaryColor, secondaryColor, primaryColor),
          center = center
        ),
        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
      )

      // Layer 2: Counter-rotating secondary fluid contour
      val ringPath2 = Path()
      val amp2 = animatedAmp.value * 24f + 6f

      for (i in 0..pointsCount) {
        val angle = (i.toFloat() / pointsCount) * 2 * PI
        val rotOffset = (-rotationAngle * 1.5 * PI / 180.0)
        val wave = sin(angle * 4 + rotOffset) * sin(angle * 2)
        val r = (baseRadius * 0.82f) + (wave * amp2).toFloat()
        val x = (center.x + r * cos(angle)).toFloat()
        val y = (center.y + r * sin(angle)).toFloat()

        if (i == 0) ringPath2.moveTo(x, y) else ringPath2.lineTo(x, y)
      }
      ringPath2.close()

      drawPath(
        path = ringPath2,
        color = secondaryColor.copy(alpha = 0.75f),
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
      )

      // PROCESSING (Conv1D state ingestion / prefill): Swirling orbiting light particles
      if (isProcessing) {
        val nodeCount = 6
        for (n in 0 until nodeCount) {
          val nodeAngle = (rotationAngle * 3.5f + (n * 360f / nodeCount)) * (PI / 180f).toFloat()
          val nodeR = baseRadius * (1.18f + 0.12f * sin(nodeAngle * 2))
          val nx = center.x + nodeR * cos(nodeAngle)
          val ny = center.y + nodeR * sin(nodeAngle)

          drawCircle(
            color = LiquidAmber.copy(alpha = 0.4f),
            radius = 7.dp.toPx(),
            center = Offset(nx, ny)
          )
          drawCircle(
            color = Color.White,
            radius = 3.5.dp.toPx(),
            center = Offset(nx, ny)
          )
        }
      }
    }
  }
}
